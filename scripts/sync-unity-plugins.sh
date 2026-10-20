#!/usr/bin/env bash
# native/의 Android AAR, iOS Swift 소스, macOS Editor 번들을 Unity 패키지(unity/Packages/com.goatwebview.unity/Plugins)로 복사한다.
# Android 빌드에는 JDK 17(JAVA_HOME)과 Android SDK(native/android/local.properties 또는 ANDROID_HOME)가,
# macOS 번들 빌드에는 Xcode가 필요하다.
# 복사본의 .meta는 그대로 두므로 Unity의 플러그인 설정이 유지된다. native에서 지운 파일은 직접 지운다.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLUGINS="$ROOT/unity/Packages/com.goatwebview.unity/Plugins"

(cd "$ROOT/native/android" && ./gradlew -q :webview-sdk:assembleRelease :unity-wrapper:assembleRelease)
mkdir -p "$PLUGINS/Android"
cp "$ROOT/native/android/webview-sdk/build/outputs/aar/webview-sdk-release.aar" "$PLUGINS/Android/GoatWebViewSDK.aar"
cp "$ROOT/native/android/unity-wrapper/build/outputs/aar/unity-wrapper-release.aar" "$PLUGINS/Android/GoatWebViewUnity.aar"

# iOS는 SDK 소스와 Unity 래퍼를 UnityFramework에서 함께 컴파일한다(xcframework 없음).
IOS_SRC="$ROOT/native/ios/GoatWebViewSDK/GoatWebViewSDK"
IOS_DST="$PLUGINS/iOS/GoatWebViewSDK"
mkdir -p "$IOS_DST"
cp "$IOS_SRC"/*.swift "$IOS_SRC"/UnityWrapper/*.swift "$IOS_DST/"

# macOS Editor 번들(x86_64 + arm64). Unity Editor는 로드한 번들을 내리지 않으므로 바꾼 뒤 Editor를 다시 시작한다.
MAC="$ROOT/native/macos"
xcodebuild -quiet -project "$MAC/WebView.xcodeproj" -target WebView -configuration Release -arch x86_64 -arch arm64 \
  CONFIGURATION_BUILD_DIR="$MAC/build" SYMROOT="$MAC/build/sym" OBJROOT="$MAC/build/obj" build
mkdir -p "$PLUGINS/Mac"
rm -rf "$PLUGINS/Mac/GoatWebView.bundle"
cp -R "$MAC/build/GoatWebView.bundle" "$PLUGINS/Mac/"

echo "Unity 플러그인을 갱신했습니다: $PLUGINS"
