# goat-webview Windows (Unity Editor 전용)

Unity Editor(Windows)에서 웹뷰를 확인하기 위한 WebView2 네이티브 플러그인입니다. 서비스 배포용이 아닙니다.
[gree/unity-webview](https://github.com/gree/unity-webview)의 `plugins/Windows`를 거의 그대로 가져왔으며, 바꾼 것은 아래 두 가지뿐입니다.

- `GetMessage` 메시지 이름을 Android·iOS의 UnitySendMessage 메서드 이름에 맞췄습니다.
- DLL 이름을 `GoatWebViewPlugin.dll`로 바꿨습니다(gree `WebView.dll`, 그리고 asmdef가 만드는 관리 어셈블리 `GoatWebView.dll`과 충돌 방지).

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

### Visual Studio C++ 워크로드 설치

1. 이미 Visual Studio가 있으면 **Visual Studio Installer** → 설치된 제품의 **수정(Modify)** → 워크로드 탭에서 **C++를 사용한 데스크톱 개발**을 체크하고 **수정**을 누릅니다(3~5GB).
2. 없으면 둘 중 하나를 설치합니다. 설치기에서 **C++를 사용한 데스크톱 개발**을 체크하면 MSVC v143 빌드 도구와 Windows SDK가 함께 들어옵니다.
   - **Visual Studio 2022 Community**(IDE 포함, 무료): <https://visualstudio.microsoft.com/ko/downloads/>
   - **Build Tools for Visual Studio 2022**(컴파일러만, 더 가벼움): 같은 페이지의 "Visual Studio 2022용 도구". `sync-unity-plugins.bat`는 Build Tools도 찾습니다.
   - 명령줄로 한 번에 설치하려면(관리자 PowerShell):
     ```powershell
     winget install Microsoft.VisualStudio.2022.BuildTools --override "--quiet --wait --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"
     ```
3. **WebView2 Runtime**은 Windows 11과 최신 Windows 10에 이미 있습니다. 없으면 <https://developer.microsoft.com/microsoft-edge/webview2/>의 Evergreen Bootstrapper를 설치합니다. 빌드가 아니라 Unity Editor에서 웹뷰를 띄울 때 필요합니다.
4. 설치 후 `scripts\sync-unity-plugins.bat`를 다시 실행합니다. `[3/3] 빌드 및 복사 (msbuild: ...)`가 찍히면 정상입니다.

```powershell
cd native\windows
.\restore_webview2_sdk.ps1        # NuGet Microsoft.Web.WebView2 1.0.3800.47 → packages\
msbuild WebViewPlugin.sln /p:Configuration=Release /p:Platform=x64
msbuild WebViewPlugin.sln /p:Configuration=Release /p:Platform=Win32
```

- 산출물: `bin\x64\Release\GoatWebViewPlugin.dll`, `bin\Win32\Release\GoatWebViewPlugin.dll`
- 또는 저장소의 `scripts\sync-unity-plugins.bat`를 더블클릭하면 SDK 복원 → x64 빌드 → Unity 패키지 복사까지 한 번에 한다.
- `bin\`, `packages\`, `nuget.exe`는 Git에서 제외합니다.
- 입력 문제를 추적하려면 `WebViewPlugin.cpp` 위쪽의 `WEBVIEW_DEBUG`를 1로 바꿔 빌드하고 DebugView에서 `[WebView2]` 로그를 확인합니다.

## Unity 배치

x64 빌드 산출물 `bin\x64\Release\GoatWebViewPlugin.dll`을 패키지의 `unity/Packages/com.goatwebview.unity/Plugins/Windows/x86_64/`에 복사합니다. 같은 폴더에 meta가 없으면 이 폴더의 `GoatWebViewPlugin.dll.meta`(Editor·Windows·x86_64 전용 임포터 설정)를 함께 복사하므로 Inspector 설정은 필요 없습니다. DLL과 meta는 함께 커밋합니다. Unity Editor는 로드한 DLL을 내리지 않으므로 바꾼 뒤 Editor를 다시 시작합니다.

코드 출처와 원본 고지는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)를 참고하세요.
