import Foundation

// MARK: - Delegate

/// 웹뷰 이벤트. upstream이 Unity 게임 오브젝트로 보내던 콜백(CallFromJS 등)에 대응하며 모두 선택 구현이다.
/// 메인 스레드에서 호출된다.
@MainActor
public protocol WebViewPluginDelegate: AnyObject {
    /// 페이지의 `Unity.call(message)` (upstream CallFromJS)
    func webViewPlugin(_ plugin: WebViewPlugin, didReceiveMessage message: String)
    /// 메인 프레임 탐색 시작 (upstream CallOnStarted). 서버 리다이렉트로 목적지가 바뀔 때도 그 URL로 다시 불린다.
    func webViewPlugin(_ plugin: WebViewPlugin, didStartLoading url: String)
    /// 메인 프레임 로드 완료 (upstream CallOnLoaded)
    func webViewPlugin(_ plugin: WebViewPlugin, didFinishLoading url: String)
    /// 로드 실패, 잘못된 URL, 웹 콘텐츠 프로세스 종료 (upstream CallOnError)
    func webViewPlugin(_ plugin: WebViewPlugin, didFailWithError error: Error)
    /// 메인 문서의 HTTP 400 이상 응답 (upstream CallOnHttpError).
    /// 뒤로·앞으로 캐시에서 그 오류 문서를 다시 보일 때도 불린다.
    func webViewPlugin(_ plugin: WebViewPlugin, didReceiveHTTPError statusCode: Int, url: String)
    /// 폴백 화면의 닫기 버튼으로 SDK가 웹뷰를 해제한 뒤 불린다. 이후 다른 콜백은 없다.
    func webViewPluginDidClose(_ plugin: WebViewPlugin)
    /// 웹 콘솔 메시지(iframe·잡히지 않은 예외 포함). SDK는 출력하지 않으며, 토큰 등이 포함될 수 있으므로 출력 여부는 호스트가 정한다.
    func webViewPlugin(_ plugin: WebViewPlugin, didReceiveConsoleMessage message: WebViewConsoleMessage)
}

public extension WebViewPluginDelegate {
    func webViewPlugin(_ plugin: WebViewPlugin, didReceiveMessage message: String) {}
    func webViewPlugin(_ plugin: WebViewPlugin, didStartLoading url: String) {}
    func webViewPlugin(_ plugin: WebViewPlugin, didFinishLoading url: String) {}
    func webViewPlugin(_ plugin: WebViewPlugin, didFailWithError error: Error) {}
    func webViewPlugin(_ plugin: WebViewPlugin, didReceiveHTTPError statusCode: Int, url: String) {}
    func webViewPluginDidClose(_ plugin: WebViewPlugin) {}
    func webViewPlugin(_ plugin: WebViewPlugin, didReceiveConsoleMessage message: WebViewConsoleMessage) {}
}

/// 웹 콘솔 메시지 한 개. 필드는 Android `WebViewEvent.ConsoleMessage`와 같다.
public struct WebViewConsoleMessage: Equatable, Sendable {
    /// `console.info`는 `log`로 보낸다(Android와 같음).
    public enum Level: String, Sendable {
        case debug = "DEBUG"
        case log = "LOG"
        case warning = "WARNING"
        case error = "ERROR"
    }

    public let level: Level
    public let message: String
    /// 스크립트 URL. 알 수 없으면 페이지 URL이다.
    public let source: String
    /// `source`의 줄 번호. 알 수 없으면 0이다.
    public let line: Int

    public init(level: Level, message: String, source: String, line: Int) {
        self.level = level
        self.message = message
        self.source = source
        self.line = line
    }

    // 콘솔 스크립트가 보낸 {level, message, source, line}. 형식이 다르면 nil이다.
    init?(body: Any) {
        guard let body = body as? [String: Any],
              let level = (body["level"] as? String).flatMap(Level.init(rawValue:)),
              let message = body["message"] as? String else { return nil }
        self.init(level: level, message: message,
                  source: body["source"] as? String ?? "",
                  line: (body["line"] as? NSNumber)?.intValue ?? 0)
    }
}

public enum WebViewPluginError: Error, Equatable {
    /// http(s)가 아니거나 해석할 수 없는 URL
    case invalidURL(String)
    /// 웹 콘텐츠 프로세스가 종료됨 (upstream "webViewWebContentProcessDidTerminate")
    case webContentProcessTerminated
}
