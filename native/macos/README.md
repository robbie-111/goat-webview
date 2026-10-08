# Goat WebView — macOS (Unity Editor 전용)

Unity Editor(macOS)에서 웹뷰를 확인하기 위한 플러그인입니다. 서비스용이 아니며 Standalone 앱은 지원하지 않습니다.
[gree/unity-webview](https://github.com/gree/unity-webview)의 `plugins/Mac`(5604a7d)을 거의 그대로 옮기고, C#이 받는 이벤트 이름과 JSON만 iOS·Android Unity 래퍼에 맞췄습니다.

WKWebView를 화면 밖 창에 그려 비트맵으로 떠서 Unity 텍스처로 넘기고(`_CWebViewPlugin_Update`, `_CWebViewPlugin_Render`), 마우스·키 입력은 C#이 넘겨 줍니다(`_CWebViewPlugin_SendMouseEvent`, `_CWebViewPlugin_SendKeyEvent`). Editor에서는 `UnitySendMessage`를 쓸 수 없어, 이벤트는 네이티브 큐에 쌓고 C#이 매 프레임 `_CWebViewPlugin_GetMessage`로 꺼냅니다.

## 폴더 구조

```text
native/macos/
├── WebView.xcodeproj/          # WebView 타깃 → GoatWebView.bundle
├── Sources/WebView.mm          # upstream 본체 + 변경점
├── Resources/                  # Info.plist, InfoPlist.strings, Prefix.pch, Info-WebViewSeparated.plist (upstream 그대로)
├── licenses/unity-webview-LICENSE.txt
└── README.md
```

upstream의 `install.sh`(upstream Packager 경로 전용)와 `WebView.bundle.meta`(Unity 패키지 작업 때 정함)는 가져오지 않았습니다.

## 빌드

`native/macos`에서 실행합니다. `build/`는 Git에서 제외됩니다.

```sh
xcodebuild -project WebView.xcodeproj -target WebView -configuration Release \
  -arch x86_64 -arch arm64 \
  CONFIGURATION_BUILD_DIR="$PWD/build" SYMROOT="$PWD/build/sym" OBJROOT="$PWD/build/obj" build
# 결과: build/GoatWebView.bundle (x86_64 + arm64)
```

C#에서는 `[DllImport("GoatWebView")]`로 부릅니다. 함수 이름과 인자는 upstream `_CWebViewPlugin_*` 그대로입니다.

## upstream 대비 변경점

- 결과물 이름 `WebView.bundle` → `GoatWebView.bundle`, 번들 ID `dev.goatwebview.macos`. upstream 번들과 같은 Unity 프로젝트에 있어도 겹치지 않습니다.
- Objective-C 클래스 이름에 `Goat`를 붙였습니다(`GoatWebViewPlugin`, `GoatCNSWindow`, `GoatWeakScriptMessageDelegate`). 두 번들이 같은 Editor 프로세스에 로드되면 같은 이름의 클래스가 충돌하기 때문입니다. C 함수는 번들마다 따로라 그대로 둡니다.
- 최소 macOS 10.13 → 12.0. Xcode 27이 지원하는 최소 버전입니다.
- 이벤트 이름과 JSON을 iOS·Android에 맞췄습니다(아래 표).
- `_CWebViewPlugin_EvaluateJavaScript(instance, requestId, js)`를 더했습니다. 결과 없는 `_CWebViewPlugin_EvaluateJS`는 그대로입니다.

빌드 경고는 upstream 코드의 deprecated API(`WKProcessPool`, `javaScriptEnabled`, `plugInsEnabled`)와 빈 Run Script 단계에서 나오며, Editor 전용이라 그대로 둡니다.

## 이벤트

`_CWebViewPlugin_GetMessage`는 `"메서드:JSON"` 문자열을 하나씩 돌려주고, 없으면 NULL입니다. C#은 첫 `:` 앞을 메서드 이름으로, 뒤를 JSON으로 읽어 iOS·Android의 `UnitySendMessage`와 같은 처리로 넘깁니다.

| 메서드 | JSON | upstream |
| --- | --- | --- |
| `OnPageStarted` | `{"url"}` | `CallOnStarted` |
| `OnPageFinished` | `{"url", "statusCode"}`. 메인 문서 HTTP 오류 상태, 없으면 200 | `CallOnLoaded` + `CallOnHttpError` |
| `OnLoadingErrorReceived` | `{"kind", "errorCode", "errorMessage", "url"}`. 로드 실패는 `NETWORK`(`NSError.code`), 웹 콘텐츠 프로세스 종료·창 닫힘은 `RENDERER`(-1) | `CallOnError` |
| `OnMessageReceived` | `{"message", "origin"}`. `Unity.call(msg)`, `unityControl.postMessage(msg)`(문자열만), `unity:` 스킴 | `CallFromJS` |
| `OnJavaScriptResult` | `{"requestId", "result"}`. `result`는 JSON 문자열이며 결과가 없으면 null | 없음 |
| `CallOnHooked`, `CallOnCookies` | 문자열 그대로 | 그대로 |

iOS·Android와 달리 메시지의 출처·프레임을 검사하지 않습니다(upstream 그대로, Editor 전용).

## 검증

Xcode 27.0에서 universal 빌드와 `nm`으로 `_CWebViewPlugin_*` 34개 내보내기(`_CWebViewPlugin_EvaluateJavaScript` 포함), 바이너리에 `Goat*` 클래스만 있는 것을 확인했습니다. Unity Editor에서의 동작은 C# 작업 전이라 확인하지 않았습니다. 라이선스는 [licenses/unity-webview-LICENSE.txt](licenses/unity-webview-LICENSE.txt)를 참고하세요.
