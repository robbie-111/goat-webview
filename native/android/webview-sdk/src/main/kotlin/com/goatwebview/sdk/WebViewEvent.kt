package com.goatwebview.sdk

/** 모든 이벤트는 Android 메인 스레드에서 전달된다. 이름은 UniWebView 이벤트(OnPageStarted 등)에서 On을 뺀 것이다. */
sealed interface WebViewEvent {
    data class PageStarted(val url: String) : WebViewEvent

    /** [statusCode]는 메인 문서의 HTTP 상태다. HTTP 오류 문서도 로드는 끝나므로 이 값으로만 알린다. */
    data class PageFinished(val url: String, val statusCode: Int) : WebViewEvent

    data class NavigationChanged(
        val url: String?,
        val canGoBack: Boolean,
        val canGoForward: Boolean,
    ) : WebViewEvent

    /** 최상위 문서의 네트워크·렌더러 오류. 하위 리소스 오류는 포함하지 않는다. RENDERER 이후에는 새 웹뷰를 생성한다. */
    data class LoadingErrorReceived(
        val kind: Kind,
        val errorCode: Int,
        val errorMessage: String,
        val url: String? = null,
    ) : WebViewEvent {
        enum class Kind { NETWORK, RENDERER }
    }

    data class MessageReceived(val message: String, val origin: String) : WebViewEvent
    data class MessageBridgeUnavailable(val reason: String) : WebViewEvent
}
