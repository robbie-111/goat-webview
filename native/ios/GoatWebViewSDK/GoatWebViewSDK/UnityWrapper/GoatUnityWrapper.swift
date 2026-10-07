// Unity C#의 [DllImport("__Internal")]가 부르는 C 진입점과, 웹뷰 이벤트를 UnitySendMessage로 C#에 보내는 래퍼.
// upstream WebView.mm의 _CWebViewPlugin_* 함수, LINE SDK Unity의 LineSDKNativeInterface·LineSDKNativeCallbackPayload에 대응한다.
// Unity 패키지에는 SDK 소스와 함께 복사되어 UnityFramework 한 모듈로 컴파일된다.
// Swift에서는 Unity 헤더(UnityInterface.h)를 가져올 수 없으므로 UnitySendMessage는 실행 중에 dlsym으로 찾고,
// 부모 뷰는 키 윈도의 rootViewController.view(Unity GL 뷰)를 쓴다.
// 모든 함수는 Unity 메인 스레드(= iOS 메인 스레드)에서 불린다고 보고 MainActor.assumeIsolated 안에서 SDK를 부른다.
// C 문자열 인자는 NULL일 수 있으며, 필요한 인자가 NULL이면 아무 일도 하지 않는다.

import UIKit

private typealias UnitySendMessageFunction = @convention(c) (UnsafePointer<CChar>, UnsafePointer<CChar>, UnsafePointer<CChar>) -> Void

// UnityFramework의 UnitySendMessage. Unity 밖(TestHost·샘플)에서는 nil이다.
// RTLD_DEFAULT((void *)-2)는 Swift로 가져와지지 않아 값을 직접 쓴다.
private let unitySendMessage: UnitySendMessageFunction? = dlsym(UnsafeMutableRawPointer(bitPattern: -2), "UnitySendMessage")
    .map { unsafeBitCast($0, to: UnitySendMessageFunction.self) }

// MARK: - GoatUnityWrapper

/// C#이 넘긴 GameObject 이름 하나에 WebViewPlugin 하나를 묶고, delegate 이벤트를 그 GameObject로 보낸다.
///
/// 메시지는 `UnitySendMessage(gameObjectName, 메서드 이름, 이벤트별 필드 JSON)`이다. 메서드 이름과 필드는 Android unity-wrapper와 같다:
/// OnPageStarted, OnPageFinished, OnLoadingErrorReceived, OnMessageReceived, OnJavaScriptResult.
/// 폴백 화면의 닫기 버튼은 SDK가 직접 해제하며 Unity에는 보내지 않는다.
@MainActor
final class GoatUnityWrapper: WebViewPluginDelegate {
    static let invalidCall = "INVALID_CALL"

    /// (GameObject 이름, 메서드 이름, JSON). 테스트가 바꿔 끼울 수 있다.
    static var sendMessage: @MainActor (String, String, String) -> Void = { name, method, message in
        unitySendMessage?(name, method, message)
    }

    static var wrappers: [String: GoatUnityWrapper] = [:]

    /// 이벤트를 받는 C# GameObject 이름이자 웹뷰 식별자
    let gameObjectName: String
    let plugin: WebViewPlugin
    let parentView: UIView
    // 이번 탐색의 메인 문서 HTTP 오류 상태. Android처럼 OnPageFinished의 statusCode로 보낸다.
    private var httpErrorStatus: Int?

    init(gameObjectName: String, plugin: WebViewPlugin, parentView: UIView) {
        self.gameObjectName = gameObjectName
        self.plugin = plugin
        self.parentView = parentView
        // delegate는 weak이므로 wrappers 테이블이 GoatUnityWrapper를 잡는다.
        plugin.delegate = self
    }

    static func wrapper(_ gameObjectName: UnsafePointer<CChar>?) -> GoatUnityWrapper? {
        string(gameObjectName).flatMap { wrappers[$0] }
    }

    // Unity GL 뷰 컨트롤러는 키 윈도의 루트다. 키 윈도가 없으면 첫 윈도를 쓴다.
    static func rootView() -> UIView? {
        let windows = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
        return (windows.first(where: \.isKeyWindow) ?? windows.first)?.rootViewController?.view
    }

    /// `fields`의 nil은 JSON null로 보낸다.
    func send(_ method: String, _ fields: [String: Any?]) {
        let payload = fields.mapValues { $0 ?? NSNull() }
        guard let json = WebViewPlugin.json(payload) else { return }
        GoatUnityWrapper.sendMessage(gameObjectName, method, json)
    }

    func webViewPlugin(_ plugin: WebViewPlugin, didReceiveMessage message: String) {
        send("OnMessageReceived", ["message": message, "origin": plugin.lastMessageOrigin])
    }

    func webViewPlugin(_ plugin: WebViewPlugin, didStartLoading url: String) {
        httpErrorStatus = nil
        send("OnPageStarted", ["url": url])
    }

