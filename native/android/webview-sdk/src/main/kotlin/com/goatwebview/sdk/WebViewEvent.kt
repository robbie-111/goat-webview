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

    /** 폴백 화면의 닫기 버튼으로 SDK가 웹뷰를 해제한 뒤 한 번 보낸다. 이후 이벤트는 없다(iOS webViewPluginDidClose). */
    data object Closed : WebViewEvent

    /**
     * 웹 콘솔 메시지(iframe·잡히지 않은 예외 포함). SDK는 출력하지 않으며 토큰 등이 포함될 수 있으므로 출력 여부는 호스트가 정한다.
     * [sourceId]는 스크립트 URL, [lineNumber]는 그 줄 번호이며 알 수 없으면 빈 문자열과 0이다.
     */
    data class ConsoleMessage(
        val level: Level,
        val message: String,
        val sourceId: String,
        val lineNumber: Int,
    ) : WebViewEvent {
        /** WebView의 TIP 레벨은 LOG로 보낸다. */
        enum class Level { DEBUG, LOG, WARNING, ERROR }
    }
}
