/*
 * Copyright (C) 2011 Keijiro Takahashi
 * Copyright (C) 2012 GREE, Inc.
 *
 * This software is provided 'as-is', without any express or implied
 * warranty.  In no event will the authors be held liable for any damages
 * arising from the use of this software.
 *
 * Permission is granted to anyone to use this software for any purpose,
 * including commercial applications, and to alter it and redistribute it
 * freely, subject to the following restrictions:
 *
 * 1. The origin of this software must not be misrepresented; you must not
 *    claim that you wrote the original software. If you use this software
 *    in a product, an acknowledgment in the product documentation would be
 *    appreciated but is not required.
 * 2. Altered source versions must be plainly marked as such, and must not be
 *    misrepresented as being the original software.
 * 3. This notice may not be removed or altered from any source distribution.
 */

// 수정됨: gree/unity-webview plugins/iOS/WebView.mm(CWebViewPlugin)을 Swift로 옮기고 고친 파일이다.
// - 구형 웹뷰 경로를 빼고 WKWebView만 남겼다. Unity 엔진 API 호출(메시지 전송, GL 뷰 컨트롤러 조회)은
//   delegate와 호스트가 넘기는 parentView로 바꿨다.
// - 메시지 수신에 메인 프레임·허용 출처 검사를, 로드 전후에 네이티브 폴백 화면을, 상단 safe area 띠를 추가했다.
// - 서버 리다이렉트도 로드 시작으로 알린다.
// - 스크롤 바운스 설정과 웹 콘솔 로그의 네이티브 출력(Android webview-sdk와 같은 기능)을 추가했다.

import os
import UIKit
import WebKit

// MARK: - WebViewPlugin

/// WKWebView 한 개를 소유한다. upstream CWebViewPlugin에 대응한다.
///
/// `parentView` 안에 컨테이너를 붙이고, 그 안에 폴백 화면과 웹뷰를 겹쳐 둔다.
/// 메인 문서 로드가 끝나야 웹뷰가 폴백 화면을 덮고, 로드에 실패하면 다시 폴백 화면(닫기 버튼)이 드러난다.
/// upstream처럼 처음에는 숨겨져 있으므로 `setVisibility(true)`로 보여 준다.
/// 모든 API는 메인 스레드에서 호출하며, 다 쓰면 `dispose()`를 호출한다.
@MainActor
public final class WebViewPlugin: NSObject {
    static let messageHandlerName = "unityControl"
    static let consoleHandlerName = "goatConsole"
    private static let consoleLogger = Logger(subsystem: "GoatWebView", category: "console")
    private static var forwardWebConsoleToNativeOutput = false

    /// 모든 웹뷰의 웹 콘솔 로그(`console.log` 등)를 네이티브 로그(subsystem `GoatWebView`)에 레벨별로 출력할지 정한다.
    /// 기본값은 false이며 이미 만든 웹뷰에도 바로 적용된다. 웹 콘솔에는 토큰 등이 포함될 수 있다.
    /// UniWebView의 SetForwardWebConsoleToNativeOutput, Android `WebViewController.setForwardWebConsoleToNativeOutput`에 대응한다.
    public static func setForwardWebConsoleToNativeOutput(_ enabled: Bool) {
        forwardWebConsoleToNativeOutput = enabled
    }

    public weak var delegate: WebViewPluginDelegate?

    private(set) var webView: WKWebView?
    private(set) var containerView: ContainerView?
    private(set) var fallbackView: FallbackView?
    private var navigationHandler: NavigationHandler?
    private let allowedOrigins: Set<String>?
    // 지금 메인 프레임 탐색을 실패로 봤는지(HTTP 오류, 로드 실패, showFallback()). 켜져 있으면 로드가 끝나도
    // 웹뷰를 드러내지 않고, 다음 탐색이 시작되면 끈다.
    private var currentNavigationFailed = false
    // 지금 탐색이 메인 문서 응답을 받았는지와, 받은 응답이 HTTP 오류라면 그 상태 코드
    private var receivedMainFrameResponse = false
    private var currentHTTPErrorStatus: Int?
    // HTTP 오류 문서였던 방문 기록 항목과 상태 코드. 뒤로·앞으로 캐시에서 복원한 문서는 응답을 다시 받지 않으므로 여기서 찾는다.
    private let httpErrorItems = NSMapTable<WKBackForwardListItem, NSNumber>.weakToStrongObjects()
    // 마지막으로 delegate에 넘긴 메시지의 출처. Unity 래퍼가 OnMessageReceived의 origin으로 쓴다.
    private(set) var lastMessageOrigin = ""

