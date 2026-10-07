package com.goatwebview.unity

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class TestUnityActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Unity처럼 콘텐츠가 window 맨 위(상태바 뒤)에서 시작하게 한다.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(FrameLayout(this))
    }
}

/** Unity처럼 메인 스레드가 아닌 테스트 스레드에서 래퍼를 호출하고, UnitySendMessage 대신 전송 내용을 받는다. */
@RunWith(AndroidJUnit4::class)
class UnityWebViewTest {
    private lateinit var scenario: ActivityScenario<TestUnityActivity>
    private lateinit var activity: Activity
    private lateinit var webView: UnityWebView
    private val sent = CopyOnWriteArrayList<Sent>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun setUp() {
        CallbackPayload.sender = { gameObject, method, message -> sent.add(Sent(gameObject, method, JSONObject(message))) }
        scenario = ActivityScenario.launch(TestUnityActivity::class.java)
        scenario.onActivity { activity = it }
    }

    @After fun tearDown() {
        if (::webView.isInitialized) webView.destroy()
        instrumentation.waitForIdleSync()
        scenario.close()
    }

    @Test fun eventsGoToTheGivenGameObjectInCallOrder() {
        webView = UnityWebView(activity, "SupportWebView", arrayOf(ORIGIN), emptyArray())
        webView.loadHtml("<script>WebViewKit.postMessage('ready')</script>", "$ORIGIN/")
        await { has("OnMessageReceived") { it.getString("message") == "ready" && it.getString("origin") == ORIGIN } }
        await { has("OnPageFinished") { it.getString("url") == "$ORIGIN/" && it.getInt("statusCode") == 200 } }
        assertTrue(has("OnPageStarted"))
        webView.evaluateJavaScript(7, "({answer: 42})")
        await { has("OnJavaScriptResult") { it.getInt("requestId") == 7 && it.getString("result") == "{\"answer\":42}" } }
        assertTrue(sent.all { it.gameObject == "SupportWebView" })
    }

    @Test fun navigationStateIsQueriedWithoutEvents() {
        webView = UnityWebView(activity, "WebView", emptyArray(), emptyArray())
        assertFalse(webView.canGoBack())
        assertNull(webView.getUrl())
        webView.loadHtml("<p>first</p>", "$ORIGIN/first")
        await { has("OnPageFinished") }
        // 문서를 다시 로드하지 않는 SPA 이동도 반영된다.
        webView.evaluateJavaScript(1, "history.pushState({}, '', '/second')")
        await { webView.getUrl() == "$ORIGIN/second" && webView.canGoBack() }
        assertFalse(webView.canGoForward())
        assertTrue(sent.none { it.method == "OnNavigationChanged" })
    }

    @Test fun startsHiddenUntilShown() {
        webView = UnityWebView(activity, "WebView", emptyArray(), emptyArray())
        instrumentation.waitForIdleSync()
        scenario.onActivity { assertEquals(View.INVISIBLE, container().visibility) }
        webView.setVisible(true)
        instrumentation.waitForIdleSync()
        scenario.onActivity { assertEquals(View.VISIBLE, container().visibility) }
    }

    @Test fun scrollBounceAndCacheCallsReachTheWebView() {
        webView = UnityWebView(activity, "WebView", emptyArray(), emptyArray())
        webView.setScrollBounceEnabled(false)
        webView.clearCache(true)
        instrumentation.waitForIdleSync()
        scenario.onActivity {
            val core = (container() as ViewGroup).getChildAt(0)
            assertEquals(View.OVER_SCROLL_NEVER, core.overScrollMode)
        }
        assertTrue(sent.none { it.method == "OnLoadingErrorReceived" })
    }

    @Test fun invalidCallsAreReportedInsteadOfCrashing() {
        webView = UnityWebView(activity, "WebView", emptyArray(), emptyArray())
        webView.loadUrl("file:///sdcard/index.html")
        await { has("OnLoadingErrorReceived") { it.getString("kind") == UnityWebView.INVALID_CALL && it.getInt("errorCode") == -1 } }
    }

    @Test fun invalidConfigThrowsFromConstructor() {
        assertThrows(IllegalArgumentException::class.java) {
            UnityWebView(activity, "WebView", arrayOf("$ORIGIN/path"), emptyArray())
        }
    }

    @Test fun frameUsesRatiosOfContentBelowTopSafeArea() {
        webView = UnityWebView(activity, "WebView", emptyArray(), emptyArray())
        webView.setFrame(0.1f, 0.2f, 0.5f, 0.25f)
        instrumentation.waitForIdleSync()
        // 레이아웃은 다음 프레임에 일어나므로 컨테이너가 새 위치에 놓일 때까지 기다린다.
        await {
            var laidOut = false
            scenario.onActivity {
                laidOut = container().top == (container().layoutParams as FrameLayout.LayoutParams).topMargin &&
                    (container() as ViewGroup).getChildAt(0).top == 0
            }
            laidOut
        }
        scenario.onActivity {
            val content = content()
            val safeTop = ViewCompat.getRootWindowInsets(content)!!
                .getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()).top
            val available = content.height - safeTop
            val params = container().layoutParams as FrameLayout.LayoutParams
            assertEquals(Math.round(content.width * 0.1f), params.leftMargin)
            assertEquals(safeTop + Math.round(available * 0.2f), params.topMargin)
            assertEquals(Math.round(content.width * 0.5f), params.width)
            assertEquals(Math.round(available * 0.25f), params.height)
            // 프레임이 safe area와 겹치지 않으므로 코어 WebView는 컨테이너를 그대로 채운다.
            val core = (container() as ViewGroup).getChildAt(0)
            assertEquals(0, core.top)
            assertEquals(params.width, core.width)
            assertEquals(params.height, core.height)
        }
    }

    @Test fun destroyDetachesViewAndIgnoresLaterCalls() {
        webView = UnityWebView(activity, "WebView", emptyArray(), emptyArray())
        webView.destroy()
        webView.destroy()
        webView.loadHtml("<p>ignored</p>", null)
        webView.evaluateJavaScript(1, "1")
        webView.setScrollBounceEnabled(true)
        webView.clearCache(true)
        instrumentation.waitForIdleSync()
        Thread.sleep(500)
        assertTrue(sent.isEmpty())
        scenario.onActivity { assertEquals(1, content().childCount) }
    }

    // 테스트 Activity가 setContentView로 붙인 FrameLayout 다음에 웹뷰 컨테이너가 붙는다.
    private fun content() = activity.findViewById<ViewGroup>(android.R.id.content)

    private fun container() = content().getChildAt(1)

    private fun has(method: String, match: (JSONObject) -> Boolean = { true }) =
        sent.any { it.method == method && match(it.payload) }

    private fun await(condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < end) {
            if (condition()) return
            Thread.sleep(50)
        }
        fail("웹뷰 이벤트 대기 초과: $sent")
    }

    private data class Sent(val gameObject: String, val method: String, val payload: JSONObject)

    companion object { const val ORIGIN = "https://unity.test" }
}