    // SDK는 HTTP 오류(뒤로·앞으로 캐시에서 복원한 오류 문서 포함)를 로드 완료 전에 알린다.
    func webViewPlugin(_ plugin: WebViewPlugin, didFinishLoading url: String) {
        send("OnPageFinished", ["url": url, "statusCode": httpErrorStatus ?? 200])
        httpErrorStatus = nil
    }

    func webViewPlugin(_ plugin: WebViewPlugin, didFailWithError error: Error) {
        switch error {
        case WebViewPluginError.invalidURL(let url):
            // Android는 잘못된 URL을 INVALID_CALL로 알린다.
            sendLoadingError(GoatUnityWrapper.invalidCall, -1, "HTTP(S) URL만 사용할 수 있습니다: \(url)", nil)
        case WebViewPluginError.webContentProcessTerminated:
            sendLoadingError("RENDERER", -1, "웹 콘텐츠 프로세스가 종료되었습니다.", nil)
        default:
            let error = error as NSError
            let url = error.userInfo[NSURLErrorFailingURLStringErrorKey] as? String
                ?? (error.userInfo[NSURLErrorFailingURLErrorKey] as? URL)?.absoluteString
            sendLoadingError("NETWORK", error.code, error.localizedDescription, url)
        }
    }

    func webViewPlugin(_ plugin: WebViewPlugin, didReceiveHTTPError statusCode: Int, url: String) {
        httpErrorStatus = statusCode
    }

    // 폴백 화면의 닫기 버튼으로 SDK가 이미 해제했다. Unity에는 알리지 않고 이름만 비워 같은 이름으로 다시 만들 수 있게 한다.
    func webViewPluginDidClose(_ plugin: WebViewPlugin) {
        GoatUnityWrapper.wrappers.removeValue(forKey: gameObjectName)
    }

    private func sendLoadingError(_ kind: String, _ errorCode: Int, _ errorMessage: String, _ url: String?) {
        send("OnLoadingErrorReceived", ["kind": kind, "errorCode": errorCode, "errorMessage": errorMessage, "url": url])
    }
}

private func string(_ pointer: UnsafePointer<CChar>?) -> String? {
    pointer.map { String(cString: $0) }
}

// MARK: - 생성·해제

/// - Parameters:
///   - gameObjectName: 이벤트를 받을 C# GameObject 이름. 웹뷰마다 고유해야 하며, NULL이거나 이미 쓰는 이름이면 false다.
///   - allowedOrigins: `\n`으로 구분한 허용 출처. NULL이면 출처를 제한하지 않는다.
/// - Returns: 부모 뷰(키 윈도의 루트 뷰)를 찾지 못했거나 GameObject 이름이 잘못됐으면 false
@_cdecl("goatwebview_create")
public func goatwebview_create(_ gameObjectName: UnsafePointer<CChar>?, _ allowedOrigins: UnsafePointer<CChar>?) -> Bool {
    MainActor.assumeIsolated {
        guard let gameObjectName = string(gameObjectName), GoatUnityWrapper.wrappers[gameObjectName] == nil,
              let parentView = GoatUnityWrapper.rootView() else { return false }
        let origins = string(allowedOrigins).map { $0.split(separator: "\n").map(String.init) }
        let plugin = WebViewPlugin(parentView: parentView, allowedOrigins: origins)
        GoatUnityWrapper.wrappers[gameObjectName] = GoatUnityWrapper(gameObjectName: gameObjectName, plugin: plugin, parentView: parentView)
        return true
    }
}

/// 여러 번 불러도 안전하다. 이후 그 GameObject로는 메시지가 가지 않는다.
@_cdecl("goatwebview_dispose")
public func goatwebview_dispose(_ gameObjectName: UnsafePointer<CChar>?) {
    MainActor.assumeIsolated {
        guard let gameObjectName = string(gameObjectName) else { return }
        GoatUnityWrapper.wrappers.removeValue(forKey: gameObjectName)?.plugin.dispose()
    }
}

// MARK: - 로드·JavaScript

@_cdecl("goatwebview_load_url")
public func goatwebview_load_url(_ gameObjectName: UnsafePointer<CChar>?, _ url: UnsafePointer<CChar>?) {
    MainActor.assumeIsolated {
        guard let url = string(url) else { return }
        GoatUnityWrapper.wrapper(gameObjectName)?.plugin.loadURL(url)
    }
}

@_cdecl("goatwebview_load_html")
public func goatwebview_load_html(_ gameObjectName: UnsafePointer<CChar>?, _ html: UnsafePointer<CChar>?, _ baseURL: UnsafePointer<CChar>?) {
    MainActor.assumeIsolated {
        guard let html = string(html) else { return }
        GoatUnityWrapper.wrapper(gameObjectName)?.plugin.loadHTML(html, baseURL: string(baseURL))
    }
}

