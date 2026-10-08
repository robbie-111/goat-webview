# goat-webview-android

Unity 설치 없이 Kotlin 웹뷰 코어와 샘플 앱을 빌드·실행하는 Android Studio 프로젝트입니다. 앱 이름은 **Goat WebView**, applicationId는 `com.goatwebview.sample`입니다.

## 실행

Android Studio에서 이 디렉터리를 열고 Gradle JDK를 **17**로 지정한 뒤 `sample-app`을 실행합니다. Android SDK Platform 36과 API 26 이상 기기/에뮬레이터가 필요합니다. Android Studio가 생성하는 `local.properties`는 Git에서 제외합니다.

터미널에서는 JDK 17과 Android SDK 경로를 환경 변수로 지정합니다.

```sh
export JAVA_HOME="/path/to/jdk-17/Contents/Home"
export ANDROID_HOME="/path/to/Android/sdk"
./gradlew :webview-sdk:assembleRelease :sample-app:assembleDebug
./gradlew :sample-app:installDebug
```

샘플은 `webview-sdk` 기능을 직접 시험하는 화면입니다. 설정 화면에서 URL, 상단 safe area 색(`setTopSafeAreaColor` 프리셋), 스크롤 바운스(`setScrollBounceEnabled`), 모서리 반지름(`setRoundCornerRadius` 프리셋)을 정하고 캐시를 비울 수 있습니다. **Load**는 웹뷰를 숨긴 채 만들어 URL을 미리 로드하고, **Show**는 그 웹뷰를 전체 화면으로 표시합니다. Load 전에 Show를 누르면 Load부터 합니다. 웹뷰가 표시된 상태에서 Android 뒤로가기를 누르면 웹 기록이 있을 때는 웹에서 뒤로 가고, 없으면 웹뷰를 destroy하고 설정 화면으로 돌아갑니다. 다음 Load는 새 웹뷰에서 시작합니다.

```text
설정 화면(스크롤)                         Show ▶   전체 화면 WebView
┌──────────────────────────────┐          ┌──────────────────────────┐
│ URL [https://example.com   ] │          │▓▓ 상단 safe area 색 ▓▓▓▓│
│ Top safe area                │          ├──────────────────────────┤
│ (Off)(Black)(White)(Red)     │          │                          │
│ Scroll bounce        [● ON]  │          │                          │
│ Corner (Off)(12)(24)(Mixed)  │          │ WebView                  │
│ [Clear Cache]                │   ◀ 뒤로 │                          │
│ [Load]  [Show]               │(destroy) └──────────────────────────┘
│ Finished https://… (200)     │
└──────────────────────────────┘
```

- 프리셋과 스위치는 웹뷰가 있으면 즉시 적용하고, 웹뷰를 만들 때도 적용합니다. 상단 색 **Off**는 `setTopSafeAreaColor`를 호출하지 않으므로 SDK 기본값(상태바 영역을 투명하게 비우고 그 아래부터 웹뷰)이 됩니다. 이미 색을 적용한 웹뷰에는 다음 Load부터 반영됩니다.
- **Corner radius**는 웹 콘텐츠 영역(safe area를 피해 웹뷰가 놓인 사각형) 네 모서리를 둥글게 자릅니다. Off(0), 12, 24, Mixed(위쪽 두 모서리만 24, 바텀시트 모양) 프리셋이며 값은 dp입니다. SDK는 px를 받으므로 샘플이 density를 곱해 넘깁니다(iOS 샘플은 같은 값을 pt로 넘깁니다). 상단 띠는 자르지 않으므로 Fullscreen에서도 상태바 아래 웹 모서리가 둥글게 보입니다. Fullscreen의 아래 모서리는 웹이 제스처 핸들 뒤까지 채우면 물리 화면 모서리와 겹칩니다.
- **Display**는 웹뷰를 화면 전체(Fullscreen) 또는 설정 화면 위에 좌우 24dp·상하 96dp 마진을 둔 팝업(Popup)으로 엽니다. Fullscreen은 설정 화면을 치우고, 비워 둔 safe area에는 Activity 창 배경(`styles.xml`의 `#F4F6FA`)이 보입니다. Popup은 설정 화면이 그대로 보이고 조작할 수 있어, 상단 색·바운스·캐시를 바꾸면 떠 있는 웹뷰에 바로 반영됩니다. 팝업은 safe area와 겹치지 않으므로 상단 띠 없이 웹뷰가 팝업을 그대로 채웁니다.
- **Clear Cache**는 웹뷰가 있으면 `clearCache(true)`, 없으면 `WebViewCache.clear(context)`를 호출합니다.
- 화면 아래 상태 문구에는 `PageStarted`, `PageFinished`(HTTP 상태 포함), `LoadingErrorReceived`, 캐시 결과를 표시합니다.

