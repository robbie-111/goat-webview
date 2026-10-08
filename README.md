# goat-webview

`goat-webview`는 `unity-webview`를 레퍼런스로 개발 중인 Unity용 웹뷰 오픈소스 프로젝트입니다.

Android와 iOS를 중심으로 기존 구현을 정리하고, Android Studio와 Xcode에서 Unity 없이 같은 네이티브 웹뷰 구현을 개발·검증할 수 있는 환경을 만드는 것이 목표입니다.

> Android의 Kotlin 코어·Compose 샘플과 iOS의 Swift 코어·SwiftUI 샘플을 구현했습니다. 실행 방법은 [Android README](native/android/README.md), [iOS README](native/ios/README.md)를 참고하세요. Windows Editor용 WebView2 플러그인과 macOS Editor용 플러그인은 gree 구현을 가져왔습니다([Windows README](native/windows/README.md), [macOS README](native/macos/README.md)). Unity 패키지(`com.robbie.goatwebview`)와 샘플 프로젝트 초안은 [Unity 패키지 README](unity/package/README.md)를 참고하세요. Unity Editor 재생은 후속 작업입니다.

## 프로젝트 목표

- Android·iOS의 기존 웹뷰 로직에서 Unity 연결부를 분리합니다.
- 각 플랫폼의 네이티브 샘플 앱과 Unity 플러그인이 동일한 웹뷰 코어를 사용하도록 구성합니다.
- 네이티브 코어와 샘플 앱을 Unity 설치 없이 빌드·실행할 수 있게 합니다.
- macOS·Windows는 레퍼런스 구현을 재사용하여 Unity Editor에서 웹뷰를 확인할 수 있게 합니다.
- `goat-webview`에 맞는 새로운 C# API를 설계하고, UPM을 기본 배포 방식으로 사용합니다.

네이티브 샘플 앱은 Unity 플러그인을 개발·검증하기 위한 도구입니다. 별도의 Android·iOS SDK 제품으로 배포하는 것은 초기 목표에 포함하지 않습니다. 기존 `WebViewObject` API와의 직접 호환 및 호환 계층도 제공 대상으로 잡지 않습니다.

## 지원 목표

아래 표는 최종 지원 목표입니다. Android·iOS 네이티브 샘플을 개발·검증하고 있습니다.

| 플랫폼 | 지원 환경 | 개발·검증 방향 |
| --- | --- | --- |
| Android | Unity Android 앱 | Android Studio에서 실행 가능한 네이티브 샘플 앱과 Unity 통합 검증 |
| iOS | Unity iOS 앱 | Xcode에서 실행 가능한 네이티브 샘플 앱과 Unity 통합 검증 |
| macOS | Unity Editor | 레퍼런스의 macOS 구현 재사용 |
| Windows | Unity Editor | 레퍼런스의 Windows 구현 재사용 |

macOS·Windows의 Standalone 앱 지원과 `.unitypackage` 배포는 초기 범위에 포함하지 않습니다. 최소 Unity·OS 버전과 세부 기능 지원 범위는 후속 설계 및 검증에서 정합니다.

## 프로젝트 구조

```text
goat-webview/
├── README.md
├── native/
│   ├── android/       # Android Studio 프로젝트와 네이티브 샘플 앱
│   ├── ios/           # Xcode 프로젝트와 네이티브 샘플 앱
│   ├── macos/         # macOS Editor 플러그인 소스와 빌드 프로젝트
│   └── windows/       # Windows Editor 플러그인 소스와 빌드 프로젝트
├── unity/
│   ├── package/       # 배포할 UPM 패키지
│   │   ├── Runtime/   # C# API와 런타임 연동 코드
│   │   └── Editor/    # Unity Editor 연동 및 빌드 지원 코드
│   └── sample/        # Unity 통합 검증 프로젝트
├── scripts/           # 네이티브 빌드·패키징 스크립트
└── docs/              # 설계·개발·빌드 문서
```

