import GoatWebViewSDK
import SwiftUI
import UIKit

/// `WebViewPlugin`을 소유하는 호스트. 생성할 때만 설정을 적용하고 로드하며, 이후 변경은 플러그인을 직접 호출한다.
/// 화면에서 빠지면(Back으로 닫기 등) `dismantleUIView`에서 dispose한다.
struct WebViewHost: UIViewRepresentable {
    let url: String
    let visible: Bool
    let safeArea: SafeAreaPreset
    let scrollBounce: Bool
    let onCreated: (WebViewPlugin) -> Void
    let onStatus: (String) -> Void
    let onClosed: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(self)
    }

    func makeUIView(context: Context) -> PassthroughView {
        let view = PassthroughView()
        let plugin = WebViewPlugin(parentView: view)
        plugin.delegate = context.coordinator
        plugin.applySafeArea(safeArea)
        plugin.setScrollBounceEnabled(scrollBounce)
        plugin.setVisibility(visible)
        plugin.loadURL(url)
        context.coordinator.plugin = plugin
        // 뷰를 갱신하는 중에는 상태를 바꿀 수 없으므로 다음 실행 루프에서 알린다.
        let onCreated = self.onCreated
        DispatchQueue.main.async { onCreated(plugin) }
        return view
    }

    func updateUIView(_ view: PassthroughView, context: Context) {
        context.coordinator.parent = self
        context.coordinator.plugin?.setVisibility(visible)
    }

    static func dismantleUIView(_ view: PassthroughView, coordinator: Coordinator) {
        coordinator.plugin?.dispose()
        coordinator.plugin = nil
    }

    @MainActor
    final class Coordinator: WebViewPluginDelegate {
        var parent: WebViewHost
        var plugin: WebViewPlugin?
        // 메인 문서의 HTTP 상태. 로드를 시작하면 200으로 되돌리고 HTTP 오류를 받으면 바꾼다.
        private var statusCode = 200

        init(_ parent: WebViewHost) {
            self.parent = parent
        }

        func webViewPlugin(_ plugin: WebViewPlugin, didStartLoading url: String) {
            statusCode = 200
            parent.onStatus("Started \(url)")
        }

        func webViewPlugin(_ plugin: WebViewPlugin, didReceiveHTTPError statusCode: Int, url: String) {
            self.statusCode = statusCode
        }

        func webViewPlugin(_ plugin: WebViewPlugin, didFinishLoading url: String) {
            parent.onStatus("Finished \(url) (\(statusCode))")
        }

        func webViewPlugin(_ plugin: WebViewPlugin, didFailWithError error: Error) {
            parent.onStatus("Error \((error as NSError).code): \(error.localizedDescription)")
        }

        // 폴백 화면의 닫기 버튼으로 SDK가 웹뷰를 해제했다.
        func webViewPluginDidClose(_ plugin: WebViewPlugin) {
            self.plugin = nil
            parent.onClosed()
        }
    }
}

/// 자기 자신은 터치를 받지 않는다. 숨긴 웹뷰(Load 후 Show 전)가 아래 설정 화면의 터치를 가로채지 않게 한다.
final class PassthroughView: UIView {
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        let hit = super.hitTest(point, with: event)
        return hit === self ? nil : hit
    }
}