## 프로젝트 구성

```text
native/android/
├── settings.gradle.kts               # goat-webview-android
├── gradle/libs.versions.toml          # 고정한 도구·라이브러리 버전
├── webview-sdk/
│   └── src/
│       ├── main/kotlin/com/goatwebview/sdk/
│       │   ├── WebViewController.kt
│       │   ├── WebViewCache.kt
│       │   ├── WebViewConfig.kt
│       │   └── WebViewEvent.kt
│       ├── test/                     # 설정 유효성 검사
│       └── androidTest/              # 코어의 실제 WebView 검증
├── unity-wrapper/                    # webview-sdk를 참조하는 Unity C# 연결부
│   ├── libs/classes.jar              # Unity 6000.3.18f1 il2cpp/Release, compileOnly
│   └── src/
│       ├── main/kotlin/com/goatwebview/unity/
│       │   ├── UnityWebView.kt       # AndroidJavaObject로 생성하는 래퍼
│       │   └── CallbackPayload.kt    # UnitySendMessage로 이벤트 전송
│       └── androidTest/              # 메인 스레드 밖에서 호출하는 기기 테스트
└── sample-app/
    └── src/main/kotlin/com/goatwebview/sample/
        ├── MainActivity.kt           # edge-to-edge, 웹 콘솔 로그 출력
        └── DemoScreen.kt             # 설정 화면, Load/Show, AndroidView 호스트
```

`webview-sdk`는 AndroidX WebKit 기반의 `WebView` 한 개를 소유합니다. 생성과 공개 API는 메인 스레드에서 호출하며 호스트가 `pause`, `resume`, `destroy`를 관리합니다. URL/HTML 로드, JavaScript 실행, 뒤로/앞으로/새로고침, 표시 상태, 출처를 제한한 메시지 수신과 문서 시작 스크립트를 제공합니다. 클래스의 패키지만 `com.goatwebview.sdk`로 변경했으며 이관한 코어의 동작은 유지했습니다.

- `view`는 WebView와 상단 safe area 띠를 담는 `FrameLayout` 컨테이너입니다. `setVisible`·`destroy`는 이 컨테이너에 적용됩니다.
- WebView는 safe area 중 컨테이너와 실제로 겹치는 만큼만 비웁니다. 상단은 상태바·디스플레이 컷아웃, 하단·좌우는 3버튼 내비게이션 바·측면 컷아웃이며, 제스처 핸들 뒤는 채웁니다. 풀스크린이면 이 영역을 피하고, 마진을 준 팝업처럼 safe area와 겹치지 않는 컨테이너는 어느 방향으로도 줄이지 않습니다. 상태바 뒤까지 덮는 옵션은 없습니다.
- `setTopSafeAreaColor(r, g, b, a)`(각 0..1)는 비워 둔 영역의 색만 정합니다. 호출하지 않거나 `a`가 0이면 칠하지 않아 투명합니다. 하단 내비게이션 바와 좌우는 칠하지 않습니다. iOS `WebViewPlugin.setTopSafeAreaColor(r:g:b:a:)`와 같은 원시 타입 시그니처라 Unity C#의 `Color`를 그대로 넘길 수 있습니다. 상태바 아이콘 색은 호스트(Activity)가 정합니다. iOS SDK는 아직 이전 동작(호출하지 않거나 `a`가 0이면 상태바 뒤까지 채움)이며 같은 규칙으로 맞출 예정입니다.
- 풀스크린 WebView는 3버튼 내비게이션 바(가로 모드에서는 측면 바)와 측면 디스플레이 컷아웃만큼 비켜 섭니다. 제스처 내비게이션에서는 핸들 뒤까지 채웁니다(iOS 홈 인디케이터와 같음). `tappableElement` inset을 쓰므로 내비게이션 모드를 바꾸면 바로 반영됩니다. 비워진 바 뒤쪽은 칠하지 않으며, 3버튼 바에는 시스템 대비 스크림이 그려집니다.
- 이벤트(`WebViewEvent`) 이름은 UniWebView 이벤트에서 `On`을 뺀 것입니다. `PageStarted`, `PageFinished(url, statusCode)`, `LoadingErrorReceived`(네트워크·렌더러), `MessageReceived`, `NavigationChanged`, `MessageBridgeUnavailable`이 있습니다. 메인 문서의 HTTP 오류는 별도 이벤트 없이 `PageFinished.statusCode`로 전달합니다.
- `setScrollBounceEnabled(enabled)`는 오버스크롤 효과를 켜고 끕니다(기본 켜짐, Android 기본 동작). `clearCache(includeDiskFiles)`는 앱의 모든 WebView가 공유하는 리소스 캐시를 비웁니다. 웹뷰를 만들기 전에는 `WebViewCache.clear(context)`를 씁니다.
- `setRoundCornerRadius(topLeft, topRight, bottomLeft, bottomRight)`(px)는 웹 콘텐츠 영역(WebView를 담아 safe area를 피해 배치한 내부 뷰) 네 모서리를 둥글게 자릅니다. 상단 safe area 띠와 바깥 컨테이너는 자르지 않으므로 풀스크린에서도 상태바 아래 웹 모서리가 보입니다. 음수는 0, 모두 0이면 자르지 않습니다. 네 값이 같으면 outline 둥근 사각형, 다르면 API 33 이상은 outline path, 그 미만은 `clipPath`(가장자리 안티앨리어싱 없음)로 자릅니다. iOS와 같은 원시 타입 시그니처입니다(iOS는 pt).
- 웹 콘솔 로그는 이벤트로 보내지 않습니다. 토큰 등이 포함될 수 있어 기본으로는 출력하지 않습니다. `WebViewController.setForwardWebConsoleToNativeOutput(true)`(static)이면 모든 웹뷰의 로그를 logcat `GoatWebView` 태그에 레벨별(`Log.e`/`w`/`d`/`i`)로 출력합니다.