    /// - Parameters:
    ///   - parentView: 웹뷰를 붙일 뷰 (upstream에서는 Unity GL 뷰 컨트롤러의 view)
    ///   - allowedOrigins: `Unity.call`을 허용할 출처(`https://example.com` 형식).
    ///     `nil`이면 upstream처럼 출처를 제한하지 않는다. 어떤 경우든 메인 프레임 메시지만 받는다.
    public init(parentView: UIView, allowedOrigins: [String]? = nil) {
        self.allowedOrigins = allowedOrigins.map { Set($0.compactMap(WebViewPlugin.normalizedOrigin(_:))) }
        super.init()

        let configuration = WKWebViewConfiguration()
        let controller = WKUserContentController()
        controller.add(WeakScriptMessageHandler(delegate: self), name: WebViewPlugin.messageHandlerName)
        controller.add(WeakScriptMessageHandler(delegate: self), name: WebViewPlugin.consoleHandlerName)
        controller.addUserScript(WKUserScript(
            source: WebViewPlugin.bridgeScript(allowedOrigins: self.allowedOrigins),
            injectionTime: .atDocumentStart,
            forMainFrameOnly: true))
        // 콘솔 전달 스크립트는 항상 설치하고, 출력 여부는 네이티브가 정한다(설정을 바로 반영하기 위해).
        controller.addUserScript(WKUserScript(
            source: WebViewPlugin.consoleScript,
            injectionTime: .atDocumentStart,
            forMainFrameOnly: false))
        configuration.userContentController = controller
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        configuration.websiteDataStore = .default()

        let containerView = ContainerView(frame: parentView.bounds)
        containerView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        containerView.isHidden = true

        let fallbackView = FallbackView(frame: containerView.contentView.bounds)
        fallbackView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        fallbackView.closeHandler = { [weak self] in
            self?.close()
        }

        let webView = WKWebView(frame: containerView.contentView.bounds, configuration: configuration)
        webView.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        webView.isHidden = true
        // cf. https://rick38yip.medium.com/wkwebview-weird-spacing-issue-in-ios-13-54a4fc686f72
        webView.scrollView.contentInsetAdjustmentBehavior = .never
#if DEBUG
        if #available(iOS 16.4, *) {
            webView.isInspectable = true
        }
#endif
        let navigationHandler = NavigationHandler(plugin: self)
        webView.navigationDelegate = navigationHandler

        // 폴백 화면과 웹뷰는 웹 콘텐츠 영역에 겹쳐 두고 autoresizing으로 채운다.
        containerView.contentView.addSubview(fallbackView)
        containerView.contentView.addSubview(webView)
        parentView.addSubview(containerView)