`native/android/`에는 Gradle 프로젝트, `native/ios/`에는 SDK·SwiftUI 샘플 프로젝트를 묶는 Xcode workspace, `native/windows/`에는 Visual Studio 솔루션이 있습니다. 나머지 플랫폼과 Unity의 빈 폴더에는 디렉터리 유지를 위한 `.gitkeep`이 들어 있습니다.

## 구성 원칙

- Android·iOS 샘플 앱은 각각 `native/android/`, `native/ios/`의 네이티브 프로젝트 안에 둡니다. 각 플랫폼에서 샘플 앱과 Unity 연결부가 같은 웹뷰 코어를 참조하도록 구성합니다.
- 네이티브 코어는 플랫폼별로 구현하며, Unity에 필요한 의존성은 연결부에 둡니다.
- `unity/package/`에서 C# 코드와 Unity에 배포할 네이티브 플러그인 파일을 관리합니다. 네이티브 소스와 빌드 프로젝트의 원본은 `native/`에서 관리합니다.
- `unity/sample/`은 `unity/package/`를 로컬 참조하도록 구성합니다. 네이티브 샘플 검증과 별도로 Unity에서의 연동을 검증합니다.

## 현재 작업 범위

Android 프로젝트 이름은 `goat-webview-android`입니다.

- `webview-sdk`: Unity·Compose에 의존하지 않는 Kotlin 웹뷰 코어. `codex-webview-kit`의 코어를 이관했습니다.
- `unity-wrapper`: `webview-sdk`를 참조하는 Unity C# 연결부. `AndroidJavaObject`로 호출하는 원시 타입 API와 메인 스레드 전환을 제공하고, 이벤트는 UniWebView 이름 규칙으로 웹뷰 GameObject에 UnitySendMessage로 보냅니다.
- `sample-app`: URL·상단 safe area 색·스크롤 바운스·캐시를 시험하고 웹뷰를 Load/Show하는 Compose 데모.
- 자동 검증: 코어 단위·기기 테스트와 Unity 래퍼 기기 테스트.

Android API 26 이상, compile/target SDK 36, JDK 17을 기준으로 구성했습니다.

iOS는 `native/ios/GoatWebViewSDK.xcworkspace`를 열고 `GoatWebViewSDKSample-SwiftUI` Scheme으로 실행합니다.

- `GoatWebViewSDK`: upstream `plugins/iOS/WebView.mm`을 Swift로 옮긴 UIKit·WebKit Framework. 파일은 Android `webview-sdk`처럼 역할별(`WebViewPlugin`, `WebViewPluginDelegate`, `WebViewConfig` 등)로 나눴습니다. URL/HTML 로드, JavaScript 평가, 탐색, 출처·메인 프레임을 검사하는 `Unity.call` 브리지, 로드 전·실패 시 네이티브 폴백 화면(닫기 버튼), 상단 safe area 색(`setTopSafeAreaColor`)을 제공합니다. 같은 프로젝트의 TestHost 앱에서 XCTest로 검증합니다.
- `GoatWebViewSDKSample-SwiftUI`: SDK 프레임워크를 링크하는 SwiftUI 샘플. LINE SDK SwiftUI 샘플 구성(`ContentView`, `Player/`, `Support/`, `Utils/`, `Config.xcconfig`)을 따르며 플레이어 입력/인증 화면과 문의 웹뷰를 제공합니다.
- iOS 15 이상, iPhone·iPad, 외부 패키지 의존성 없음.

Unity Editor(macOS·Windows) 재생, 파일 업로드·권한 처리 등 추가 웹뷰 기능은 후속 작업입니다. Unity 샘플(`unity/sample`, Unity 6000.3.18f1)은 `PrefabSample`·`CodeSample` 씬으로 UniWebView의 "Using Prefab"·"Working with Code" 사용법을 보여 줍니다.

`package.json`은 Unity 패키지 정보를 결정한 뒤 작성합니다. 레퍼런스 출처와 원본 고지는 [Android 출처 문서](native/android/THIRD_PARTY_NOTICES.md), [iOS 출처 문서](native/ios/THIRD_PARTY_NOTICES.md)에 정리합니다.

## 레퍼런스

- 기반 프로젝트: [unity-webview](https://github.com/gree/unity-webview)
