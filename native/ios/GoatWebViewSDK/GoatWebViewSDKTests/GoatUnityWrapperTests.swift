import XCTest
@testable import GoatWebViewSDK

/// Unity C#이 부를 C 진입점. 래퍼는 키 윈도의 루트 뷰에 웹뷰를 붙이므로 테스트 윈도를 키 윈도로 만든다.
/// Unity가 없으므로 GoatUnityWrapper.sendMessage를 바꿔 끼워 C#에 갈 메시지를 기록한다.
@MainActor
final class GoatUnityWrapperTests: XCTestCase {
    private struct Message {
        let gameObjectName: String
        let method: String
        let fields: NSDictionary
    }

    private static let baseURL = "https://example.com/"

    private var messages: [Message] = []
    private var window: UIWindow?
    private var originalSendMessage: (@MainActor (String, String, String) -> Void)?

    override func setUp() async throws {
        let window = UIWindow(windowScene: try await windowScene())
        window.rootViewController = UIViewController()
        window.makeKeyAndVisible()
        self.window = window
        messages = []
        originalSendMessage = GoatUnityWrapper.sendMessage
        GoatUnityWrapper.sendMessage = { [weak self] name, method, json in
            guard let fields = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? NSDictionary else {
                XCTFail("payload가 JSON 객체가 아니다: \(json)")
                return
            }
            self?.messages.append(Message(gameObjectName: name, method: method, fields: fields))
        }
    }

    override func tearDown() async throws {
        for gameObjectName in GoatUnityWrapper.wrappers.keys {
            goatwebview_dispose(gameObjectName)
        }
        if let originalSendMessage {
            GoatUnityWrapper.sendMessage = originalSendMessage
        }
        window?.isHidden = true
        window = nil
    }

    private func messages(_ method: String, _ gameObjectName: String) -> [Message] {
        messages.filter { $0.method == method && $0.gameObjectName == gameObjectName }
    }

    private func fields(_ method: String, _ gameObjectName: String) -> [NSDictionary] {
        messages(method, gameObjectName).map(\.fields)
    }

    func testCreateAttachesToKeyWindowAndSendsMessagesToGameObject() async throws {
        XCTAssertTrue(goatwebview_create("a", nil))
        let plugin = try XCTUnwrap(GoatUnityWrapper.wrappers["a"]?.plugin)
        XCTAssertTrue(plugin.containerView?.superview === window?.rootViewController?.view)

        goatwebview_set_visibility("a", true)
        goatwebview_load_html("a", "<script>Unity.call('hello');</script>", Self.baseURL)
        try await waitUntil { !self.messages("OnPageFinished", "a").isEmpty }
        try await waitUntil { !self.messages("OnMessageReceived", "a").isEmpty }
        XCTAssertEqual(fields("OnMessageReceived", "a"), [["message": "hello", "origin": "https://example.com"]])
        XCTAssertEqual(fields("OnPageStarted", "a"), [["url": Self.baseURL]])
        XCTAssertEqual(fields("OnPageFinished", "a"), [["url": Self.baseURL, "statusCode": 200]])
    }

    func testAllowedOriginsAreSeparatedByNewline() async throws {
        XCTAssertTrue(goatwebview_create("a", "https://a.example\nhttps://example.com"))
        XCTAssertTrue(goatwebview_create("b", "https://other.example"))
        for gameObjectName in ["a", "b"] {
            goatwebview_load_html(gameObjectName, "<script>window.Unity && Unity.call('hello');</script>", Self.baseURL)
        }
        try await waitUntil { !self.messages("OnMessageReceived", "a").isEmpty && !self.messages("OnPageFinished", "b").isEmpty }
        try await pause(0.3)
        XCTAssertEqual(fields("OnMessageReceived", "a").map { $0["message"] as? String }, ["hello"])
        XCTAssertTrue(messages("OnMessageReceived", "b").isEmpty, "허용하지 않은 출처의 메시지는 보내지 않는다")
    }

    func testEvaluateJSSendsResultWithRequestId() async throws {
        XCTAssertTrue(goatwebview_create("a", nil))
        goatwebview_load_html("a", "<p>eval</p>", Self.baseURL)
        try await waitUntil { !self.messages("OnPageFinished", "a").isEmpty }

        goatwebview_evaluate_js("a", 1, "1 + 1")
        goatwebview_evaluate_js("a", 2, "undefined")
        try await waitUntil { self.messages("OnJavaScriptResult", "a").count == 2 }
        let results = fields("OnJavaScriptResult", "a").sorted { ($0["requestId"] as? Int ?? 0) < ($1["requestId"] as? Int ?? 0) }
        XCTAssertEqual(results, [["requestId": 1, "result": "2"], ["requestId": 2, "result": NSNull()]], "결과가 없으면 null이다")
    }

