@echo off
rem native\windows의 WebView2 Editor DLL(GoatWebViewPlugin.dll)을 빌드해 Unity 패키지 Plugins\Windows\x86_64 로 복사한다.
rem Visual Studio(C++ 데스크톱 개발)와 WebView2 Runtime이 필요하다. Unity Editor는 로드한 DLL을 내리지 않으므로 바꾼 뒤 Editor를 다시 시작한다.
setlocal
rem 한글 메시지가 깨지지 않도록 UTF-8 코드 페이지를 쓴다.
chcp 65001 >nul
set "ROOT=%~dp0.."
set "WIN=%ROOT%\native\windows"
set "DST=%ROOT%\unity\Packages\com.goatwebview.unity\Plugins\Windows\x86_64"

echo [1/3] WebView2 SDK 복원
powershell -NoProfile -ExecutionPolicy Bypass -File "%WIN%\restore_webview2_sdk.ps1" || goto :fail

echo [2/3] msbuild 찾기
rem 괄호 블록 안에서는 %VAR%가 블록 진입 시점 값으로 치환되므로 변수 설정과 사용을 블록 밖에서 한다.
set "MSBUILD="
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
where msbuild >nul 2>nul && set "MSBUILD=msbuild"
if defined MSBUILD goto :build
if not exist "%VSWHERE%" goto :nomsbuild
rem -products * 로 Build Tools만 설치된 경우도 찾는다.
for /f "usebackq delims=" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.Component.MSBuild -find MSBuild\**\Bin\MSBuild.exe`) do set "MSBUILD=%%i"
if defined MSBUILD goto :build
:nomsbuild
echo msbuild를 찾지 못했습니다. Visual Studio^(C++ 데스크톱 개발^)를 설치하세요.
goto :fail

:build
rem 설치된 Visual Studio 주 버전에 맞는 플랫폼 도구 집합을 고른다(16→v142, 17→v143, 18→v145). 못 읽으면 vcxproj 기본값(v143)을 쓴다.
set "TOOLSET="
set "VSMAJOR="
if exist "%VSWHERE%" for /f "usebackq tokens=1 delims=." %%v in (`"%VSWHERE%" -latest -products * -requires Microsoft.Component.MSBuild -property installationVersion`) do set "VSMAJOR=%%v"
if "%VSMAJOR%"=="16" set "TOOLSET=/p:PlatformToolset=v142"
if "%VSMAJOR%"=="17" set "TOOLSET=/p:PlatformToolset=v143"
if "%VSMAJOR%"=="18" set "TOOLSET=/p:PlatformToolset=v145"
echo [3/3] 빌드 및 복사 ^(msbuild: %MSBUILD%, VS %VSMAJOR% %TOOLSET%^)
"%MSBUILD%" "%WIN%\WebViewPlugin.sln" /p:Configuration=Release /p:Platform=x64 %TOOLSET% /nologo /v:m || goto :fail
if not exist "%DST%" mkdir "%DST%"
copy /y "%WIN%\bin\x64\Release\GoatWebViewPlugin.dll" "%DST%\GoatWebViewPlugin.dll" >nul || goto :fail
rem 임포터 설정(Editor·Windows·x86_64 전용)이 없으면 템플릿 meta를 함께 둔다.
if not exist "%DST%\GoatWebViewPlugin.dll.meta" copy /y "%WIN%\GoatWebViewPlugin.dll.meta" "%DST%\GoatWebViewPlugin.dll.meta" >nul || goto :fail
echo 복사됨: %DST%\GoatWebViewPlugin.dll
pause
exit /b 0

:fail
echo 실패했습니다. 위 로그를 확인하세요.
pause
exit /b 1