`unity-wrapper`는 `webview-sdk`를 참조하며, `webview-sdk`는 Unity를 알지 못합니다. Unity `classes.jar`는 `compileOnly`로만 참조하므로 AAR에 포함되지 않습니다. 기기 테스트는 UnitySendMessage 대신 전송 내용을 받아 Unity 없이 실행합니다.

- C#은 웹뷰 컴포넌트를 붙인 GameObject의 이름으로 `AndroidJavaObject("com.goatwebview.unity.UnityWebView", gameObjectName, allowedMessageOrigins, documentStartScripts)`를 생성합니다. 경계에는 원시 타입·문자열·문자열 배열만 사용합니다.
- 웹뷰는 Unity Activity의 `android.R.id.content`에 숨긴 상태로 붙으며 `setVisible(true)`로 보여 줍니다. `setFrame(x, y, width, height)`는 상단 safe area를 뺀 콘텐츠 영역에 대한 비율(0..1)이며, 원점은 safe area 바로 아래의 좌상단입니다. `(0, 0, 1, 1)`은 상태바 아래부터 화면 끝까지입니다. 호출 시점의 크기로 계산하므로 회전 후에는 다시 호출합니다. 공개 메서드는 어느 스레드에서 호출해도 메인 스레드로 넘겨 호출 순서대로 실행합니다.
- 이벤트는 `UnitySendMessage(gameObjectName, 메서드 이름, json)`으로 GameObject의 메서드를 호출해 보내며 Unity 메인 스레드에서 처리됩니다. 메서드 이름은 UniWebView 이벤트 이름을 따르고, 인자는 이벤트별 필드를 담은 JSON 문자열입니다.

| 메서드 | JSON 필드 |
| --- | --- |
| `OnPageStarted` | `url` |
| `OnPageFinished` | `url`, `statusCode` (메인 문서 HTTP 오류도 이 값으로만 전달) |
| `OnLoadingErrorReceived` | `kind`(`NETWORK`·`RENDERER`·`INVALID_CALL`), `errorCode`, `errorMessage`, `url` |
| `OnMessageReceived` | `message`, `origin` |
| `OnJavaScriptResult` | `requestId`, `result` |
| `OnMessageBridgeUnavailable` | `reason` |

