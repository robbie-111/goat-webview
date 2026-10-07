package com.goatwebview.sdk

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TestWebViewActivity : Activity() {
    lateinit var root: FrameLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // API와 관계없이 루트가 window 맨 위(상태바 뒤)에서 시작하게 한다.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        root = FrameLayout(this)
        setContentView(root)
    }
}

@RunWith(AndroidJUnit4::class)
class WebViewIntegrationTest {
    private lateinit var scenario: ActivityScenario<TestWebViewActivity>
    private lateinit var controller: WebViewController
    private val events = CopyOnWriteArrayList<WebViewEvent>()
    private val requests = CopyOnWriteArrayList<String>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun setUp() { scenario = ActivityScenario.launch(TestWebViewActivity::class.java) }
    @After fun tearDown() {
        if (::controller.isInitialized) main { controller.destroy() }
        scenario.close()
    }

    @Test fun documentStartBridgeDeliversOnlyMainFrameMessages() {
        create(WebViewConfig(setOf(ORIGIN), ::intercept, listOf("window.atStart = !!window.WebViewKit;")))
        main { controller.loadUrl("$ORIGIN/bridge") }
        await { events.filterIsInstance<WebViewEvent.MessageReceived>().any { it.message == "MAIN" } }
        awaitJs("document.querySelector('iframe').contentDocument.readyState === 'complete'")
        assertEquals("true", js("window.atStart"))
        assertEquals("\"object\"", js("typeof document.querySelector('iframe').contentWindow.WebViewKit"))
        val messages = events.filterIsInstance<WebViewEvent.MessageReceived>()
        assertEquals(listOf("MAIN"), messages.map { it.message })
        assertEquals(ORIGIN, messages.single().origin)
    }

    @Test fun untrustedOriginHasNeitherMessageObjectNorDocumentStartScript() {
        create(WebViewConfig(setOf(ORIGIN), ::intercept, listOf("window.injected = true;")))
        main { controller.loadUrl("https://other.invalid/plain") }
        awaitLoaded("/plain")
        assertEquals("\"undefined\"", js("typeof window.WebViewKit"))
        assertEquals("\"undefined\"", js("typeof window.injected"))
        assertTrue(events.filterIsInstance<WebViewEvent.MessageReceived>().isEmpty())
    }

    @Test fun navigationBackForwardAndReloadUpdateHistory() {
        create(WebViewConfig(requestInterceptor = ::intercept))
        main { controller.loadUrl("$ORIGIN/one") }
        awaitLoaded("/one")
        main { controller.loadUrl("$ORIGIN/two") }
        awaitLoaded("/two")
        await { events.filterIsInstance<WebViewEvent.NavigationChanged>().lastOrNull()?.canGoBack == true }
        events.clear()
        main { controller.goBack() }
        awaitLoaded("/one")
        await { events.filterIsInstance<WebViewEvent.NavigationChanged>().lastOrNull()?.canGoForward == true }
        events.clear()
        main { controller.goForward() }
        awaitLoaded("/two")
        events.clear()
        main { controller.reload() }
        awaitLoaded("/two")
        assertEquals("\"/two\"", js("location.pathname"))
    }

    @Test fun htmlBaseUrlResolvesRelativeResourcesAndEvaluationReturnsJson() {
        create(WebViewConfig(requestInterceptor = ::intercept))
        main { controller.loadHtml("<html><head><title>한글 😀</title></head><body><img src='image.png'></body></html>", "$ORIGIN/content/") }
        await { requests.contains("$ORIGIN/content/image.png") }
        assertEquals("\"한글 😀\"", js("document.title"))
        assertEquals("{\"answer\":42}", js("({answer:42})"))
        main {
            controller.setVisible(false)
            assertEquals(View.INVISIBLE, controller.view.visibility)
            controller.setVisible(true)
            assertEquals(View.VISIBLE, controller.view.visibility)
            controller.pause()
            controller.resume()
        }
    }

    @Test fun pageFinishedCarriesMainDocumentStatusOnly() {
        create(WebViewConfig(requestInterceptor = ::intercept))
        main { controller.loadUrl("$ORIGIN/subresource-error") }
        awaitLoaded("/subresource-error")
        assertEquals(200, finished("/subresource-error").statusCode)
        main { controller.loadUrl("$ORIGIN/error") }
        awaitLoaded("/error")
        assertEquals(503, finished("/error").statusCode)
        assertTrue(events.filterIsInstance<WebViewEvent.LoadingErrorReceived>().isEmpty())
    }

