# Goat WebView — iOS

Unity 플러그인에 넣을 iOS 웹뷰 코어(`GoatWebViewSDK`)를 Unity 없이 개발·검증하는 Xcode workspace입니다. 코어는 [gree/unity-webview](https://github.com/gree/unity-webview)의 `plugins/iOS/WebView.mm`(`CWebViewPlugin`)을 Swift로 옮긴 것으로, 파일은 Android `webview-sdk`처럼 역할별로 나눴고, SwiftUI 샘플 앱은 Android 샘플과 같은 데모 화면(Load/Show, 상단 safe area 색, 스크롤 바운스, Fullscreen/Popup, 캐시 비우기)으로 SDK 기능을 시험합니다.

## 실행

`GoatWebViewSDK.xcworkspace`를 열고 **GoatWebViewSDKSample-SwiftUI** 스킴과 iPhone/iPad 시뮬레이터를 선택해 실행합니다. 외부 패키지와 프로젝트 생성 도구는 필요 없습니다.

| 스킴 | 용도 |
| --- | --- |
| `GoatWebViewSDK` | SDK 프레임워크 빌드와 SDK 테스트(`GoatWebViewSDKTests`) |
| `GoatWebViewSDKSample-SwiftUI` | 샘플 앱 실행. 테스트 타깃은 없습니다 |

- Deployment Target은 SDK·샘플 모두 iOS 15.0이고, 테스트 타깃은 Xcode 27의 XCTest에 맞춰 iOS 17.0입니다. Swift 5 언어 모드, 개발 도구는 Xcode 27.0입니다.
- 샘플 앱: 표시 이름 `GoatWebViewSDK Sample`, Bundle ID `dev.goatwebview.goatwebviewsdk.sample`, iPhone·iPad.
- 실기기에서는 Xcode의 Signing & Capabilities에서 본인 팀을 지정합니다.
- 샘플은 웹 콘솔 로그를 네이티브 로그로 출력합니다. Xcode 콘솔 또는 Console.app에서 subsystem `GoatWebView`로 볼 수 있습니다.

## 폴더 구조

```text
native/ios/
├── GoatWebViewSDK.xcworkspace/            # 아래 두 프로젝트를 묶는다
├── GoatWebViewSDK/
│   ├── GoatWebViewSDK.xcodeproj/          # GoatWebViewSDK·TestHost·GoatWebViewSDKTests 타깃
│   ├── GoatWebViewSDK/
│   │   ├── WebViewPlugin.swift            # 본체 + private WebKit 핸들러 (≙ Android WebViewController.kt)
│   │   ├── WebViewPluginDelegate.swift    # delegate·오류 (≙ WebViewEvent.kt)
│   │   ├── WebViewConfig.swift            # 출처·URL 검사, 주입 스크립트 (≙ WebViewConfig.kt)
│   │   ├── ContainerView.swift            # safe area 비우기와 상단 띠
│   │   ├── WebViewCache.swift             # 앱 공용 캐시 비우기 (≙ WebViewCache.kt)
│   │   ├── FallbackView.swift             # 폴백 화면
│   │   └── UnityWrapper/GoatUnityWrapper.swift  # Unity C#용 @_cdecl 진입점
│   ├── TestHost/                          # 테스트를 띄우는 빈 SwiftUI 앱(윈도·WebKit 프로세스 제공)
│   └── GoatWebViewSDKTests/               # GoatUnityWrapperTests
├── GoatWebViewSDKSample-SwiftUI/
│   ├── GoatWebViewSDKSample-SwiftUI.xcodeproj/
│   └── GoatWebViewSDKSample-SwiftUI/
│       ├── GoatWebViewSDKSampleApp.swift  # @main, 웹 콘솔 출력을 켜고 DemoView를 연다
│       ├── DemoView.swift                 # 설정 화면, Load/Show/Back (≙ Android DemoScreen.kt)
│       ├── WebViewHost.swift              # WebViewPlugin을 소유하는 UIViewRepresentable
│       └── Assets.xcassets
├── licenses/unity-webview-LICENSE.txt
├── README.md
└── THIRD_PARTY_NOTICES.md
```

- 두 프로젝트는 폴더 동기화(objectVersion 77) 방식이라, 위 소스 폴더에 파일을 추가하면 `project.pbxproj`를 고치지 않아도 해당 타깃에 들어갑니다.
- 샘플은 `GoatWebViewSDK.framework`를 빌드 산출물(`BUILT_PRODUCTS_DIR`)에서 링크하고 앱에 포함(Embed)합니다. workspace의 암시적 의존성으로 SDK가 먼저 빌드되므로 샘플은 workspace에서 실행합니다.
- SDK는 UIKit·WebKit만 사용합니다.

## GoatWebViewSDK API

```swift
import GoatWebViewSDK

let plugin = WebViewPlugin(parentView: hostView, allowedOrigins: ["https://example.com"])
plugin.delegate = self                 // WebViewPluginDelegate
plugin.setVisibility(true)             // 처음에는 숨겨져 있다
plugin.setScrollBounceEnabled(false)
plugin.loadURL("https://example.com/page")
plugin.evaluateJS("document.title") { json in
    // "\"제목\"" 처럼 JSON 문자열, 실패하면 nil
}
// 다 쓰면
plugin.dispose()
```

`WebViewPlugin`은 `@MainActor` 클래스이며 모든 API는 메인 스레드에서 호출합니다.

| API | 동작 |
| --- | --- |
| `init(parentView:allowedOrigins:)` | `parentView` 전체 크기로 컨테이너를 붙이고, 그 안에 폴백 화면과 `WKWebView`를 겹쳐 둡니다. 컨테이너는 숨긴 상태로 시작합니다. `allowedOrigins`는 [JS 브리지](#js-브리지) 참고 |
| `delegate` | `weak`. 이벤트를 받는 `WebViewPluginDelegate` |
| `setAllowedOrigins(_:)` | 허용 출처를 바꿉니다. 지금 페이지에는 적용되지 않고 다음 탐색부터 적용됩니다. 값의 의미는 `init`의 `allowedOrigins`와 같습니다 |
| `loadURL(_:)` | http(s)만 받습니다. 경로·쿼리·프래그먼트의 공백과 비ASCII 문자만 퍼센트 인코딩하고 `#`·기존 `%xx`는 그대로 둡니다. 그 밖의 URL은 `didFailWithError(.invalidURL)`와 폴백 실패 화면 |
| `loadHTML(_:baseURL:)` | `baseURL`(http(s)만)은 상대 경로와 메시지 출처 판정에 씁니다 |
| `evaluateJS(_:completionHandler:)` | 결과를 JSON 문자열로 넘깁니다(문자열이면 따옴표 포함). 오류·`undefined`·함수·`NaN`·`Infinity`·`Date`는 `nil`. `dispose()` 뒤에는 완료 처리기를 부르지 않습니다 |
| `canGoBack`, `canGoForward` | 방문 기록 상태 |
| `goBack()`, `goForward()`, `reload()`, `stopLoading()` | 탐색 |
| `setVisibility(_:)` | 컨테이너(폴백 화면 + 웹뷰)를 보이거나 숨깁니다 |
| `setFallbackTheme(_:)` | 폴백 화면 테마 `.light`(기본)·`.dark`·`.system`. [폴백 화면](#폴백-화면) 참고 |
| `setTopSafeAreaColor(r:g:b:a:)` | 컨테이너 위쪽 safe area(상태바·노치 영역)를 (r, g, b, a) 색(각 0...1)으로 칠합니다. 폴백 화면과 웹뷰는 색과 상관없이 위쪽 safe area 중 컨테이너와 겹치는 부분을 비우고 그 아래부터 채우며, 좌우 safe area(가로 모드 노치)도 피합니다. 아래쪽 safe area(홈 인디케이터) 뒤는 채웁니다. safe area와 겹치지 않는 팝업형 컨테이너는 줄지 않습니다(Android와 같음). `a`가 0이면(기본값) 칠하지 않고 투명하게 비웁니다. Android와 같은 원시 타입 시그니처라 Unity C#의 `Color`를 그대로 넘길 수 있습니다. 상태바 아이콘 색은 호스트의 뷰 컨트롤러가 정합니다 |
| `setScrollBounceEnabled(_:)` | 스크롤 끝에서 튕기는 효과(`scrollView.bounces`). 기본은 켜짐 |
| `setRoundCornerRadius(topLeft:topRight:bottomLeft:bottomRight:)` | 웹 콘텐츠 영역(safe area를 피해 폴백 화면·웹뷰를 담은 `contentView`) 네 모서리 반지름(pt). 상단 띠와 컨테이너는 자르지 않으므로 풀스크린에서도 상태바 아래 웹 모서리가 보입니다. 음수는 0, 모두 0이면 자르지 않음. 같으면 `layer.cornerRadius`, 다르면 `CAShapeLayer` 마스크. Android와 같은 원시 타입 시그니처(Android는 px) |
| `setSuspended(_:)` | 페이지의 미디어 재생을 멈추거나 다시 시작합니다. JavaScript 실행은 멈추지 않습니다 |
| `dispose()` | 로드를 멈추고 뷰를 떼며 핸들러를 해제합니다. 여러 번 불러도 안전하고 이후 콜백은 없습니다. 부르지 않고 놓아도 해제될 때 로드를 멈추고 뷰를 뗍니다 |
| `WebViewPlugin.setForwardWebConsoleToNativeOutput(_:)` (static) | 모든 웹뷰의 웹 콘솔 로그(`console.log/info/warn/error/debug`)를 `os.Logger`(subsystem `GoatWebView`, category `console`)에 레벨별로 출력할지 정합니다. 기본 false이며 이미 만든 웹뷰에도 바로 적용됩니다. 웹 콘솔에는 토큰 등이 포함될 수 있습니다 |
| `WebViewCache.clear(completionHandler:)` (static) | 앱의 모든 웹뷰가 공유하는 디스크·메모리 캐시를 비웁니다. 쿠키와 웹 저장소는 유지합니다 |

`WebViewPluginDelegate`의 메서드는 모두 선택 구현이며 메인 스레드에서 호출됩니다.

| 콜백 | 시점 |
| --- | --- |
| `webViewPlugin(_:didReceiveMessage:)` | 허용된 메인 프레임의 `Unity.call(message)` |
| `webViewPlugin(_:didStartLoading:)` | 메인 프레임 탐색 시작. 서버 리다이렉트로 목적지가 바뀌면 새 URL로 다시 불립니다 |
| `webViewPlugin(_:didFinishLoading:)` | 메인 프레임 로드 완료 |
| `webViewPlugin(_:didFailWithError:)` | 로드 실패(`NSError`), `WebViewPluginError.invalidURL(String)`, `WebViewPluginError.webContentProcessTerminated`. 새 탐색이나 `stopLoading()`에 의한 취소(-999, WebKit 102)는 알리지 않습니다 |
| `webViewPlugin(_:didReceiveHTTPError:url:)` | 메인 문서의 HTTP 400 이상 응답. 뒤로·앞으로 캐시에서 그 오류 문서를 다시 보일 때도 불립니다. 하위 리소스의 404는 해당하지 않습니다 |
| `webViewPluginDidClose(_:)` | 폴백 화면의 닫기 버튼으로 SDK가 웹뷰를 해제한 뒤. 이후 다른 콜백은 없습니다 |

DEBUG 빌드에서는 iOS 16.4 이상에서 Safari 웹 검사기로 볼 수 있게(`isInspectable`) 합니다. `WKUIDelegate`와 탐색 정책은 두지 않으므로 `target="_blank"`·`window.open`, `alert`·`confirm`·`prompt`, 웹이 아닌 스킴(`mailto:`, `tel:`, App Store 링크)은 WebKit 기본 동작대로 아무 일도 일어나지 않습니다([뺀 기능](#이번-범위에서-뺀-upstream-기능) 참고).

### upstream `WebView.mm` 대응

| upstream C 함수 (`CWebViewPlugin`) | GoatWebViewSDK |
| --- | --- |
| `_CWebViewPlugin_Init(gameObjectName, transparent, zoom, ua, …)` + `UnityGetGLViewController().view` | `WebViewPlugin(parentView:allowedOrigins:)`. 부모 뷰는 호스트가 넘기고, 초기화 옵션은 없습니다 |
| `_CWebViewPlugin_Destroy` | `dispose()` |
| `_CWebViewPlugin_SetMargins` | 없음. 컨테이너는 부모 뷰를 채우며, Unity에서는 `goatwebview_set_frame`이 위치·크기를 정합니다 |
| `_CWebViewPlugin_SetVisibility` | `setVisibility(_:)` |
| `_CWebViewPlugin_LoadURL`, `_LoadHTML` | `loadURL(_:)`, `loadHTML(_:baseURL:)` |
| `_CWebViewPlugin_EvaluateJS` | `evaluateJS(_:completionHandler:)`. upstream은 결과를 돌려주지 않습니다 |
| `_CWebViewPlugin_CanGoBack`, `CanGoForward`, `GoBack`, `GoForward`, `Reload` | `canGoBack`, `canGoForward`, `goBack()`, `goForward()`, `reload()` |
| `_CWebViewPlugin_SetSuspended` | `setSuspended(_:)` |
| `_CWebViewPlugin_SetScrollBounceEnabled`, `_CWebViewPlugin_ClearCache` | `setScrollBounceEnabled(_:)`, `WebViewCache.clear(completionHandler:)`(캐시만 비움) |
| 없음 | `stopLoading()`, `setTopSafeAreaColor(r:g:b:a:)`, `setForwardWebConsoleToNativeOutput(_:)` |

| upstream `UnitySendMessage` 메서드 | `WebViewPluginDelegate` |
| --- | --- |
| `CallFromJS` (`unityControl` 메시지, `unity:` 스킴) | `didReceiveMessage`. `unityControl`만 받고, 메인 프레임·허용 출처 검사를 더했습니다 |
| `CallOnStarted` | `didStartLoading`. upstream은 탐색 정책 결정 시점, 여기서는 탐색 시작과 서버 리다이렉트 시점입니다 |
| `CallOnLoaded` | `didFinishLoading`. upstream은 `loading` KVO입니다 |
| `CallOnError` (오류 설명 문자열) | `didFailWithError` (`Error` 값) |
| `CallOnHttpError` (상태 코드 문자열) | `didReceiveHTTPError(statusCode:url:)`. 메인 문서만 알립니다(Unity 래퍼는 `OnPageFinished`의 `statusCode`로 보냅니다) |
| `CallOnHooked`, `CallOnCookies` | 없음 |
| 없음 | `webViewPluginDidClose` (폴백 닫기 버튼으로 해제) |

### Android `webview-sdk` 대응

| Android (`com.goatwebview.sdk`) | iOS |
| --- | --- |
| `WebViewController(context, WebViewConfig(allowedMessageOrigins), listener)` | `WebViewPlugin(parentView:allowedOrigins:)` + `delegate` |
| `view`를 호스트가 배치 | SDK가 `parentView`에 붙여 부모 뷰를 채움(배치는 부모 뷰로 호스트가 정함) |
| `loadUrl`, `loadHtml` (잘못된 URL은 예외) | `loadURL`, `loadHTML` (잘못된 URL은 `didFailWithError`) |
| `evaluateJavaScript(script, onResult)` | `evaluateJS(_:completionHandler:)` |
| `goBack`, `goForward`, `reload`, `stopLoading` | 같음 |
| `setVisible` | `setVisibility` |
| `setScrollBounceEnabled` | `setScrollBounceEnabled` |
| `clearCache(includeDiskFiles)`, `WebViewCache.clear(context)` | `WebViewCache.clear(completionHandler:)`. iOS 캐시는 데이터 저장소 단위라 인스턴스 메서드는 없습니다 |
| `WebViewController.setForwardWebConsoleToNativeOutput` (logcat `GoatWebView`) | `WebViewPlugin.setForwardWebConsoleToNativeOutput` (`os.Logger` subsystem `GoatWebView`). WKWebView에는 콘솔 콜백이 없어 `console.*`를 감싸는 스크립트로 받습니다 |
| `pause`, `resume` | `setSuspended(true/false)`. 미디어 재생만 제어합니다 |
| `destroy` | `dispose` |
| `PageStarted`, `PageFinished(url, statusCode)` | `didStartLoading`, `didFinishLoading` |
| `NavigationChanged(url, canGoBack, canGoForward)` | 이벤트 없음. `canGoBack`·`canGoForward` 속성과 리다이렉트 때도 불리는 `didStartLoading` |
| `LoadingErrorReceived(NETWORK)`, `PageFinished.statusCode` | `didFailWithError`, `didReceiveHTTPError` |
| `LoadingErrorReceived(RENDERER)` (인스턴스를 해제한 뒤 전달) | `didFailWithError(.webContentProcessTerminated)`. 인스턴스를 유지하고 실패 화면을 보이며 `reload()`로 복구할 수 있습니다 |
| `MessageReceived(message, origin)` | `didReceiveMessage(message)`. 출처는 SDK가 검사하므로 넘기지 않습니다 |
| 허용 출처가 비면 브리지 없음 | `allowedOrigins: []`이면 브리지 없음, `nil`이면 출처 제한 없음(upstream 호환) |
| `MessageBridgeUnavailable`, `requestInterceptor`, `documentStartScripts`, `currentUrl`, `isDestroyed` | 없음 |
| `setTopSafeAreaColor(r, g, b, a)`, 상단 safe area 항상 비움, 3버튼 바·측면 컷아웃 회피 | `setTopSafeAreaColor(r:g:b:a:)` (같은 원시 타입 시그니처), 상단 safe area 항상 비움, 좌우 safe area 회피. 홈 인디케이터 뒤는 채움(Android 제스처 핸들과 같음) |
| 폴백 화면, `Closed` 이벤트 | 폴백 화면, `webViewPluginDidClose` (같은 동작. Android는 뒤로·앞으로 캐시의 HTTP 오류 문서를 다시 실패로 보지 않음) |

## 폴백 화면

컨테이너 안에서 웹뷰 아래에 깔리는 네이티브 화면입니다. 배경은 `systemBackground`, 가운데에 로딩 표시 또는 경고 아이콘(`exclamationmark.triangle`)이 있고, 오른쪽 위 safe area 안에 44pt 닫기 버튼(`xmark`, 접근성 라벨 `Close`)이 항상 보입니다. 문구는 없습니다.

테마는 `setFallbackTheme(_:)`로 `.light`(기본), `.dark`, `.system` 중에서 고릅니다. 색은 시스템 색 그대로이고 `overrideUserInterfaceStyle`로 라이트·다크를 고정하며, `.system`은 시스템 다크 모드를 실행 중에도 따릅니다.

| 상태 | 언제 | 웹뷰 |
| --- | --- | --- |
| 로딩 | 웹뷰가 숨겨진 상태에서 탐색이 시작될 때(처음 로드 포함) | 숨김 |
| 로드됨 | 메인 문서 로드가 실패 없이 끝났을 때 | 보임 |
| 실패 | 메인 문서 HTTP 400 이상, 로드 실패, 잘못된 URL, 웹 콘텐츠 프로세스 종료, 이어지는 탐색 없이 멈춘 로드(`stopLoading`, `window.stop`) | 숨김 |

- 웹뷰가 한 번 보인 뒤의 탐색 중에는 이전 페이지를 계속 보여 주고, 실패하면 다시 숨깁니다.
- 실패 판정은 다음 탐색이 시작될 때까지 유지되어, 그 사이 로드가 끝나도 웹뷰를 드러내지 않습니다. 다음 탐색이 성공하면 웹뷰가 다시 드러납니다.
- 이어지는 탐색 없이 멈춘 로드는 오류 콜백 없이 실패 화면(닫기 버튼)만 보입니다.
- 닫기 버튼을 누르면 SDK가 웹뷰와 폴백 화면을 `dispose()`로 해제한 뒤 `webViewPluginDidClose`를 보냅니다. 로드에 실패해 페이지가 닫기를 요청할 수 없을 때 사용자가 직접 닫는 용도입니다.
- 폴백 화면도 웹뷰와 함께 상단 safe area 아래에 놓이므로 닫기 버튼은 상태바(또는 상단 띠) 바로 아래에 보입니다.

## JS 브리지

- 페이지 → 네이티브: 문서 시작 시점에 메인 프레임에만 `window.Unity = { call(msg) }`를 설치합니다. `call`은 값을 `String(msg)`로 바꿔 `window.webkit.messageHandlers.unityControl.postMessage`로 보냅니다(upstream의 `Unity.saveDataURL`은 없습니다).
- 스크립트는 `location.origin`이 허용 출처일 때만 `window.Unity`를 만듭니다. 네이티브도 받은 메시지마다 `frameInfo.isMainFrame`과 `securityOrigin`을 다시 검사하므로, iframe이나 허용하지 않은 출처가 `unityControl.postMessage`를 직접 불러도 버립니다. 문자열이 아닌 원시 메시지도 버립니다.
- `allowedOrigins`: `nil`이면 출처를 제한하지 않고(메인 프레임 검사는 유지), 빈 배열이면 브리지를 설치하지 않습니다. 각 항목은 `https://example.com[:port]` 형식이며 scheme·host 소문자화, 기본 포트 제거, 국제화 도메인의 퓨니코드 변환으로 정규화합니다. 경로(`/` 제외)·쿼리·프래그먼트·사용자 정보가 있거나 http(s)가 아닌 항목은 무시합니다.
- `loadHTML(_:baseURL:)`로 넣은 문서의 출처는 `baseURL`의 출처입니다. `baseURL`이 없으면 출처가 없으므로 허용 출처를 지정한 경우 브리지가 설치되지 않습니다.
- 네이티브 → 페이지: `evaluateJS`를 씁니다.
- 웹 콘솔: 모든 프레임에 `console.*`를 감싸는 스크립트를 문서 시작 시점에 설치합니다. 원래 콘솔 동작은 유지하고 `goatConsole` 핸들러로 `{level, message}`를 보내며, 출력 여부는 네이티브가 `setForwardWebConsoleToNativeOutput` 설정으로 정합니다.

## 샘플 동작

샘플은 GoatWebViewSDK 기능을 직접 시험하는 화면이며 Android `DemoScreen`과 조작·문구가 같습니다. 설정 화면에서 URL, 상단 safe area 색(`setTopSafeAreaColor` 프리셋), 스크롤 바운스(`setScrollBounceEnabled`), 모서리 반지름(`setRoundCornerRadius` 프리셋)을 정하고 캐시를 비울 수 있습니다. **Load**는 웹뷰를 숨긴 채 만들어 URL을 미리 로드하고, **Show**는 그 웹뷰를 표시합니다. Load 전에 Show를 누르면 Load부터 합니다.

```text
설정 화면(스크롤)                         Show ▶   WebView (Fullscreen)
┌──────────────────────────────┐          ┌──────────────────────────┐
│ URL [https://example.com   ] │          │▓▓ 상단 safe area 색 ▓▓▓▓│
│ Top safe area color          │          ├──────────────────────────┤
│ [Off|Black|White|Red]        │          │                          │
│ Scroll bounce        [● ON]  │          │                          │
│ Corner [Off|12|24|Mixed]     │          │ WebView                  │
│ Display [Fullscreen|Popup]   │   ◀ Back │                  [< Back]│
│ [Clear Cache]                │(dispose) └──────────────────────────┘
│ [Load]  [Show]               │
│ Finished https://… (200)     │
└──────────────────────────────┘
```

- 프리셋과 스위치는 웹뷰가 있으면 즉시 적용하고, 웹뷰를 만들 때도 적용합니다. 상단 색 **Off**는 `setTopSafeAreaColor`를 호출하지 않으므로 SDK 기본값(상태바 영역을 투명하게 비우고 그 아래부터 웹뷰)이 됩니다. 이미 색을 적용한 웹뷰에는 다음 Load부터 반영됩니다.
- **Corner radius**는 웹 콘텐츠 영역(폴백 화면·웹뷰) 네 모서리를 둥글게 자릅니다. Off(0), 12, 24, Mixed(위쪽 두 모서리만 24, 바텀시트 모양) 프리셋이며 값은 pt로, Android 샘플(dp)과 같은 크기로 보입니다. 상단 띠는 자르지 않으므로 Fullscreen에서도 상태바 아래 웹 모서리가 둥글게 보입니다. Fullscreen의 아래 모서리는 웹이 홈 인디케이터 뒤까지 채우므로 물리 화면 모서리와 겹칩니다.
- **Display**는 웹뷰를 화면 전체(Fullscreen) 또는 설정 화면 위에 좌우 24pt·상하 96pt 마진을 둔 팝업(Popup)으로 엽니다. 설정 화면은 두 모드 모두 웹뷰 아래에 남으므로, 웹뷰가 비운 상단 safe area(Top safe area color Off)에는 Fullscreen에서도 Popup처럼 설정 화면이 보입니다. Popup은 바깥 설정 화면을 조작할 수 있어, 상단 색·바운스·캐시를 바꾸면 떠 있는 웹뷰에 바로 반영됩니다. 팝업은 safe area와 겹치지 않으므로 상단 띠 없이 웹뷰가 팝업을 그대로 채웁니다.
- iOS에는 시스템 뒤로가기가 없으므로 표시 중에는 오른쪽 아래에 **Back** 버튼을 둡니다. 웹 기록이 있으면 웹에서 뒤로 가고, 없으면 웹뷰를 `dispose()`하고 설정 화면으로 돌아갑니다(Android 뒤로가기와 같음). 폴백 화면의 닫기 버튼도 같은 상태로 돌아갑니다. 다음 Load는 새 웹뷰에서 시작합니다.
- 숨긴 웹뷰(Load 후 Show 전)는 터치를 받지 않아 아래 설정 화면을 그대로 조작할 수 있습니다(`PassthroughView.hitTest`).
- **Clear Cache**는 `WebViewCache.clear`를 호출합니다.
- 화면 아래 상태 문구에는 `Started`, `Finished`(HTTP 상태 포함), `Error`, 캐시 결과를 표시합니다.

## 빌드·테스트

아래 명령은 `native/ios`에서 실행합니다. DerivedData와 결과 번들은 `build/` 아래에 두며 Git에서 제외됩니다. `-resultBundlePath`에 같은 이름이 이미 있으면 xcodebuild가 실패하므로 지우거나 이름을 바꿉니다.

```sh
# SDK 테스트: 두 시뮬레이터에서 차례로 실행 (설치된 이름·OS로 바꿔도 됨)
xcodebuild -workspace GoatWebViewSDK.xcworkspace -scheme GoatWebViewSDK \
  -destination 'platform=iOS Simulator,name=iPhone 16 Pro,OS=18.6' \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro,OS=27.0' \
  -disable-concurrent-destination-testing -parallel-testing-enabled NO \
  -derivedDataPath build/DD-sdk \
  -resultBundlePath build/results/sdk-tests.xcresult test

# 샘플 Debug 빌드 (시뮬레이터)
xcodebuild -workspace GoatWebViewSDK.xcworkspace -scheme GoatWebViewSDKSample-SwiftUI \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/DD-sample build

# 샘플 Release 빌드 (기기, 서명 제외)
xcodebuild -workspace GoatWebViewSDK.xcworkspace -scheme GoatWebViewSDKSample-SwiftUI \
  -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO \
  -derivedDataPath build/DD-sample-release build
```

엄격한 동시성 검사는 위 명령에 `SWIFT_STRICT_CONCURRENCY=complete`를 더해 실행합니다(DerivedData는 따로 둡니다).

SDK 테스트는 `GoatUnityWrapperTests` 8개입니다. `TestHost` 앱의 윈도에서 실제 `WKWebView`를 만들고 `goatwebview_*` C 함수를 직접 부릅니다. `UnitySendMessage` 대신 `GoatUnityWrapper.sendMessage`를 바꿔 끼워 GameObject 이름·메서드 이름·JSON을 기록하고, 생성·키 윈도 부착, `\n` 구분 허용 출처, `evaluate_js` 결과와 requestId, 잘못된 URL(`INVALID_CALL`), 폴백 닫기 버튼의 해제, 중복·없는 GameObject 이름과 NULL 인자, `dispose` 뒤 메시지 차단, 픽셀→포인트 여백을 확인합니다. 네트워크는 쓰지 않습니다(`loadHTML` + baseURL).

## Unity 래퍼

`GoatWebViewSDK/UnityWrapper/GoatUnityWrapper.swift`는 Unity C#이 `[DllImport("__Internal")]`로 부를 C 함수를 `@_cdecl`로 내보내고, 웹뷰 이벤트를 `UnitySendMessage`로 C#에 보냅니다.
upstream의 `_CWebViewPlugin_*` 함수와 LINE SDK Unity의 `LineSDKNativeInterface`·`LineSDKNativeCallbackPayload`에 대응하며, Unity 패키지에는 SDK 소스와 함께 `Plugins/iOS`로 복사해 UnityFramework에 컴파일합니다(xcframework 없음).
Unity 헤더를 쓰지 않으므로 SDK 빌드·테스트에 그대로 포함됩니다.

- 웹뷰는 C#이 넘긴 GameObject 이름으로 구분하며, 이벤트도 그 GameObject로 보냅니다(upstream `Init(name)`과 같음). `UnitySendMessage`는 이름으로 GameObject를 찾으므로 이름이 겹치면 다른 오브젝트가 받을 수 있어, C#이 고유한 이름을 정합니다. 이미 쓰는 이름으로 만들면 실패하고, 없는 이름으로 부르면 아무 일도 하지 않습니다.
- 문자열은 UTF-8 `const char*`로 받습니다. 모든 문자열 인자는 NULL일 수 있으며, 필요한 인자가 NULL이면 아무 일도 하지 않습니다.
- 부모 뷰는 키 윈도의 `rootViewController.view`(Unity GL 뷰)입니다. Swift는 Unity 헤더(`UnityInterface.h`)를 가져올 수 없어 `UnitySendMessage`는 실행 중에 `dlsym`으로 찾습니다. Unity 밖(TestHost·샘플)에서는 찾지 못해 보내지 않습니다.
- 모든 함수는 Unity 메인 스레드에서 부른다는 전제로 `MainActor.assumeIsolated` 안에서 SDK를 부릅니다.
- `bool` 반환값은 C `bool`(1바이트)이므로 C#에서 `[return: MarshalAs(UnmanagedType.I1)]`로 받습니다.

| 함수 | 동작 |
| --- | --- |
| `goatwebview_create(gameObjectName)` | 메시지 브리지 없이 만듭니다. 부모 뷰가 없거나 이름이 NULL이거나 겹치면 `false` |
| `goatwebview_set_allowed_message_origins(gameObjectName, allowedOrigins)` | `allowedOrigins`는 `\n` 구분, 빈 문자열이면 브리지를 내립니다. 다음 탐색부터 적용됩니다 |
| `goatwebview_dispose(gameObjectName)` | `dispose()` 후 이름을 비웁니다. 여러 번 불러도 안전합니다 |
| `goatwebview_load_url(gameObjectName, url)`, `goatwebview_load_html(gameObjectName, html, baseUrl)` | `loadURL`, `loadHTML` |
| `goatwebview_evaluate_js(gameObjectName, requestId, js)` | `requestId`는 `int`. 결과를 `OnJavaScriptResult`로 보냅니다 |
| `goatwebview_can_go_back(gameObjectName)`, `goatwebview_can_go_forward(gameObjectName)` | 방문 기록 상태 |
| `goatwebview_go_back`, `go_forward`, `reload`, `stop_loading` | 탐색 |
| `goatwebview_set_frame(gameObjectName, x, y, w, h)` | Android `setFrame`과 같습니다. 상단 safe area를 뺀 부모 뷰 영역에 대한 비율(0...1), 원점은 safe area 바로 아래 좌상단 |
| `goatwebview_set_round_corner_radius(gameObjectName, tl, tr, bl, br)` | Unity 화면 픽셀을 `contentScaleFactor`로 나눠 pt로 바꿔 `setRoundCornerRadius`를 부릅니다 |
| `goatwebview_get_url(gameObjectName)` | 현재 URL을 `strdup`한 문자열로 돌려줍니다(C#이 해제). 없으면 NULL |
| `goatwebview_set_scroll_bounce_enabled`, `goatwebview_clear_cache()`, `goatwebview_set_forward_web_console_to_native_output(bool)` | `setScrollBounceEnabled`, `WebViewCache.clear`, `setForwardWebConsoleToNativeOutput` |
| `goatwebview_set_visibility`, `set_top_safe_area_color`, `set_suspended` | 같은 이름의 SDK API |
| `goatwebview_set_fallback_theme(gameObjectName, theme)` | `theme`은 `int` 0 light, 1 dark, 2 system. 범위 밖이면 무시 |

이벤트는 `UnitySendMessage(gameObjectName, 메서드 이름, JSON)`으로 보냅니다. 메서드 이름과 JSON 필드는 Android `unity-wrapper`(UniWebView 이벤트 이름)와 같으며, 그 GameObject의 컴포넌트가 아래 메서드를 `string` 인자 하나로 받습니다. 값이 없는 필드는 `null`입니다.

| 메서드 | JSON |
| --- | --- |
| `OnPageStarted` | `{"url"}`. 서버 리다이렉트 때도 새 URL로 다시 보냅니다 |
| `OnPageFinished` | `{"url", "statusCode"}`. 메인 문서 HTTP 상태이며 오류가 없으면 200입니다. HTTP 오류는 별도 메서드 없이 이 값으로만 알립니다 |
| `OnLoadingErrorReceived` | `{"kind", "errorCode", "errorMessage", "url"}`. `kind`는 `NETWORK`(로드 실패, `NSError.code`), `RENDERER`(웹 콘텐츠 프로세스 종료, -1), `INVALID_CALL`(http(s)가 아닌 URL, -1) |
| `OnMessageReceived` | `{"message", "origin"}`. 허용 출처 메인 프레임의 `Unity.call(message)` |
| `OnJavaScriptResult` | `{"requestId", "result"}`. `result`는 JSON 문자열이며 결과가 없으면 `null` |

- Android의 `OnNavigationChanged`, `OnMessageBridgeUnavailable`은 iOS에서 보내지 않습니다.

### 페이지 메시지 규격

프론트엔드는 메시지를 항상 JSON 문자열 `{"type": "…", "value": {…}}`로 보냅니다(예: `{"type":"closeWindow"}`, `{"type":"toUnity"}`, `{"type":"read","value":{…}}`). 네이티브는 내용을 해석하지 않고 `OnMessageReceived`의 `message`로 원문을 넘깁니다. C# 패키지는 이를 UniWebView의 `UniWebViewMessage`처럼 풀 예정입니다.

| C# `message` | 값 |
| --- | --- |
| `RawMessage`, `Origin` | 원문 JSON, 보낸 페이지 출처 |
| `Path` | `type` |
| `Args` | `value`의 최상위 필드(`Dictionary<string,string>`. 숫자·불리언은 문자열, 객체·배열은 JSON 문자열) |
| `GetData<T>()` | `value` 전체를 `T`로 역직렬화 |

JSON이 아니거나 `type`이 없으면 `Path`는 빈 문자열입니다. UniWebView의 `uniwebview://path?k=v` URL 형식은 받지 않습니다.
- 폴백 화면의 닫기 버튼은 SDK가 웹뷰를 직접 해제하고 Unity에는 보내지 않습니다. 이후 그 이름으로 온 호출은 무시되며, 같은 이름으로 다시 만들 수 있습니다.

## 이번 범위에서 뺀 upstream 기능

- `UnityGetGLViewController`. 키 윈도의 루트 뷰로 대신합니다([Unity 래퍼](#unity-래퍼) 참고)
- 초기화 옵션: `transparent`, `zoom`(`user-scalable=no` 주입), `ua`(`customUserAgent`), `enableWKWebView`와 구형 웹뷰 경로, `contentMode`, `allowsLinkPreview`, `allowsBackForwardNavigationGestures`, `radius`
- `Unity.saveDataURL`(파일 저장)과 `unity:` 스킴 메시지
- URL 패턴 필터 `SetURLPattern`(allow·deny·hook)과 `CallOnHooked`
- 사용자 지정 요청 헤더(`AddCustomHeader`, `RemoveCustomHeader`, `ClearCustomHeader`, `GetCustomHeaderValue`)
- 쿠키(`ClearCookie`, `ClearCookies`, `SaveCookies`, `GetCookies`/`CallOnCookies`)
- Basic 인증(`SetBasicAuthInfo`)
- `Progress`, `IsInitialized`
- JavaScript 대화상자(`alert`·`confirm`·`prompt`)와 `SetAlertDialogEnabled`, 새 창 요청(`target="_blank"`·`window.open`)을 같은 웹뷰에서 열기, 웹이 아닌 스킴·App Store 링크를 시스템에 넘기기
- `SetInteractionEnabled`, `SetScrollbarsVisibility`, `SetGoogleAppRedirectionEnabled`
- 공유 `WKProcessPool`, Unity 6 접근성 계층 등록, file URL 접근 옵션(`UNITYWEBVIEW_IOS_ALLOW_FILE_URLS`)

## 검증 결과

2026-10-07, Xcode 27.0에서 샘플을 데모 화면으로 바꾼 뒤 확인했습니다.

| 대상 | 결과 |
| --- | --- |
| SDK 빌드 (`generic/platform=iOS Simulator`) | 성공 |
| SDK 테스트 타깃 컴파일 (`build-for-testing`) | 성공 |
| 샘플 Debug 빌드 (`generic/platform=iOS Simulator`) | 성공 |

아직 검증하지 않은 것: 이번 변경 뒤 SDK 테스트 실행(이전 실행은 2026-10-02, 8/8 통과), 시뮬레이터에서의 데모 화면 조작(Fullscreen·Popup·상단 색·Back·Clear Cache·콘솔 출력), iPad 화면, iOS 15·16 런타임, 실기기 서명·실행, Unity 프로젝트에서의 래퍼 동작(실제 UnityFramework에서 `dlsym`으로 `UnitySendMessage`를 찾는지 포함). 출처는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)를 참고하세요.