- 탐색 상태는 UniWebView처럼 이벤트 없이 `canGoBack()`, `canGoForward()`, `getUrl()`로 조회합니다. WebView는 UI 스레드에서만 접근할 수 있으므로 래퍼가 코어 `NavigationChanged`의 마지막 값을 보관하고, Unity 스레드에서 바로 반환합니다. `pushState` 같은 문서 내 이동도 반영됩니다.
- UnitySendMessage는 이름으로 GameObject를 찾으므로 이름은 씬에서 고유해야 하고 생성 후 바꾸지 않습니다.
- `setScrollBounceEnabled(bool)`, `clearCache(bool includeDiskFiles)`, `setRoundCornerRadius(tl, tr, bl, br)`(px)는 코어의 같은 메서드를 호출합니다. 웹뷰가 없을 때는 `UnityWebView.clearSharedCache()`(static, 어느 스레드에서나 호출 가능)로 앱 공용 캐시를 비웁니다. 이벤트를 받을 GameObject가 없으므로 실패는 logcat에만 남습니다.
- 웹 콘솔 로그는 이벤트로 보내지 않습니다. `UnityWebView.setForwardWebConsoleToNativeOutput(true)`(static, 기본 false)는 코어의 같은 설정에 위임합니다.
- 코어에서 발생한 잘못된 호출 예외는 앱을 종료하지 않고 `LoadingErrorReceived`(`kind` = `INVALID_CALL`)로 전달합니다. 잘못된 출처 설정은 생성자에서 예외로 알립니다. `destroy` 이후의 호출은 무시합니다.

샘플의 `WebViewHost`는 `AndroidView`로 `WebViewController`를 소유하며, 재구성에서는 새로 생성하거나 로드하지 않습니다.

- 백그라운드 복귀: Activity 생명주기에 맞춰 같은 WebView의 `pause`/`resume`을 호출합니다.
- 화면 회전: 마지막 URL·표시 여부·설정(`rememberSaveable`)으로 WebView를 새로 생성해 복원합니다.
- 화면 해제: `destroy`를 호출합니다.
- 웹 페이지 디버깅을 위해 `MainActivity`에서 웹 콘솔 로그를 logcat `GoatWebView` 태그로 항상 출력합니다.
- `usesCleartextTraffic="false"`이므로 `http://` 주소는 열리지 않습니다.

## 빌드·검증

버전: Gradle 9.1.0, AGP 9.0.1, 내장 Kotlin/Compose compiler 2.2.10, Compose BOM 2026.02.01, minSdk 26, compile/targetSdk 36. AGP의 내장 Kotlin을 사용하므로 `org.jetbrains.kotlin.android` 플러그인을 추가하지 않습니다.

```sh
./gradlew :webview-sdk:assembleRelease :unity-wrapper:assembleRelease :sample-app:assembleDebug
./gradlew :webview-sdk:testDebugUnitTest lint
# 기기 또는 에뮬레이터 연결 후
./gradlew :webview-sdk:connectedDebugAndroidTest :unity-wrapper:connectedDebugAndroidTest
```

산출물:

- `webview-sdk/build/outputs/aar/webview-sdk-release.aar`
- `unity-wrapper/build/outputs/aar/unity-wrapper-release.aar`
- `sample-app/build/outputs/apk/debug/sample-app-debug.apk`
- 각 모듈의 `build/reports/`: 단위 테스트, lint, 기기 테스트 결과

자동 테스트는 `webview-sdk`와 `unity-wrapper`에만 있으며, 테스트 APK의 가짜 응답(`shouldInterceptRequest`)을 사용합니다. 샘플 앱에는 자동 테스트가 없습니다.

2026-09-21 검증 결과:

| 항목 | 결과 |
| --- | --- |
| 코어 Release AAR / 샘플 Debug APK | 빌드 성공 |
| JVM 단위 테스트 | 12개 통과(당시 샘플 테스트 포함) |
| Android 코어 기기 테스트 | 7개 통과 |
| lint | 오류 0개, 샘플 경고 0개. 버전을 고정한 도구·의존성의 업데이트 알림 18개 |

기기 검증 환경은 Pixel 8 에뮬레이터, Android 16/API 36, Android System WebView 140.0.7339.207입니다. API 26 실기기와 Unity 연동은 아직 검증하지 않았습니다.

## 후속 범위

Unity 패키지 배치·AndroidX 의존성 전달, C# API, 파일 업로드·권한 요청·쿠키 관리 등의 확장, iOS 및 Editor 플랫폼 구현은 포함하지 않습니다. 현재 AAR은 AndroidX 의존성을 포함한 단일 fat AAR이 아닙니다. `unity-wrapper` AAR은 `webview-sdk` AAR을 포함하지 않으므로 Unity에는 두 AAR을 함께 배치합니다. AndroidX 의존성 전달 방식은 C# API 작업에서 정합니다.

코드 출처와 원본 고지는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)를 참고하세요.