        self.webView = webView
        self.containerView = containerView
        self.fallbackView = fallbackView
        self.navigationHandler = navigationHandler
    }

    // Swift 5 언어 모드의 deinit은 메인 액터 밖에서 돌므로 뷰 정리는 메인 액터에서 한다.
    deinit {
        // dispose() 없이 놓았을 때도 웹뷰가 부모 뷰에 남아 페이지가 계속 돌지 않게 한다.
        guard let containerView, let webView else { return }
        let cleanUp: @MainActor @Sendable () -> Void = {
            webView.stopLoading()
            containerView.removeFromSuperview()
        }
        if Thread.isMainThread {
            MainActor.assumeIsolated(cleanUp)
        } else {
            Task { @MainActor in
                cleanUp()
            }
        }
    }

    // MARK: - Loading

    /// http(s) URL만 받는다. 그 밖의 URL은 `didFailWithError`와 폴백 실패 화면으로 알린다.
    public func loadURL(_ url: String) {
        guard let webView else { return }
        guard let requestURL = WebViewPlugin.webURL(url) else {
            fail(with: WebViewPluginError.invalidURL(url))
            return
        }
        webView.load(URLRequest(url: requestURL))
    }

    /// `baseURL`을 주면 상대 경로와 메시지 출처 판정에 쓴다. http(s)만 받는다.
    public func loadHTML(_ html: String, baseURL: String? = nil) {
        guard let webView else { return }
        var base: URL?
        if let baseURL {
            guard let url = WebViewPlugin.webURL(baseURL) else {
                fail(with: WebViewPluginError.invalidURL(baseURL))
                return
            }
            base = url
        }
        webView.loadHTMLString(html, baseURL: base)
    }

    // MARK: - JavaScript

    /// 결과를 JSON 문자열로 넘긴다. 오류, `undefined`, JSON으로 표현할 수 없는 값은 `nil`이다.
    public func evaluateJS(_ js: String, completionHandler: ((String?) -> Void)? = nil) {
        guard let webView else { return }
        webView.evaluateJavaScript(js) { [weak self] result, error in
            guard let self, self.webView != nil else { return }
            completionHandler?(error == nil ? WebViewPlugin.json(result) : nil)
        }
    }

    // MARK: - Navigation

    public var canGoBack: Bool {
        webView?.canGoBack ?? false
    }

    public var canGoForward: Bool {
        webView?.canGoForward ?? false
    }

    public func goBack() {
        webView?.goBack()
    }

    public func goForward() {
        webView?.goForward()
    }

    public func reload() {
        webView?.reload()
    }

    public func stopLoading() {
        webView?.stopLoading()
    }

    // MARK: - Layout

    /// 부모 뷰 기준 여백. `relative`이면 부모 크기에 대한 비율(0...1), 아니면 포인트다.
    public func setMargins(left: CGFloat, top: CGFloat, right: CGFloat, bottom: CGFloat, relative: Bool) {
        guard let containerView, let parentView = containerView.superview else { return }
        let bounds = parentView.bounds
        let insets = relative
            ? UIEdgeInsets(top: bounds.height * top, left: bounds.width * left,
                           bottom: bounds.height * bottom, right: bounds.width * right)
            : UIEdgeInsets(top: top, left: left, bottom: bottom, right: right)
        let frame = bounds.inset(by: insets)
        // CGRect.width/height는 음수 크기를 절댓값으로 돌려주므로 size로 잘라야 여백이 부모보다 클 때 0이 된다.
        containerView.frame = CGRect(x: floor(frame.origin.x), y: floor(frame.origin.y),
                                     width: floor(max(frame.size.width, 0)), height: floor(max(frame.size.height, 0)))
    }

    /// 웹 콘텐츠 영역(safe area를 피해 폴백 화면·웹뷰가 놓인 사각형) 네 모서리의 반지름을 pt로 정한다. 음수는 0으로 보고, 모두 0이면 자르지 않는다.
    /// 상단 띠와 컨테이너는 자르지 않으므로 풀스크린에서도 상태바 아래 웹 모서리가 둥글게 보인다.
    /// 풀스크린의 아래 모서리는 웹이 홈 인디케이터 뒤까지 채우므로 물리 화면 모서리와 겹친다.
    /// Android `setRoundCornerRadius(topLeft, topRight, bottomLeft, bottomRight)`(px)와 같은 원시 타입 시그니처다.
    public func setRoundCornerRadius(topLeft: Float, topRight: Float, bottomLeft: Float, bottomRight: Float) {
        containerView?.cornerRadii = .init(topLeft: CGFloat(max(topLeft, 0)), topRight: CGFloat(max(topRight, 0)),
                                           bottomLeft: CGFloat(max(bottomLeft, 0)), bottomRight: CGFloat(max(bottomRight, 0)))
    }

    /// 스크롤 끝에서 튕기는 효과를 켜거나 끈다. 기본은 켜짐(WKWebView 기본)이다.
    public func setScrollBounceEnabled(_ enabled: Bool) {
        webView?.scrollView.bounces = enabled
    }

    public func setVisibility(_ visible: Bool) {
        containerView?.isHidden = !visible
    }

    /// 컨테이너 위쪽 safe area(상태바·노치 영역)를 (r, g, b, a) 색으로 칠한다. 폴백 화면과 웹뷰는 색과 상관없이 그 아래부터 채운다.
    /// 각 값은 0...1이며 `a`가 0이면(기본값) 칠하지 않고 투명하게 비운다. 아래쪽 safe area(홈 인디케이터)와 좌우는 칠하지 않는다.
    /// Unity C#의 `Color`를 변환 없이 넘길 수 있도록 Android와 같은 원시 타입을 받는다. 상태바 아이콘 색은 호스트의 뷰 컨트롤러가 정한다.
    public func setTopSafeAreaColor(r: Float, g: Float, b: Float, a: Float) {
        containerView?.topSafeAreaColor = a <= 0
            ? nil
            : UIColor(red: CGFloat(r), green: CGFloat(g), blue: CGFloat(b), alpha: CGFloat(a))
    }

    /// 미디어 재생을 멈추거나 다시 시작한다 (upstream SetSuspended).
    public func setSuspended(_ suspended: Bool) {
        webView?.setAllMediaPlaybackSuspended(suspended)
    }

    // MARK: - Fallback

    /// 웹뷰를 숨기고 폴백 실패 화면(닫기 버튼)을 드러낸다.
    /// 호스트가 페이지를 실패로 판단했을 때 쓴다(예: 준비 신호 타임아웃).
    /// 로드 중인 탐색이 끝나도 되돌리지 않으며, 다음 탐색이 시작되면 풀린다.
    public func showFallback() {
        guard webView != nil else { return }
        showFailure()
    }

    // MARK: - Dispose

    /// 여러 번 호출해도 안전하다. 이후에는 어떤 콜백도 보내지 않는다.
    /// 부르지 않고 놓아도 해제될 때 웹뷰를 부모 뷰에서 떼지만, 콜백을 멈추는 시점을 정하려면 직접 부른다.
    public func dispose() {
        guard let webView0 = webView else { return }
        webView = nil
        webView0.stopLoading()
        webView0.navigationDelegate = nil
        webView0.configuration.userContentController.removeScriptMessageHandler(forName: WebViewPlugin.messageHandlerName)
        webView0.configuration.userContentController.removeScriptMessageHandler(forName: WebViewPlugin.consoleHandlerName)
        webView0.configuration.userContentController.removeAllUserScripts()
        containerView?.removeFromSuperview()
        containerView = nil
        fallbackView = nil
        navigationHandler = nil
        delegate = nil
    }

    // MARK: - Internal

    var topSafeAreaView: UIView? {
        containerView?.topSafeAreaView
    }

    var isWebViewVisible: Bool {
        webView.map { !$0.isHidden } ?? false
    }

    // 폴백 화면의 닫기 버튼. 웹뷰를 해제한 뒤 알린다(dispose가 delegate를 비우므로 먼저 잡아 둔다).
    private func close() {
        guard webView != nil else { return }
        let delegate = self.delegate
        dispose()
        delegate?.webViewPluginDidClose(self)
    }

    private func reveal() {
        webView?.isHidden = false
        fallbackView?.state = .loaded
    }

    private func showFailure() {
        currentNavigationFailed = true
        webView?.isHidden = true
        fallbackView?.state = .failed
    }

    private func fail(with error: Error) {
        showFailure()
        delegate?.webViewPlugin(self, didFailWithError: error)
    }

    fileprivate func didReceiveScriptMessage(_ message: WKScriptMessage) {
        if message.name == WebViewPlugin.consoleHandlerName {
            logConsoleMessage(message)
            return
        }
        guard webView != nil, message.name == WebViewPlugin.messageHandlerName else { return }
        // upstream은 프레임과 출처를 확인하지 않는다. iframe이나 허용하지 않은 출처의 메시지는 버린다.
        guard message.frameInfo.isMainFrame, let body = message.body as? String else { return }
        let origin = WebViewPlugin.origin(of: message.frameInfo.securityOrigin)
        if let allowedOrigins, !allowedOrigins.contains(origin) {
            return
        }
        lastMessageOrigin = origin
        delegate?.webViewPlugin(self, didReceiveMessage: body)
    }

    private func logConsoleMessage(_ message: WKScriptMessage) {
        guard webView != nil, WebViewPlugin.forwardWebConsoleToNativeOutput,
              let body = message.body as? [String: Any], let text = body["message"] as? String else { return }
        let type: OSLogType
        switch body["level"] as? String {
        case "error": type = .error
        case "warn": type = .default
        case "debug": type = .debug
        default: type = .info
        }
        WebViewPlugin.consoleLogger.log(level: type, "\(text, privacy: .public)")
    }

    fileprivate func didStartProvisionalNavigation(_ webView: WKWebView) {
        guard self.webView != nil else { return }
        currentNavigationFailed = false
        receivedMainFrameResponse = false
        currentHTTPErrorStatus = nil
        if webView.isHidden {
            fallbackView?.state = .loading
        }
        delegate?.webViewPlugin(self, didStartLoading: webView.url?.absoluteString ?? "")
    }

    // 같은 탐색이므로 실패 판정은 그대로 둔다. 호스트가 문서가 열리기 전에 목적지를 검사할 수 있게 한다.
    fileprivate func didReceiveServerRedirect(_ webView: WKWebView) {
        guard self.webView != nil else { return }
        delegate?.webViewPlugin(self, didStartLoading: webView.url?.absoluteString ?? "")
    }

    fileprivate func didReceiveMainFrameResponse(_ response: URLResponse) {
        guard webView != nil else { return }
        receivedMainFrameResponse = true
        guard let response = response as? HTTPURLResponse, response.statusCode >= 400 else { return }
        currentHTTPErrorStatus = response.statusCode
        showFailure()
        delegate?.webViewPlugin(self, didReceiveHTTPError: response.statusCode, url: response.url?.absoluteString ?? "")
    }

    fileprivate func didFinishNavigation(_ webView: WKWebView) {
        guard self.webView != nil else { return }
        if let item = webView.backForwardList.currentItem {
            if receivedMainFrameResponse {
                // 새로 받은 응답으로 이 방문 기록 항목의 결과를 바꾼다.
                if let status = currentHTTPErrorStatus {
                    httpErrorItems.setObject(NSNumber(value: status), forKey: item)
                } else {
                    httpErrorItems.removeObject(forKey: item)
                }
            } else if let status = httpErrorItems.object(forKey: item)?.intValue {
                // 뒤로·앞으로 캐시에서 복원한 오류 문서다. 응답 정책을 거치지 않으므로 기억해 둔 오류를 다시 알린다.
                showFailure()
                delegate?.webViewPlugin(self, didReceiveHTTPError: status, url: webView.url?.absoluteString ?? "")
            }
        }
        if !currentNavigationFailed {
            reveal()
        }
        delegate?.webViewPlugin(self, didFinishLoading: webView.url?.absoluteString ?? "")
    }

    fileprivate func didFailNavigation(_ error: Error) {
        guard let webView else { return }
        let error = error as NSError
        // 새 탐색이 이전 탐색을 취소했거나(-999), 정책 결정으로 로드가 중단된(102) 경우는 실패로 알리지 않는다.
        let isCancelled = (error.domain == NSURLErrorDomain && error.code == NSURLErrorCancelled)
            || (error.domain == "WebKitErrorDomain" && error.code == 102)
        if isCancelled {
            // 이어지는 탐색 없이 멈췄다면(stopLoading, window.stop) 끝나지 않을 로딩 표시를 실패 화면으로 바꾼다.
            // 새 탐색이 시작될 수 있으므로 실패 판정(currentNavigationFailed)은 켜지 않는다.
            if webView.isHidden, !webView.isLoading {
                fallbackView?.state = .failed
            }
            return
        }
        fail(with: error)
    }

    func webContentProcessDidTerminate() {
        guard webView != nil else { return }
        fail(with: WebViewPluginError.webContentProcessTerminated)
    }
}