    @Test fun webConsoleIsForwardedToLogcatOnlyWhenEnabled() {
        create(WebViewConfig(requestInterceptor = ::intercept))
        val marker = "console-" + System.nanoTime()
        shell("logcat -c")
        main { controller.loadHtml("<script>console.warn('$marker-off')</script>", "$ORIGIN/off") }
        awaitLoaded("/off")
        WebViewController.setForwardWebConsoleToNativeOutput(true)
        try {
            main { controller.loadHtml("<script>console.warn('$marker-on')</script>", "$ORIGIN/on") }
            awaitLoaded("/on")
            await { shell("logcat -d -v brief -s GoatWebView").lines().any { it.startsWith("W/GoatWebView") && "$marker-on" in it } }
            assertFalse("$marker-off" in shell("logcat -d -v brief -s GoatWebView"))
        } finally {
            WebViewController.setForwardWebConsoleToNativeOutput(false)
        }
    }

    @Test fun destroyDetachesViewAndSuppressesPendingEvaluationCallbacks() {
        create(WebViewConfig(requestInterceptor = ::intercept))
        main { controller.loadUrl("$ORIGIN/plain") }
        awaitLoaded("/plain")
        val callback = AtomicBoolean(false)
        main {
            controller.evaluateJavaScript("42") { callback.set(true) }
            controller.destroy()
            controller.destroy()
        }
        instrumentation.waitForIdleSync()
        assertTrue(controller.isDestroyed)
        assertFalse(callback.get())
        scenario.onActivity {
            assertEquals(0, it.root.childCount)
            assertThrows(IllegalStateException::class.java) { controller.loadUrl("$ORIGIN/plain") }
        }
    }

    @Test fun scrollBounceFollowsWebViewDefaultAndCanBeToggled() {
        create()
        main {
            val webView = (controller.view as ViewGroup).getChildAt(0)
            assertEquals(View.OVER_SCROLL_IF_CONTENT_SCROLLS, webView.overScrollMode)
            controller.setScrollBounceEnabled(false)
            assertEquals(View.OVER_SCROLL_NEVER, webView.overScrollMode)
            controller.setScrollBounceEnabled(true)
            assertEquals(View.OVER_SCROLL_IF_CONTENT_SCROLLS, webView.overScrollMode)
        }
    }

    @Test fun clearCacheKeepsTheWebViewUsableAndIsRejectedAfterDestroy() {
        create(WebViewConfig(requestInterceptor = ::intercept))
        main {
            controller.clearCache(false)
            controller.clearCache(true)
            controller.loadUrl("$ORIGIN/plain")
        }
        awaitLoaded("/plain")
        main {
            controller.destroy()
            assertThrows(IllegalStateException::class.java) { controller.clearCache(true) }
            assertThrows(IllegalStateException::class.java) { controller.setScrollBounceEnabled(true) }
        }
    }

    @Test fun topSafeAreaColorPaintsOnlyTheStatusBarInset() {
        create()
        instrumentation.waitForIdleSync()
        main {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 100, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 80))
                .build()

            // 기본값: 칠하지 않지만 WebView는 상태바 아래부터 아래 끝까지 채운다(하단 제스처 바는 피하지 않음).
            ViewCompat.dispatchApplyWindowInsets(controller.view, insets)
            relayout()
            assertEquals(View.GONE, band().visibility)
            assertEquals(100, webView().top)
            assertEquals(controller.view.height, webView().bottom)

            // 상태바 높이만큼만 칠한다.
            controller.setTopSafeAreaColor(0f, 0f, 0f, 1f)
            ViewCompat.dispatchApplyWindowInsets(controller.view, insets)
            relayout()
            assertEquals(View.VISIBLE, band().visibility)
            assertEquals(100, band().height)
            assertEquals(Color.BLACK, (band().background as ColorDrawable).color)
            assertEquals(100, webView().top)

