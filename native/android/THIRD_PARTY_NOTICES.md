# Android 코드 출처

이 디렉터리는 원본 `unity-webview` 배포본이 아닌 `goat-webview`의 수정·재구성 버전입니다.

- `webview-sdk`의 세 Kotlin 클래스는 로컬 레퍼런스 `codex-webview-kit/native/android/webview-core`에서 이관했습니다. 패키지 이름만 `dev.webviewkit.core`에서 `com.goatwebview.sdk`로 변경했습니다.
- 샘플 앱 아이콘은 `codex-webview-kit`에서 가져왔습니다.
- 기반 레퍼런스 [GREE unity-webview](https://github.com/gree/unity-webview)의 원본 zlib 고지는 [unity-webview-LICENSE.txt](licenses/unity-webview-LICENSE.txt)에 보존합니다. 기존 Unity Java 플러그인 전체를 이관한 것은 아닙니다.
- Gradle Wrapper는 Gradle이 제공하는 파일이며 파일 내부의 원본 고지를 유지합니다. AndroidX·Kotlin 등은 Gradle 의존성으로 사용합니다.

저장소 전체의 배포 라이선스 결정은 별도 작업입니다. 이 문서는 코드 출처와 원본 고지를 기록하며 새로운 라이선스를 부여하지 않습니다.
