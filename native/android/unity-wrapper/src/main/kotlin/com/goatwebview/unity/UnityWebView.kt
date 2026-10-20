package com.goatwebview.unity

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.unity3d.player.UnityPlayer
import com.goatwebview.sdk.FallbackTheme
import com.goatwebview.sdk.WebViewCache
import com.goatwebview.sdk.WebViewController
import com.goatwebview.sdk.WebViewEvent
import kotlin.math.roundToInt

/**
 * Unity C#의 AndroidJavaObject가 생성하는 [WebViewController] 래퍼.
 *
 * 웹뷰는 Unity Activity의 콘텐츠 영역(android.R.id.content)에 숨긴 상태로 붙이며 setVisible(true)로 보여 준다.
 * 이벤트는 C# 컴포넌트가 붙은 GameObject([gameObjectName])의 메서드를 UnitySendMessage로 호출해 보낸다.
 * 메서드 이름은 UniWebView 이벤트 이름을 따르며 인자는 이벤트별 필드를 담은 JSON 문자열이다:
 * OnPageStarted, OnPageFinished, OnLoadingErrorReceived, OnMessageReceived, OnJavaScriptResult,
 * OnMessageBridgeUnavailable.
 * 탐색 상태는 UniWebView처럼 이벤트 없이 [canGoBack], [canGoForward], [getUrl]로 조회한다.
 * 오버스크롤은 [setScrollBounceEnabled](기본 켜짐), 캐시는 [clearCache]로 정한다.
 * 웹뷰를 만들기 전에는 static [clearSharedCache]로 앱 공용 캐시를 비운다.
 * UnitySendMessage는 이름으로 GameObject를 찾으므로 C#은 웹뷰마다 고유한 이름의 GameObject를 넘긴다.
 * 메시지 브리지는 [setAllowedMessageOrigins]로 출처를 정하기 전까지 없다.
 * 공개 메서드는 어느 스레드에서 호출해도 되며 호출 순서대로 메인 스레드에서 실행된다.
 * 잘못된 호출은 앱을 종료하지 않고 INVALID_CALL 오류로 알리며, [destroy] 이후의 호출은 무시한다.
 */
