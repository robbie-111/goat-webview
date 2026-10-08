# goat-webview Windows (Unity Editor 전용)

Unity Editor(Windows)에서 웹뷰를 확인하기 위한 WebView2 네이티브 플러그인입니다. 서비스 배포용이 아닙니다.
[gree/unity-webview](https://github.com/gree/unity-webview)의 `plugins/Windows`를 거의 그대로 가져왔으며, 바꾼 것은 아래 두 가지뿐입니다.

- `GetMessage` 메시지 이름을 Android·iOS의 UnitySendMessage 메서드 이름에 맞췄습니다.
- DLL 이름을 `GoatWebView.dll`로 바꿨습니다(gree `WebView.dll`과 충돌 방지).

## 동작 방식

- WebView2를 화면 밖 STA 스레드에서 실행하고 `CapturePreview` 비트맵을 Unity 텍스처로 렌더링합니다. 마우스·키 입력은 C#이 `_CWebViewPlugin_SendMouseEvent`·`_CWebViewPlugin_SendKeyEvent`로 전달합니다.
- Windows·macOS 데스크톱에는 네이티브 플러그인이 호출할 `UnitySendMessage`가 없습니다. 그래서 이벤트를 큐에 쌓아 두고, C#이 매 프레임 `_CWebViewPlugin_GetMessage`로 꺼내 `"메서드:값"`을 나눈 뒤 같은 컴포넌트의 메서드를 `SendMessage`로 호출합니다.
- 문서 생성 시 `window.Unity.call(msg)`를 넣고, 이 함수는 `window.chrome.webview.postMessage`로 메시지를 보냅니다.

| 메시지 | 값 | gree 원래 이름 |
| --- | --- | --- |
| `OnMessageReceived` | `Unity.call` 메시지 원문 | `CallFromJS` |
| `OnPageStarted` | URL | `CallOnStarted` |
| `OnPageFinished` | URL (성공한 탐색만) | `CallOnLoaded` |

기기(Android·iOS)와 달리 값은 JSON이 아니라 원문 문자열입니다. C 함수 이름(`_CWebViewPlugin_*`)과 시그니처는 gree와 같습니다.

## 빌드

Windows 10 이상, "C++를 사용한 데스크톱 개발"이 포함된 Visual Studio, WebView2 Runtime이 필요합니다.

```powershell
cd native\windows
.\restore_webview2_sdk.ps1        # NuGet Microsoft.Web.WebView2 1.0.3800.47 → packages\
msbuild WebViewPlugin.sln /p:Configuration=Release /p:Platform=x64
msbuild WebViewPlugin.sln /p:Configuration=Release /p:Platform=Win32
```

- 산출물: `bin\x64\Release\GoatWebView.dll`, `bin\Win32\Release\GoatWebView.dll`
- `bin\`, `packages\`, `nuget.exe`는 Git에서 제외합니다.
- 입력 문제를 추적하려면 `WebViewPlugin.cpp` 위쪽의 `WEBVIEW_DEBUG`를 1로 바꿔 빌드하고 DebugView에서 `[WebView2]` 로그를 확인합니다.

## Unity 배치

Unity 패키지 구성은 아직 정하지 않았습니다. 지금은 빌드한 DLL을 Unity 프로젝트의 `Assets/Plugins/x86_64/`(x64)에 복사하고, Inspector에서 Editor(Windows)만 선택합니다.

코드 출처와 원본 고지는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)를 참고하세요.