// MARK: - WeakScriptMessageHandler

// WKUserContentController가 handler를 강하게 잡아 생기는 순환 참조를 끊는다 (upstream WeakScriptMessageDelegate).
// cf. https://stackoverflow.com/questions/26383031/wkwebview-causes-my-view-controller-to-leak/33365424#33365424
private final class WeakScriptMessageHandler: NSObject, WKScriptMessageHandler {
    weak var delegate: WebViewPlugin?

    init(delegate: WebViewPlugin) {
        self.delegate = delegate
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        delegate?.didReceiveScriptMessage(message)
    }
}

// MARK: - NavigationHandler

// WKNavigationDelegate 메서드가 WebViewPlugin의 공개 API로 드러나지 않도록 따로 둔다.
private final class NavigationHandler: NSObject, WKNavigationDelegate {
    weak var plugin: WebViewPlugin?

    init(plugin: WebViewPlugin) {
        self.plugin = plugin
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        plugin?.didStartProvisionalNavigation(webView)
    }

    func webView(_ webView: WKWebView, didReceiveServerRedirectForProvisionalNavigation navigation: WKNavigation!) {
        plugin?.didReceiveServerRedirect(webView)
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse,
                 decisionHandler: @escaping @MainActor @Sendable (WKNavigationResponsePolicy) -> Void) {
        if navigationResponse.isForMainFrame {
            plugin?.didReceiveMainFrameResponse(navigationResponse.response)
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        plugin?.didFinishNavigation(webView)
    }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        plugin?.didFailNavigation(error)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        plugin?.didFailNavigation(error)
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        plugin?.webContentProcessDidTerminate()
    }
}
