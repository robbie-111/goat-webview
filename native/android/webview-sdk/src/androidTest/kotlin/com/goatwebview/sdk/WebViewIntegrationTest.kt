package com.goatwebview.sdk

import android.app.Activity
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
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

    @Test fun consoleMessagesAreDeliveredAsEventsByLevel() {
        create(WebViewConfig(requestInterceptor = ::intercept))
        main {
            controller.loadHtml(
                "<script>console.log('m-log'); console.warn('m-warn'); console.error('m-error'); console.debug('m-debug')</script>",
                "$ORIGIN/console",
            )
        }
        awaitLoaded("/console")
        val expected = mapOf(
            "m-log" to WebViewEvent.ConsoleMessage.Level.LOG,
            "m-warn" to WebViewEvent.ConsoleMessage.Level.WARNING,
            "m-error" to WebViewEvent.ConsoleMessage.Level.ERROR,
            "m-debug" to WebViewEvent.ConsoleMessage.Level.DEBUG,
        )
        await { events.filterIsInstance<WebViewEvent.ConsoleMessage>().map { it.message }.containsAll(expected.keys) }
        for (message in events.filterIsInstance<WebViewEvent.ConsoleMessage>()) {
            assertEquals(expected[message.message], message.level)
            assertTrue(message.sourceId.endsWith("/console"))
            assertEquals(1, message.lineNumber)
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
            val webView = webView()
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

    @Test fun statusBarColorPaintsOnlyTheStatusBarInset() {
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
            assertEquals(100, content().top)
            assertEquals(controller.view.height, content().bottom)

            // 상태바 높이만큼만 칠한다.
            controller.setStatusBarColor(0f, 0f, 0f, 1f)
            ViewCompat.dispatchApplyWindowInsets(controller.view, insets)
            relayout()
            assertEquals(View.VISIBLE, band().visibility)
            assertEquals(100, band().height)
            assertEquals(Color.BLACK, (band().background as ColorDrawable).color)
            assertEquals(100, content().top)

            // a가 0이면 칠하지 않고 투명하게 비워 둔다.
            controller.setStatusBarColor(0f, 0f, 0f, 0f)
            ViewCompat.dispatchApplyWindowInsets(controller.view, insets)
            relayout()
            assertEquals(View.GONE, band().visibility)
            assertEquals(100, content().top)
        }
    }

    @Test fun webViewSkipsTopSafeAreaOnlyWhereItOverlaps() {
        create()
        instrumentation.waitForIdleSync()
        main {
            controller.setStatusBarColor(1f, 0f, 0f, 1f)
            ViewCompat.dispatchApplyWindowInsets(controller.view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 100, 0, 0))
                .build())
            // 상태바와 일부만 겹치면 겹친 만큼만, safe area 아래에 놓이면 비우지 않는다.
            relayout(top = 40)
            assertEquals(60, content().top)
            assertEquals(60, band().height)
            relayout(top = 150)
            assertEquals(0, content().top)
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
            assertEquals(container.height, content().bottom)

            // 3버튼 내비게이션(보임): 바 높이만큼 피한다.
            ViewCompat.dispatchApplyWindowInsets(container, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 126))
                .setInsets(WindowInsetsCompat.Type.tappableElement(), Insets.of(0, 0, 0, 126))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .build())
            relayout()
            assertEquals(container.height - 126, content().bottom)

            // 3버튼 내비게이션(immersive로 숨김): 일부 기기는 tappableElement를 그대로 돌려주지만 비우지 않는다.
            ViewCompat.dispatchApplyWindowInsets(container, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, 126))
                .setInsets(WindowInsetsCompat.Type.tappableElement(), Insets.of(0, 0, 0, 126))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), false)
                .build())
            relayout()
            assertEquals(container.height, content().bottom)

            // 가로 모드: 옆쪽 3버튼 바와 반대쪽 카메라 컷아웃을 피한다.
            ViewCompat.dispatchApplyWindowInsets(container, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 126, 0))
                .setInsets(WindowInsetsCompat.Type.tappableElement(), Insets.of(0, 0, 126, 0))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(90, 0, 0, 0))
                .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                .build())
            relayout()
            assertEquals(90, content().left)
            assertEquals(container.width - 126, content().right)
            assertEquals(container.height, content().bottom)
            assertEquals(0, content().top)
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
            assertEquals(0, content().left)
            assertEquals(0, content().top)
            assertEquals(c.width, content().right)
            assertEquals(c.height, content().bottom)
        }
    }

    @Test fun roundCornerRadiusClipsWebContentButNotTopBand() {
        create()
        main {
            val container = controller.view as ViewGroup
            val outline = Outline()
            // 풀스크린처럼 상태바 inset이 있어도 웹 콘텐츠 영역만 자르고 상단 띠·컨테이너는 자르지 않는다.
            controller.setStatusBarColor(0f, 0f, 0f, 1f)
            ViewCompat.dispatchApplyWindowInsets(container, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 100, 0, 0))
                .build())

            // 같은 반지름: outline 둥근 사각형으로 자른다. 음수는 0으로 본다.
            controller.setRoundCornerRadius(24f, 24f, 24f, 24f)
            assertTrue(content().clipToOutline)
            assertFalse(container.clipToOutline)
            assertFalse(band().clipToOutline)
            content().outlineProvider.getOutline(content(), outline)
            assertEquals(24f, outline.radius, 0f)

            // 모두 0이면 자르지 않는다.
            controller.setRoundCornerRadius(0f, -5f, 0f, 0f)
            assertFalse(content().clipToOutline)

            // 서로 다른 반지름: API 33 이상은 outline path, 그 미만은 clipPath(clipToOutline 꺼짐)로 자른다.
            controller.setRoundCornerRadius(16f, 12f, 8f, 4f)
            assertEquals(Build.VERSION.SDK_INT >= 33, content().clipToOutline)
        }
    }

    @Test fun fallbackCoversWebViewUntilLoadedAndClosesFromButton() {
        create(WebViewConfig(requestInterceptor = ::intercept))
        // 처음에는 웹뷰를 숨기고 폴백 화면(로딩 + 닫기)을 보인다.
        main {
            assertEquals(View.INVISIBLE, webView().visibility)
            assertEquals(FallbackView.State.LOADING, fallback().state)
        }
        main { controller.loadUrl("$ORIGIN/plain") }
        awaitLoaded("/plain")
        main {
            assertEquals(View.VISIBLE, webView().visibility)
            assertEquals(FallbackView.State.LOADED, fallback().state)
        }
        // 메인 문서 HTTP 400 이상이면 로드가 끝나도 웹뷰를 숨기고 실패 화면을 보인다.
        main { controller.loadUrl("$ORIGIN/error") }
        awaitLoaded("/error")
        main {
            assertEquals(View.INVISIBLE, webView().visibility)
            assertEquals(FallbackView.State.FAILED, fallback().state)
            fallback().closeButton.performClick()
        }
        assertTrue(controller.isDestroyed)
        assertEquals(WebViewEvent.Closed, events.last())
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
    // 컨테이너의 첫 자식은 WebView를 담은 웹 콘텐츠 영역(safe area를 피해 배치, 라운딩 대상)이다.
    private fun content() = (controller.view as ViewGroup).getChildAt(0)
    private fun band() = (controller.view as ViewGroup).getChildAt(1)
    // 웹 콘텐츠 영역 안: 폴백 화면이 아래(0), WebView가 위(1)다.
    private fun fallback() = (content() as ViewGroup).getChildAt(0) as FallbackView
    private fun webView() = (content() as ViewGroup).getChildAt(1)
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