/// 결과는 같은 requestId와 함께 `OnJavaScriptResult`로 보낸다. `result`는 JSON 문자열이고 결과가 없으면 null이다.
/// 해제한 뒤에는 보내지 않는다.
@_cdecl("goatwebview_evaluate_js")
public func goatwebview_evaluate_js(_ gameObjectName: UnsafePointer<CChar>?, _ requestId: Int32, _ js: UnsafePointer<CChar>?) {
    MainActor.assumeIsolated {
        guard let wrapper = GoatUnityWrapper.wrapper(gameObjectName), let js = string(js) else { return }
        wrapper.plugin.evaluateJS(js) { [weak wrapper] json in
            wrapper?.send("OnJavaScriptResult", ["requestId": Int(requestId), "result": json])
        }
    }
}

// MARK: - 탐색

@_cdecl("goatwebview_can_go_back")
public func goatwebview_can_go_back(_ gameObjectName: UnsafePointer<CChar>?) -> Bool {
    MainActor.assumeIsolated {
        GoatUnityWrapper.wrapper(gameObjectName)?.plugin.canGoBack ?? false
    }
}

@_cdecl("goatwebview_can_go_forward")
public func goatwebview_can_go_forward(_ gameObjectName: UnsafePointer<CChar>?) -> Bool {
    MainActor.assumeIsolated {
        GoatUnityWrapper.wrapper(gameObjectName)?.plugin.canGoForward ?? false
    }
}

@_cdecl("goatwebview_go_back")
public func goatwebview_go_back(_ gameObjectName: UnsafePointer<CChar>?) {
    MainActor.assumeIsolated {
        GoatUnityWrapper.wrapper(gameObjectName)?.plugin.goBack()
    }
}

@_cdecl("goatwebview_go_forward")
public func goatwebview_go_forward(_ gameObjectName: UnsafePointer<CChar>?) {
    MainActor.assumeIsolated {
        GoatUnityWrapper.wrapper(gameObjectName)?.plugin.goForward()
    }
}

@_cdecl("goatwebview_reload")
public func goatwebview_reload(_ gameObjectName: UnsafePointer<CChar>?) {
    MainActor.assumeIsolated {
        GoatUnityWrapper.wrapper(gameObjectName)?.plugin.reload()
    }
}

@_cdecl("goatwebview_stop_loading")
public func goatwebview_stop_loading(_ gameObjectName: UnsafePointer<CChar>?) {
    MainActor.assumeIsolated {
        GoatUnityWrapper.wrapper(gameObjectName)?.plugin.stopLoading()
    }
}

// MARK: - 레이아웃·표시

/// `relative`가 아니면 Unity 화면 픽셀(Screen.width 기준)로 받아, upstream getScale처럼 부모 뷰의
/// contentScaleFactor로 나눠 포인트로 바꾼다. `relative`이면 부모 크기에 대한 비율(0...1)을 그대로 넘긴다.
@_cdecl("goatwebview_set_margins")
public func goatwebview_set_margins(_ gameObjectName: UnsafePointer<CChar>?, _ left: Float, _ top: Float, _ right: Float, _ bottom: Float, _ relative: Bool) {
    MainActor.assumeIsolated {
        guard let wrapper = GoatUnityWrapper.wrapper(gameObjectName) else { return }
        let scale = relative ? 1 : wrapper.parentView.contentScaleFactor
        wrapper.plugin.setMargins(left: CGFloat(left) / scale, top: CGFloat(top) / scale,
                                  right: CGFloat(right) / scale, bottom: CGFloat(bottom) / scale,
                                  relative: relative)
    }
}

@_cdecl("goatwebview_set_visibility")
public func goatwebview_set_visibility(_ gameObjectName: UnsafePointer<CChar>?, _ visible: Bool) {
    MainActor.assumeIsolated {
        GoatUnityWrapper.wrapper(gameObjectName)?.plugin.setVisibility(visible)
    }
}

@_cdecl("goatwebview_set_top_safe_area_color")
public func goatwebview_set_top_safe_area_color(_ gameObjectName: UnsafePointer<CChar>?, _ r: Float, _ g: Float, _ b: Float, _ a: Float) {
    MainActor.assumeIsolated {
        GoatUnityWrapper.wrapper(gameObjectName)?.plugin.setTopSafeAreaColor(r: r, g: g, b: b, a: a)
    }
}

@_cdecl("goatwebview_set_suspended")
public func goatwebview_set_suspended(_ gameObjectName: UnsafePointer<CChar>?, _ suspended: Bool) {
    MainActor.assumeIsolated {
        GoatUnityWrapper.wrapper(gameObjectName)?.plugin.setSuspended(suspended)
    }
}

@_cdecl("goatwebview_show_fallback")
public func goatwebview_show_fallback(_ gameObjectName: UnsafePointer<CChar>?) {
    MainActor.assumeIsolated {
        GoatUnityWrapper.wrapper(gameObjectName)?.plugin.showFallback()
    }
}
