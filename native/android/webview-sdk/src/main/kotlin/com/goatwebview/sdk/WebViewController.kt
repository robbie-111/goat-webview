package com.goatwebview.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.core.graphics.Insets
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.ScriptHandler

/**
 * Unity에 의존하지 않는 웹뷰 한 개의 소유자.
 *
 * 생성과 모든 공개 API 호출은 메인 스레드에서 수행한다.
 * 호스트가 [view]를 ViewGroup에 추가하고 Activity 생명주기에 맞춰 pause/resume/destroy를 호출한다.
 * [view]는 WebView와 상단 safe area 띠([setTopSafeAreaColor])를 담는 컨테이너다.
 * WebView는 항상 상단 safe area(상태바·컷아웃) 중 컨테이너와 겹치는 부분을 비우고 그 아래부터 채운다.
 * safe area와 겹치지 않는 팝업형 컨테이너는 어느 방향으로도 줄이지 않는다.
 * WebView는 3버튼 내비게이션 바와 측면 디스플레이 컷아웃 중 컨테이너와 겹치는 만큼만 피하고, 제스처 내비게이션 핸들 뒤까지는 채운다.
 * JavaScript 평가 결과는 Android WebView가 반환한 JSON 문자열이며 오류/undefined는 null일 수 있다.
 */
