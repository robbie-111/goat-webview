# iOS 코드 출처

이 프로젝트는 원본 `unity-webview` 배포본이 아닌 `goat-webview`의 iOS 구현입니다.

- **[gree/unity-webview](https://github.com/gree/unity-webview) (zlib)**: `GoatWebViewSDK/GoatWebViewSDK/`의 SDK는 `plugins/iOS/WebView.mm`의 `CWebViewPlugin`을 Swift로 옮겨 수정한 것입니다. upstream 코드를 옮긴 `WebViewPlugin.swift`와 `WebViewConfig.swift`의 첫머리에 원본 저작권(Keijiro Takahashi, GREE, Inc.)과 zlib 고지를 그대로 두었고, zlib 조건 2에 따라 바로 아래에 `수정됨` 표시와 바뀐 점을 적었습니다. 원본 고지 전문은 [unity-webview-LICENSE.txt](licenses/unity-webview-LICENSE.txt)에 보존합니다.
  - 옮긴 부분: `WKWebView` 구성, 문서 시작 시점의 `window.Unity` 주입과 `unityControl` 핸들러, 순환 참조를 끊는 약한 메시지 핸들러, 여백·표시·탐색·미디어 일시정지, 탐색 오류·HTTP 오류 알림.
  - 바꾼 부분: Unity 엔진 API 호출을 delegate와 호스트가 넘기는 부모 뷰로 대체, 메인 프레임·허용 출처 검사, 네이티브 폴백 화면, 서버 리다이렉트 알림, 스크롤 바운스 설정과 웹 콘솔 네이티브 출력. 뺀 기능은 [README](README.md#이번-범위에서-뺀-upstream-기능)에 정리했습니다.
  - Unity용 Objective-C++ 파일이나 C# 코드를 그대로 가져오지는 않았습니다.
- **[LINE SDK for iOS Swift](https://github.com/line/line-sdk-ios-swift)**: workspace 안에 SDK 프로젝트와 SwiftUI 샘플 프로젝트(`LineSDKSample-SwiftUI`)를 나란히 두고 샘플이 SDK 프레임워크를 링크·포함하는 배치, Swift 작성 스타일만 참고했습니다. 코드는 복사하지 않았습니다. LINE SDK 소스 파일의 고지는 사용 범위를 LY Corporation 서비스와 함께 쓰는 경우로 한정하므로 참고를 구조와 스타일로 제한했습니다.
- **기존 goat-webview 샘플**:
  - 샘플 앱 아이콘 `AppIcon.png`는 기존 goat-webview iOS 샘플의 아이콘과 같은 파일입니다. 그 아이콘은 Android 샘플의 벡터 도형을 기준으로 만들었습니다.
  - 데모 화면(`DemoView`)의 조작과 문구는 [Android 샘플](../android/sample-app/src/main/kotlin/com/goatwebview/sample/DemoScreen.kt)에 맞췄습니다. 코드는 Swift로 새로 작성했습니다.

외부 패키지 의존성은 없습니다. SDK는 UIKit·WebKit, 샘플은 SwiftUI·Foundation 등 Apple 시스템 프레임워크만 사용합니다.

이 문서는 출처를 기록하며 저장소 전체에 새로운 배포 라이선스를 부여하지 않습니다.