            // a가 0이면 칠하지 않고 투명하게 비워 둔다.
            controller.setTopSafeAreaColor(0f, 0f, 0f, 0f)
            ViewCompat.dispatchApplyWindowInsets(controller.view, insets)
            relayout()
            assertEquals(View.GONE, band().visibility)
            assertEquals(100, webView().top)
        }
    }

    @Test fun webViewSkipsTopSafeAreaOnlyWhereItOverlaps() {
        create()
        instrumentation.waitForIdleSync()
        main {
            controller.setTopSafeAreaColor(1f, 0f, 0f, 1f)
            ViewCompat.dispatchApplyWindowInsets(controller.view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 100, 0, 0))
                .build())
            // 상태바와 일부만 겹치면 겹친 만큼만, safe area 아래에 놓이면 비우지 않는다.
            relayout(top = 40)
            assertEquals(60, webView().top)
            assertEquals(60, band().height)
            relayout(top = 150)
            assertEquals(0, webView().top)
            assertEquals(0, band().height)
        }
    }

    @Test fun webViewAvoidsButtonNavigationBarButNotGestureHandle() {
        create()
        instrumentation.waitForIdleSync()
        main {
            val container = controller.view

            // 제스처 내비게이션: 핸들은 터치 대상이 아니므로(tappableElement 0) 아래 끝까지 채운다.
            ViewCompat.dispatchApplyWindowInsets(container, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 60))
                .setInsets(WindowInsetsCompat.Type.tappableElement(), Insets.NONE)
                .build())
            relayout()
            assertEquals(container.height, webView().bottom)

            // 3버튼 내비게이션: 바 높이만큼 피한다.
            ViewCompat.dispatchApplyWindowInsets(container, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 126))
                .setInsets(WindowInsetsCompat.Type.tappableElement(), Insets.of(0, 0, 0, 126))
                .build())
            relayout()
            assertEquals(container.height - 126, webView().bottom)

            // 가로 모드: 옆쪽 3버튼 바와 반대쪽 카메라 컷아웃을 피한다.
            ViewCompat.dispatchApplyWindowInsets(container, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.tappableElement(), Insets.of(0, 0, 126, 0))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(90, 0, 0, 0))
                .build())
            relayout()
            assertEquals(90, webView().left)
            assertEquals(container.width - 126, webView().right)
            assertEquals(container.height, webView().bottom)
            assertEquals(0, webView().top)
        }
    }

    @Test fun popupInMiddleKeepsItsFullSize() {
        create()
        instrumentation.waitForIdleSync()
        main {
            ViewCompat.dispatchApplyWindowInsets(controller.view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 100, 0, 0))
                .setInsets(WindowInsetsCompat.Type.tappableElement(), Insets.of(0, 0, 0, 126))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(90, 0, 0, 0))
                .build())
            // 위·아래·옆 safe area 어느 쪽과도 겹치지 않는 팝업은 WebView가 컨테이너를 그대로 채운다.
            val window = controller.view.rootView
            val c = controller.view
            c.measure(exactly(window.width / 2), exactly(window.height / 2))
            c.layout(window.width / 4, window.height / 4, window.width / 4 + window.width / 2, window.height / 4 + window.height / 2)
            assertEquals(0, webView().left)
            assertEquals(0, webView().top)
            assertEquals(c.width, webView().right)
            assertEquals(c.height, webView().bottom)
        }
    }

    @Test fun publicCallsRequireMainThreadAndRejectNonHttpUrls() {
        create()
        assertThrows(IllegalStateException::class.java) { controller.pause() }
        main {
            assertThrows(IllegalArgumentException::class.java) { controller.loadUrl("file:///tmp/index.html") }
            assertThrows(IllegalArgumentException::class.java) { controller.loadHtml("hello", "javascript:alert(1)") }
        }
    }

    private fun create(config: WebViewConfig = WebViewConfig()) {
        scenario.onActivity {
            controller = WebViewController(it, config, events::add)
            it.root.addView(controller.view, FrameLayout.LayoutParams(-1, -1))
        }
    }
    private fun intercept(request: WebResourceRequest): WebResourceResponse {
        requests.add(request.url.toString())
        val path = request.url.path
        if (path == "/error") return response("Unavailable", 503)
        val body = when (path) {
            "/bridge" -> "<script>WebViewKit.postMessage('MAIN')</script><iframe src='/frame'></iframe>"
            "/frame" -> "<script>WebViewKit.postMessage('FRAME')</script>"
            "/subresource-error" -> "<img src='/error'>"
            else -> "<p>Test page</p>"
        }
        return response("<!doctype html><html><head><meta charset='utf-8'></head><body>$body</body></html>")
    }
    private fun response(html: String, status: Int = 200) = WebResourceResponse(
        "text/html", "UTF-8", status, if (status == 200) "OK" else "Unavailable", emptyMap(),
        ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
    )
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    /** 레이아웃은 다음 프레임에 일어나므로, 컨테이너를 window 맨 위에서 [top]만큼 내려 직접 다시 배치한다. */
    private fun relayout(top: Int = 0) {
        val c = controller.view
        val window = c.rootView
        c.measure(exactly(window.width), exactly(window.height - top))
        c.layout(0, top, window.width, window.height)
    }
    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
    private fun webView() = (controller.view as ViewGroup).getChildAt(0)
    private fun band() = (controller.view as ViewGroup).getChildAt(1)
    private fun js(script: String): String {
        val latch = CountDownLatch(1)
        val result = AtomicReference<String>()
        main { controller.evaluateJavaScript(script) { result.set(it); latch.countDown() } }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result.get()
    }
    private fun awaitLoaded(path: String) = await {
        events.filterIsInstance<WebViewEvent.PageFinished>().any { it.url.endsWith(path) }
    }
    private fun finished(path: String) = events.filterIsInstance<WebViewEvent.PageFinished>().last { it.url.endsWith(path) }
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .bufferedReader().use { it.readText() }
    private fun awaitJs(condition: String) = await { js(condition) == "true" }
    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail("웹뷰 이벤트 대기 초과")
    }
    companion object { const val ORIGIN = "https://core.test" }
}