    func testInvalidURLSendsInvalidCall() throws {
        XCTAssertTrue(goatwebview_create("a", nil))
        goatwebview_load_url("a", "ftp://example.com/file")
        XCTAssertEqual(fields("OnLoadingErrorReceived", "a"), [[
            "kind": "INVALID_CALL", "errorCode": -1,
            "errorMessage": "HTTP(S) URL만 사용할 수 있습니다: ftp://example.com/file", "url": NSNull(),
        ]])
    }

    func testFallbackCloseButtonDestroysWebViewWithoutMessage() throws {
        XCTAssertTrue(goatwebview_create("a", nil))
        let plugin = try XCTUnwrap(GoatUnityWrapper.wrappers["a"]?.plugin)
        let container = try XCTUnwrap(plugin.containerView)

        plugin.fallbackView?.closeButton.sendActions(for: .touchUpInside)
        XCTAssertNil(container.superview, "백판과 웹뷰를 함께 해제한다")
        XCTAssertNil(GoatUnityWrapper.wrappers["a"])
        XCTAssertTrue(messages.isEmpty, "Unity에는 보내지 않는다")
        XCTAssertTrue(goatwebview_create("a", nil), "같은 이름으로 다시 만들 수 있다")
    }

    func testInvalidIdentifiersAndNullArgumentsAreIgnored() throws {
        XCTAssertTrue(goatwebview_create("a", nil))
        XCTAssertFalse(goatwebview_create("a", nil), "이미 쓰는 GameObject 이름")
        XCTAssertFalse(goatwebview_create(nil, nil))

        // 없는 GameObject 이름과 NULL 인자는 아무 일도 하지 않는다.
        goatwebview_load_url("missing", "https://example.com/")
        goatwebview_load_url("a", nil)
        goatwebview_load_html("a", nil, nil)
        goatwebview_evaluate_js("a", 1, nil)
        goatwebview_go_back(nil)
        goatwebview_set_frame("missing", 0, 0, 1, 1)
        XCTAssertFalse(goatwebview_can_go_back("missing"))
        XCTAssertFalse(goatwebview_can_go_forward(nil))
        goatwebview_dispose("missing")
        goatwebview_dispose(nil)
        XCTAssertTrue(messages.isEmpty)
    }

    func testDisposeRemovesWebViewAndStopsMessages() async throws {
        XCTAssertTrue(goatwebview_create("a", nil))
        let container = try XCTUnwrap(GoatUnityWrapper.wrappers["a"]?.plugin.containerView)
        goatwebview_load_html("a", "<script>setTimeout(function() { Unity.call('late'); }, 200);</script>", Self.baseURL)
        goatwebview_evaluate_js("a", 1, "1")

        goatwebview_dispose("a")
        goatwebview_dispose("a")
        XCTAssertNil(GoatUnityWrapper.wrappers["a"])
        XCTAssertNil(container.superview)
        try await pause(0.6)
        XCTAssertTrue(messages.isEmpty)
        XCTAssertTrue(goatwebview_create("a", nil), "해제한 GameObject 이름은 다시 쓸 수 있다")
    }

    func testSetFrameUsesRatioBelowTopSafeArea() throws {
        XCTAssertTrue(goatwebview_create("a", nil))
        let wrapper = try XCTUnwrap(GoatUnityWrapper.wrappers["a"])
        let container = try XCTUnwrap(wrapper.plugin.containerView)
        let bounds = wrapper.parentView.bounds
        let safeTop = min(wrapper.parentView.safeAreaInsets.top, bounds.height)
        let available = bounds.height - safeTop

        goatwebview_set_frame("a", 0.1, 0.2, 0.8, 0.5)
        XCTAssertEqual(container.frame, CGRect(x: floor(bounds.width * 0.1), y: floor(safeTop + available * 0.2),
                                               width: floor(bounds.width * 0.8), height: floor(available * 0.5)))

        // 음수 크기는 0이다.
        goatwebview_set_frame("a", 0, 0, -1, -1)
        XCTAssertEqual(container.frame.size, .zero)
    }

    // MARK: - 도우미

    private struct TimeoutError: Error {}

    // TestHost의 장면이 연결될 때까지 기다린다.
    private func windowScene() async throws -> UIWindowScene {
        let deadline = Date().addingTimeInterval(10)
        while Date() < deadline {
            if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
                return scene
            }
            try await Task.sleep(nanoseconds: 50_000_000)
        }
        throw TimeoutError()
    }

    /// 조건이 참이 될 때까지 기다린다. 기다리는 동안 메인 런루프가 돌아 WebKit 콜백이 들어온다.
    private func waitUntil(timeout: TimeInterval = 15,
                           file: StaticString = #filePath,
                           line: UInt = #line,
                           _ condition: () -> Bool) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            guard Date() < deadline else {
                XCTFail("\(timeout)초 안에 조건을 만족하지 못했다", file: file, line: line)
                throw TimeoutError()
            }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    /// 일어나지 않아야 하는 일을 확인할 때, 들어올 콜백이 있다면 들어오도록 잠시 기다린다.
    private func pause(_ seconds: TimeInterval) async throws {
        try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
    }
}
