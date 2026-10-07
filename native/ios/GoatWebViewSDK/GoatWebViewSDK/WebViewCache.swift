import WebKit

// MARK: - WebViewCache

/// 앱의 모든 WKWebView가 공유하는 메모리·디스크 리소스 캐시를 비운다. 쿠키와 웹 저장소는 유지한다.
/// Android `WebViewCache.clear`에 대응한다. iOS 캐시는 데이터 저장소 단위라 웹뷰 인스턴스별 메서드는 두지 않는다.
@MainActor
public enum WebViewCache {
    public static func clear(completionHandler: (() -> Void)? = nil) {
        let types: Set<String> = [WKWebsiteDataTypeDiskCache, WKWebsiteDataTypeMemoryCache]
        WKWebsiteDataStore.default().removeData(ofTypes: types, modifiedSince: .distantPast) {
            completionHandler?()
        }
    }
}