class UnityWebView internal constructor(
    private val activity: Activity,
    private val gameObjectName: String,
) {
    /** C#용 생성자. 현재 Unity Activity에 붙인다. */
    constructor(gameObjectName: String) : this(UnityPlayer.currentActivity, gameObjectName)

    private val mainHandler = Handler(Looper.getMainLooper())
    private var controller: WebViewController? = null
    private var destroyed = false
    // WebView 메서드는 UI 스레드에서만 부를 수 있으므로 마지막 NavigationChanged 값을 Unity 스레드에서 읽도록 보관한다.
    @Volatile private var currentUrl: String? = null
    @Volatile private var currentCanGoBack = false
    @Volatile private var currentCanGoForward = false

    init {
        post {
            if (destroyed) return@post
            val created = WebViewController(activity, eventListener = ::dispatch)
            controller = created
            activity.findViewById<ViewGroup>(android.R.id.content).addView(
                created.view,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
            )
            created.setVisible(false)
        }
    }

    /** 메시지를 받을 출처. 다음 탐색부터 적용된다. 빈 배열이면 브리지를 내리고, 잘못된 출처는 INVALID_CALL로 알린다. */
    fun setAllowedMessageOrigins(origins: Array<String>) = withController { it.setAllowedMessageOrigins(origins.toSet()) }

    fun loadUrl(url: String) = withController { it.loadUrl(url) }

    fun loadHtml(html: String, baseUrl: String?) = withController { it.loadHtml(html, baseUrl) }

    /** 결과는 같은 [requestId]와 함께 OnJavaScriptResult로 보낸다. */
    fun evaluateJavaScript(requestId: Int, script: String) = withController {
        it.evaluateJavaScript(script) { result ->
            sendMessage("OnJavaScriptResult", "requestId" to requestId, "result" to result)
        }
    }

    fun goBack() = withController { it.goBack() }

    fun goForward() = withController { it.goForward() }

    fun reload() = withController { it.reload() }

    fun stopLoading() = withController { it.stopLoading() }

    /** 마지막으로 알려진 방문 기록 상태. 어느 스레드에서 호출해도 바로 반환한다. */
    fun canGoBack(): Boolean = currentCanGoBack

    fun canGoForward(): Boolean = currentCanGoForward

    /** 마지막으로 알려진 현재 페이지 URL. 아직 탐색하지 않았으면 null이다. */
    fun getUrl(): String? = currentUrl

    fun setVisible(visible: Boolean) = withController { it.setVisible(visible) }

    fun setScrollBounceEnabled(enabled: Boolean) = withController { it.setScrollBounceEnabled(enabled) }

    /** 앱의 모든 웹뷰가 공유하는 캐시를 비운다. [includeDiskFiles]가 false면 메모리 캐시만 비운다. */
    fun clearCache(includeDiskFiles: Boolean) = withController { it.clearCache(includeDiskFiles) }

    /** 폴백 화면 테마. 0 LIGHT(기본), 1 DARK, 2 SYSTEM. 범위를 벗어나면 INVALID_CALL로 알린다. */
    fun setFallbackTheme(theme: Int) = withController {
        it.setFallbackTheme(FallbackTheme.entries.getOrNull(theme) ?: throw IllegalArgumentException("알 수 없는 폴백 테마입니다: $theme"))
    }

    fun setTopSafeAreaColor(r: Float, g: Float, b: Float, a: Float) = withController { it.setTopSafeAreaColor(r, g, b, a) }

    /** 네 모서리 반지름(px). C#은 Unity 화면 픽셀을 기기 픽셀로 바꿔 넘긴다. */
    fun setRoundCornerRadius(topLeft: Float, topRight: Float, bottomLeft: Float, bottomRight: Float) =
        withController { it.setRoundCornerRadius(topLeft, topRight, bottomLeft, bottomRight) }

    /**
     * 상단 safe area(상태바·컷아웃)를 뺀 콘텐츠 영역에 대한 비율(0..1)로 위치와 크기를 정한다.
     * 원점은 safe area 바로 아래의 좌상단이다. 프레임이 safe area 아래에 놓이므로 코어가 위쪽을 다시 비우지 않는다.
     * C#은 Unity 해상도 스케일링과 무관하도록 화면 픽셀을 Screen.width/height로 나눠서 넘긴다.
     */
    fun setFrame(x: Float, y: Float, width: Float, height: Float) = withController {
        val view = it.view
        val parent = view.parent as? ViewGroup ?: return@withController
        val inset = ViewCompat.getRootWindowInsets(parent)
            ?.getInsets(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout())?.top ?: 0
        val parentTop = IntArray(2).also(parent::getLocationInWindow)[1]
        val safeTop = (inset - parentTop).coerceAtLeast(0)
        val available = parent.height - safeTop
        view.layoutParams = (view.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = (x * parent.width).roundToInt()
            topMargin = safeTop + (y * available).roundToInt()
            this.width = (width * parent.width).roundToInt()
            this.height = (height * available).roundToInt()
        }
    }

    fun pause() = withController { it.pause() }

    fun resume() = withController { it.resume() }

    /** 재호출해도 안전하며 이 시점부터 이벤트를 전달하지 않는다. */
    fun destroy() = post {
        destroyed = true
        controller?.destroy()
        controller = null
    }

    private fun withController(block: (WebViewController) -> Unit) = post {
        val active = controller?.takeUnless { it.isDestroyed } ?: return@post
        try {
            block(active)
        } catch (e: RuntimeException) {
            sendLoadingError(INVALID_CALL, -1, e.message.orEmpty(), null)
        }
    }

    private fun post(block: () -> Unit) {
        mainHandler.post(block)
    }

    private fun dispatch(event: WebViewEvent) {
        when (event) {
            is WebViewEvent.PageStarted -> sendMessage("OnPageStarted", "url" to event.url)
            is WebViewEvent.PageFinished ->
                sendMessage("OnPageFinished", "url" to event.url, "statusCode" to event.statusCode)
            is WebViewEvent.NavigationChanged -> {
                currentUrl = event.url
                currentCanGoBack = event.canGoBack
                currentCanGoForward = event.canGoForward
            }
            is WebViewEvent.LoadingErrorReceived -> {
                // 렌더러 종료 시 코어가 이미 destroy했으므로 이후 호출은 무시한다.
                if (event.kind == WebViewEvent.LoadingErrorReceived.Kind.RENDERER) controller = null
                sendLoadingError(event.kind.name, event.errorCode, event.errorMessage, event.url)
            }
            is WebViewEvent.MessageReceived ->
                sendMessage("OnMessageReceived", "message" to event.message, "origin" to event.origin)
            // 폴백 화면 닫기 버튼으로 코어가 이미 해제했다. iOS 래퍼처럼 Unity에는 보내지 않고 이후 호출을 무시한다.
            WebViewEvent.Closed -> controller = null
            is WebViewEvent.MessageBridgeUnavailable -> sendMessage("OnMessageBridgeUnavailable", "reason" to event.reason)
        }
    }

    private fun sendLoadingError(kind: String, errorCode: Int, errorMessage: String, url: String?) =
        sendMessage("OnLoadingErrorReceived",
            "kind" to kind, "errorCode" to errorCode, "errorMessage" to errorMessage, "url" to url)

    private fun sendMessage(methodName: String, vararg fields: Pair<String, Any?>) =
        CallbackPayload.sendMessage(gameObjectName, methodName, *fields)

    companion object {
        const val INVALID_CALL = "INVALID_CALL"
        private const val LOG_TAG = "GoatWebView"

        /** 모든 웹뷰의 웹 콘솔 로그를 logcat에 출력할지 정한다. C#이 래퍼 클래스만 알면 되도록 코어 설정에 위임한다. */
        @JvmStatic
        fun setForwardWebConsoleToNativeOutput(enabled: Boolean) =
            WebViewController.setForwardWebConsoleToNativeOutput(enabled)

        /**
         * 웹뷰 인스턴스 없이 앱의 모든 웹뷰가 공유하는 메모리·디스크 캐시를 비운다. 어느 스레드에서 호출해도 된다.
         * 이벤트를 받을 GameObject가 없으므로 실패는 logcat에만 남긴다.
         */
        @JvmStatic
        fun clearSharedCache() {
            val activity = UnityPlayer.currentActivity
            Handler(Looper.getMainLooper()).post {
                try {
                    WebViewCache.clear(activity)
                } catch (e: RuntimeException) {
                    Log.e(LOG_TAG, "웹뷰 캐시를 초기화하지 못했습니다.", e)
                }
            }
        }
    }
}
