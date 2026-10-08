# Windows 코드 출처

이 디렉터리는 원본 `unity-webview` 배포본이 아닌 `goat-webview`의 수정 버전입니다.

- `WebViewPlugin.cpp`, `WebViewPlugin.sln`, `WebViewPlugin.vcxproj`, `packages.config`, `restore_webview2_sdk.ps1`은 [GREE unity-webview](https://github.com/gree/unity-webview)의 `plugins/Windows`(로컬 레퍼런스 커밋 `5604a7d`, 2026-08-28)에서 가져왔습니다.
- `WebViewPlugin.cpp`는 `GetMessage` 메시지 이름 세 개만 바꿨고, 파일 위쪽에 수정 사실을 적었습니다. `WebViewPlugin.vcxproj`는 출력 DLL 이름(`TargetName`)만 바꿨습니다.
- 원본의 미리 빌드된 `bin/`, `PlanDocument/`, `install.sh`, `README*.md`는 가져오지 않았습니다.
- 원본 zlib 고지는 [unity-webview-LICENSE.txt](licenses/unity-webview-LICENSE.txt)에 보존합니다.
- Microsoft WebView2 SDK는 빌드 시 NuGet으로 받으며 저장소에 포함하지 않습니다.

저장소 전체의 배포 라이선스 결정은 별도 작업입니다. 이 문서는 코드 출처와 원본 고지를 기록하며 새로운 라이선스를 부여하지 않습니다.