@MainThread
@SuppressLint("SetJavaScriptEnabled")
class WebViewController(
    context: Context,
    config: WebViewConfig = WebViewConfig(),
    eventListener: (WebViewEvent) -> Unit,
) {
    private val webView: WebView
    private val containerView: FrameLayout
    private val topSafeAreaView: View
    // 상태바(디스플레이 컷아웃 포함) 위쪽 inset과, 비운 영역을 칠할지 여부. 비우는 것은 항상 한다.
    private var topSafeAreaInset = 0
    private var topSafeAreaColored = false
    // 3버튼 내비게이션 바와 측면 컷아웃 크기. 제스처 핸들은 터치 대상이 아니라 0이다.
    private var sideSafeAreaInsets = Insets.NONE
    private var listener: ((WebViewEvent) -> Unit)? = eventListener
    private var bridgeInstalled = false
    // 지금 메인 문서의 HTTP 상태. 새 탐색이 시작되면 200으로 되돌린다.
    private var mainFrameStatus = 200
    private val scriptHandlers = mutableListOf<ScriptHandler>()

    @Volatile
    var isDestroyed: Boolean = false
        private set

    val view: View
        get() {
            checkActive()
            return containerView
        }

    val isMessageBridgeAvailable: Boolean

    val currentUrl: String?
        get() {
            checkActive()
            return webView.url
        }

    init {
        checkMainThread()
        webView = WebView(context)
        topSafeAreaView = View(context).apply { visibility = View.GONE }
        // safe area와 겹치는 크기는 컨테이너의 window 위치에 달려 있으므로 onLayout에서 직접 배치한다.
        containerView = object : FrameLayout(context) {
            override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
                super.onLayout(changed, left, top, right, bottom)
                layoutInsideSafeArea(right - left, bottom - top)
            }
        }.apply {
            addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(topSafeAreaView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0, Gravity.TOP))
        }
        // insets는 소비하지 않고 그대로 하위 뷰에 넘긴다.
        ViewCompat.setOnApplyWindowInsetsListener(containerView) { _, insets ->
            topSafeAreaInset = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
            ).top
            // 3버튼 내비게이션 바와 측면 컷아웃만 피한다. 제스처 핸들 뒤는 웹이 채운다.
            sideSafeAreaInsets = insets.getInsets(
                WindowInsetsCompat.Type.tappableElement() or WindowInsetsCompat.Type.displayCutout(),
            )
            updateSafeArea()
            insets
        }
        val origins = config.allowedMessageOrigins.toSet()
        val interceptor = config.requestInterceptor

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }

        webView.webChromeClient = object : WebChromeClient() {
            // 웹 콘솔에는 토큰 등이 포함될 수 있으므로 기본으로는 출력하지 않는다.
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (forwardWebConsoleToNativeOutput) {
                    val text = "${message.message()} (${message.sourceId()}:${message.lineNumber()})"
                    when (message.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> Log.e(LOG_TAG, text)
                        ConsoleMessage.MessageLevel.WARNING -> Log.w(LOG_TAG, text)
                        ConsoleMessage.MessageLevel.DEBUG -> Log.d(LOG_TAG, text)
                        else -> Log.i(LOG_TAG, text)
                    }
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = if (isDestroyed) null else interceptor?.invoke(request)

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                mainFrameStatus = 200
                emit(WebViewEvent.PageStarted(url))
                emitNavigation()
            }

            override fun onPageFinished(view: WebView, url: String) {
                emit(WebViewEvent.PageFinished(url, mainFrameStatus))
                emitNavigation()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                emitNavigation()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    emit(WebViewEvent.LoadingErrorReceived(
                        WebViewEvent.LoadingErrorReceived.Kind.NETWORK,
                        error.errorCode,
                        error.description.toString(),
                        request.url.toString(),
                    ))
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                // HTTP 오류 문서도 로드는 끝나므로 PageFinished의 statusCode로 알린다.
                if (request.isForMainFrame) mainFrameStatus = errorResponse.statusCode
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                val callback = listener
                destroy()
                callback?.invoke(WebViewEvent.LoadingErrorReceived(
                    WebViewEvent.LoadingErrorReceived.Kind.RENDERER,
                    -1,
                    "웹 콘텐츠 프로세스가 종료되었습니다. 새 웹뷰를 생성하세요.",
                ))
                return true
            }
        }

        val supportsMessages = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        val supportsScripts = config.documentStartScripts.isEmpty() ||
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        isMessageBridgeAvailable = if (origins.isNotEmpty() &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) && supportsScripts
        ) {
            WebViewCompat.addWebMessageListener(webView, BRIDGE_NAME, origins) {
                    _, message, origin, isMainFrame, _ ->
                if (isMainFrame && message.type == WebMessageCompat.TYPE_STRING) {
                    emit(WebViewEvent.MessageReceived(message.data.orEmpty(), origin.toString()))
                }
            }
            bridgeInstalled = true
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                for (script in config.documentStartScripts) {
                    scriptHandlers.add(WebViewCompat.addDocumentStartJavaScript(webView, script, origins))
                }
            }
            true
        } else {
            if (origins.isNotEmpty()) {
                emit(WebViewEvent.MessageBridgeUnavailable(
                    if (!supportsMessages) "설치된 Android System WebView가 메시지 수신을 지원하지 않습니다."
                    else "설치된 Android System WebView가 문서 시작 스크립트를 지원하지 않습니다.",
                ))
            }
            false
        }
    }

    fun loadUrl(url: String) {
        checkActive()
        require(url.toUri().scheme?.lowercase() in setOf("https", "http")) {
            "HTTP(S) URL만 사용할 수 있습니다."
        }
        webView.loadUrl(url)
    }

    /** baseUrl을 지정하면 상대 경로 및 메시지 출처 판정에 사용한다. */
    fun loadHtml(html: String, baseUrl: String? = null) {
        checkActive()
        require(baseUrl == null || baseUrl.toUri().scheme?.lowercase() in setOf("https", "http")) {
            "baseUrl은 HTTP(S) URL이어야 합니다."
        }
        webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
    }

    fun evaluateJavaScript(script: String, onResult: (String?) -> Unit = {}) {
        checkActive()
        webView.evaluateJavascript(script) { result ->
            if (!isDestroyed) onResult(result)
        }
    }

    fun goBack() {
        checkActive()
        if (webView.canGoBack()) webView.goBack()
    }

    fun goForward() {
        checkActive()
        if (webView.canGoForward()) webView.goForward()
    }

    fun reload() {
        checkActive()
        webView.reload()
    }

    fun stopLoading() {
        checkActive()
        webView.stopLoading()
    }

    fun setVisible(visible: Boolean) {
        checkActive()
        containerView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    /** 오버스크롤 효과를 켜거나 끈다. 기본값은 WebView 기본 동작(켜짐)이다. */
    fun setScrollBounceEnabled(enabled: Boolean) {
        checkActive()
        webView.overScrollMode = if (enabled) View.OVER_SCROLL_IF_CONTENT_SCROLLS else View.OVER_SCROLL_NEVER
    }

    /** 앱의 모든 WebView가 공유하는 리소스 캐시를 비운다. [includeDiskFiles]가 false면 메모리 캐시만 비운다. */
    fun clearCache(includeDiskFiles: Boolean) {
        checkActive()
        webView.clearCache(includeDiskFiles)
    }

    /**
     * WebView가 비워 둔 위쪽 상태바(디스플레이 컷아웃 포함) 영역을 (r, g, b, a) 색으로 칠한다.
     * WebView는 색과 관계없이 항상 그 아래부터 채우며, 각 값은 0..1이고 [a]가 0이면(기본값) 칠하지 않아 투명하다.
     * 아래쪽 시스템 바와 좌우는 칠하지 않는다.
     * Unity C#의 Color를 변환 없이 넘길 수 있도록 iOS와 같은 원시 타입을 받는다. 상태바 아이콘 색은 호스트(Activity)가 정한다.
     */
    fun setTopSafeAreaColor(r: Float, g: Float, b: Float, a: Float) {
        checkActive()
        topSafeAreaColored = a > 0f
        if (topSafeAreaColored) topSafeAreaView.setBackgroundColor(Color.argb(a, r, g, b))
        updateSafeArea()
        ViewCompat.requestApplyInsets(containerView)
    }

    fun pause() {
        checkActive()
        webView.onPause()
    }

    fun resume() {
        checkActive()
        webView.onResume()
    }

    /** 재호출해도 안전하며 이 시점부터 이벤트와 평가 결과를 전달하지 않는다. */
    fun destroy() {
        checkMainThread()
        if (isDestroyed) return
        isDestroyed = true
        listener = null
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            for (script in scriptHandlers) script.remove()
        }
        scriptHandlers.clear()
        if (bridgeInstalled && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.removeWebMessageListener(webView, BRIDGE_NAME)
            bridgeInstalled = false
        }
        ViewCompat.setOnApplyWindowInsetsListener(containerView, null)
        (containerView.parent as? ViewGroup)?.removeView(containerView)
        containerView.removeView(webView)
        webView.stopLoading()
        webView.webChromeClient = null
        webView.webViewClient = WebViewClient()
        webView.removeAllViews()
        webView.destroy()
    }

    /** inset이나 색이 바뀌면 띠 표시 여부를 갱신하고 다시 배치한다. 위치 계산은 [layoutInsideSafeArea]가 한다. */
    private fun updateSafeArea() {
        topSafeAreaView.visibility = if (topSafeAreaColored) View.VISIBLE else View.GONE
        containerView.requestLayout()
    }

    /**
     * 컨테이너가 safe area와 겹치는 만큼만 비우고 WebView를 배치한다.
     * 풀스크린이면 상태바·3버튼 바·컷아웃을 피하고, safe area와 겹치지 않는 팝업은 컨테이너를 그대로 채운다.
     * layoutParams를 바꾸지 않으므로 레이아웃을 다시 요청하지 않는다.
     */
    private fun layoutInsideSafeArea(width: Int, height: Int) {
        val (x, y) = IntArray(2).also(containerView::getLocationInWindow)
        val window = containerView.rootView
        val side = sideSafeAreaInsets
        val top = (topSafeAreaInset - y).coerceIn(0, height)
        val bottom = (side.bottom - (window.height - (y + height))).coerceIn(0, height - top)
        val left = (side.left - x).coerceIn(0, width)
        val right = (side.right - (window.width - (x + width))).coerceIn(0, width - left)
        val webWidth = width - left - right
        val webHeight = height - top - bottom
        if (webView.measuredWidth != webWidth || webView.measuredHeight != webHeight) {
            webView.measure(exactly(webWidth), exactly(webHeight))
        }
        webView.layout(left, top, left + webWidth, top + webHeight)
        if (topSafeAreaView.visibility == View.VISIBLE) {
            topSafeAreaView.measure(exactly(width), exactly(top))
            topSafeAreaView.layout(0, 0, width, top)
        }
    }

    private fun exactly(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun emit(event: WebViewEvent) {
        if (!isDestroyed) listener?.invoke(event)
    }

    private fun emitNavigation() {
        if (!isDestroyed) {
            emit(WebViewEvent.NavigationChanged(webView.url, webView.canGoBack(), webView.canGoForward()))
        }
    }

    private fun checkActive() {
        checkMainThread()
        check(!isDestroyed) { "해제된 웹뷰입니다. 새 WebViewController를 생성하세요." }
    }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "웹뷰 API는 메인 스레드에서 호출해야 합니다." }
    }

    companion object {
        const val BRIDGE_NAME = "WebViewKit"
        private const val LOG_TAG = "GoatWebView"

        @Volatile
        private var forwardWebConsoleToNativeOutput = false

        /**
         * 모든 웹뷰의 웹 콘솔 로그를 logcat(GoatWebView 태그)에 레벨별로 출력할지 정한다. 기본값은 false다.
         * UniWebView의 SetForwardWebConsoleToNativeOutput에 대응한다.
         */
        @JvmStatic
        fun setForwardWebConsoleToNativeOutput(enabled: Boolean) {
            forwardWebConsoleToNativeOutput = enabled
        }
    }
}
