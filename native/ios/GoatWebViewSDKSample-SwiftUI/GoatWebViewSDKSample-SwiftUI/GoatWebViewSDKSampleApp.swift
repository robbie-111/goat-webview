import GoatWebViewSDK
import SwiftUI

@main
struct GoatWebViewSDKSampleApp: App {
    init() {
        // 샘플은 웹 페이지 디버깅을 위해 웹 콘솔 로그를 네이티브 로그(subsystem GoatWebView)로 출력한다.
        WebViewPlugin.setForwardWebConsoleToNativeOutput(true)
    }

    var body: some Scene {
        WindowGroup {
            DemoView()
        }
    }
}
