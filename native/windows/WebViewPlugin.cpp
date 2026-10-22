/*
 * Copyright (C) 2012 GREE, Inc.
 * Windows WebView2 implementation for unity-webview.
 *
 * This software is provided 'as-is'. See repository root LICENSE.
 * Uses Microsoft WebView2 (Edge Chromium). Requires WebView2 Runtime.
 */

// 수정됨: gree/unity-webview plugins/Windows/WebViewPlugin.cpp를 goat-webview용으로 옮긴 파일이다(Unity Editor 전용).
// macOS 번들(native/macos/Sources/WebView.mm)과 같은 동작을 목표로 한다.
// - 이벤트 이름과 JSON을 iOS·Android Unity 래퍼에 맞췄다: OnPageStarted{url}, OnPageFinished{url,statusCode},
//   OnLoadingErrorReceived{kind,errorCode,errorMessage,url}, OnMessageReceived{message,origin}.
// - JavaScript 결과를 OnJavaScriptResult{requestId,result}로 돌려주는 _CWebViewPlugin_EvaluateJavaScript를 더했다.
// - 웹 콘솔과 잡히지 않은 예외를 OnConsoleMessage{level,message,source,line}로 보낸다.
// - 비트맵은 macOS 번들과 같은 top-down 행 순서다. Unity 텍스처 원점이 좌하단이라 C# GoatWebViewEditorView가 뒤집어 그린다.

#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <objbase.h>
#include <wrl.h>
#include <string>
#include <cstring>
#include <queue>
#include <mutex>
#include <memory>
#include <atomic>
#include <stdio.h>

#include "WebView2.h"

#pragma comment(lib, "user32.lib")
#pragma comment(lib, "ole32.lib")

// Set to 1 to log input and window info to OutputDebugString.
// View logs: run DebugView (Sysinternals) as admin and enable "Capture Global Win32", or run Unity from Visual Studio and check Output.
#ifndef WEBVIEW_DEBUG
#define WEBVIEW_DEBUG 0
#endif
#if WEBVIEW_DEBUG
#define WV_LOG(fmt, ...) do { char _buf[384]; snprintf(_buf, sizeof(_buf), "[WebView2] " fmt "\n", ##__VA_ARGS__); OutputDebugStringA(_buf); } while(0)
#else
#define WV_LOG(fmt, ...) ((void)0)
#endif

using namespace Microsoft::WRL;

//------------------------------------------------------------------------------
// Message queue (thread-safe; producer = WebView2 callbacks on STA, consumer = GetMessage on Unity thread)
//------------------------------------------------------------------------------
struct MessageQueue {
    std::mutex mtx;
    std::queue<std::string> q;

    void push(const std::string& s) {
        std::lock_guard<std::mutex> lk(mtx);
        q.push(s);
    }

    bool pop(std::string& out) {
        std::lock_guard<std::mutex> lk(mtx);
        if (q.empty()) return false;
        out = std::move(q.front());
        q.pop();
        return true;
    }
};

//------------------------------------------------------------------------------
// 문자열·JSON 도우미
//------------------------------------------------------------------------------
static std::string WideToUtf8(LPCWSTR w) {
    if (!w) return std::string();
    int n = WideCharToMultiByte(CP_UTF8, 0, w, -1, nullptr, 0, nullptr, nullptr);
    if (n <= 1) return std::string();
    std::string s((size_t)n - 1, 0);
    WideCharToMultiByte(CP_UTF8, 0, w, -1, &s[0], n, nullptr, nullptr);
    return s;
}

// 호출자가 delete[] 한다.
static wchar_t* Utf8ToWideAlloc(const char* s) {
    int n = MultiByteToWideChar(CP_UTF8, 0, s, -1, nullptr, 0);
    if (n <= 0) n = 1;
    wchar_t* w = new wchar_t[n];
    w[0] = 0;
    MultiByteToWideChar(CP_UTF8, 0, s, -1, w, n);
    return w;
}

static std::string JsonEscape(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (unsigned char c : s) {
        switch (c) {
        case '"': out += "\\\""; break;
        case '\\': out += "\\\\"; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        default:
            if (c < 0x20) {
                char b[8];
                snprintf(b, sizeof(b), "\\u%04x", c);
                out += b;
            } else {
                out += (char)c;
            }
        }
    }
    return out;
}

static std::string JsonString(const std::string& s) {
    return "\"" + JsonEscape(s) + "\"";
}

// "https://Host:443/path" → "https://host" (iOS·Android 래퍼의 origin과 같은 형식). 기본 포트는 생략하고, 파싱할 수 없으면 ""다.
static std::string OriginOf(const std::string& url) {
    size_t schemeEnd = url.find("://");
    if (schemeEnd == std::string::npos) return std::string();
    std::string scheme = url.substr(0, schemeEnd);
    size_t hostStart = schemeEnd + 3;
    size_t hostEnd = url.find_first_of("/?#", hostStart);
    std::string authority = url.substr(hostStart, hostEnd == std::string::npos ? std::string::npos : hostEnd - hostStart);
    size_t at = authority.rfind('@');
    if (at != std::string::npos) authority = authority.substr(at + 1);
    std::string host = authority, port;
    size_t colon = authority.rfind(':');
    if (colon != std::string::npos && authority.find(']') == std::string::npos) {
        host = authority.substr(0, colon);
        port = authority.substr(colon + 1);
    }
    if (host.empty()) return std::string();
    for (auto& c : scheme) c = (char)tolower((unsigned char)c);
    for (auto& c : host) c = (char)tolower((unsigned char)c);
    if (scheme != "http" && scheme != "https") return std::string();
    const char* defaultPort = scheme == "https" ? "443" : "80";
    if (port.empty() || port == defaultPort) return scheme + "://" + host;
    return scheme + "://" + host + ":" + port;
}

static const char* WebErrorStatusName(COREWEBVIEW2_WEB_ERROR_STATUS status) {
    switch (status) {
    case COREWEBVIEW2_WEB_ERROR_STATUS_CERTIFICATE_COMMON_NAME_IS_INCORRECT: return "CERTIFICATE_COMMON_NAME_IS_INCORRECT";
    case COREWEBVIEW2_WEB_ERROR_STATUS_CERTIFICATE_EXPIRED: return "CERTIFICATE_EXPIRED";
    case COREWEBVIEW2_WEB_ERROR_STATUS_CLIENT_CERTIFICATE_CONTAINS_ERRORS: return "CLIENT_CERTIFICATE_CONTAINS_ERRORS";
    case COREWEBVIEW2_WEB_ERROR_STATUS_CERTIFICATE_REVOKED: return "CERTIFICATE_REVOKED";
    case COREWEBVIEW2_WEB_ERROR_STATUS_CERTIFICATE_IS_INVALID: return "CERTIFICATE_IS_INVALID";
    case COREWEBVIEW2_WEB_ERROR_STATUS_SERVER_UNREACHABLE: return "SERVER_UNREACHABLE";
    case COREWEBVIEW2_WEB_ERROR_STATUS_TIMEOUT: return "TIMEOUT";
    case COREWEBVIEW2_WEB_ERROR_STATUS_ERROR_HTTP_INVALID_SERVER_RESPONSE: return "ERROR_HTTP_INVALID_SERVER_RESPONSE";
    case COREWEBVIEW2_WEB_ERROR_STATUS_CONNECTION_ABORTED: return "CONNECTION_ABORTED";
    case COREWEBVIEW2_WEB_ERROR_STATUS_CONNECTION_RESET: return "CONNECTION_RESET";
    case COREWEBVIEW2_WEB_ERROR_STATUS_DISCONNECTED: return "DISCONNECTED";
    case COREWEBVIEW2_WEB_ERROR_STATUS_CANNOT_CONNECT: return "CANNOT_CONNECT";
    case COREWEBVIEW2_WEB_ERROR_STATUS_HOST_NAME_NOT_RESOLVED: return "HOST_NAME_NOT_RESOLVED";
    case COREWEBVIEW2_WEB_ERROR_STATUS_OPERATION_CANCELED: return "OPERATION_CANCELED";
    case COREWEBVIEW2_WEB_ERROR_STATUS_REDIRECT_FAILED: return "REDIRECT_FAILED";
    case COREWEBVIEW2_WEB_ERROR_STATUS_UNEXPECTED_ERROR: return "UNEXPECTED_ERROR";
    case COREWEBVIEW2_WEB_ERROR_STATUS_VALID_AUTHENTICATION_CREDENTIALS_REQUIRED: return "VALID_AUTHENTICATION_CREDENTIALS_REQUIRED";
    case COREWEBVIEW2_WEB_ERROR_STATUS_VALID_PROXY_AUTHENTICATION_REQUIRED: return "VALID_PROXY_AUTHENTICATION_REQUIRED";
    default: return "UNKNOWN";
    }
}

// 콘솔 메시지는 Unity.call 메시지와 같은 postMessage 채널을 쓰므로 이 접두어로 구분한다.
static const char kConsolePrefix[] = "\x01goatConsole:";

//------------------------------------------------------------------------------
// Instance state
//------------------------------------------------------------------------------
struct WebViewInstance {
    HWND hwnd = nullptr;
    ComPtr<ICoreWebView2Controller> controller;
    ComPtr<ICoreWebView2CompositionController> compositionController;
    ComPtr<ICoreWebView2> webview;
    MessageQueue messages;
    std::string gameObjectName;

    int rectWidth = 0;
    int rectHeight = 0;
    bool visible = true;

    // Bitmap from CapturePreview (decoded to RGBA). Double-buffered: STA thread decodes into
    // bitmapPixelsBack then swaps with bitmapPixels so Render() reads consistent front buffer.
    std::mutex bitmapMutex;
    std::vector<uint8_t> bitmapPixels;
    int bitmapWidth = 0;
    int bitmapHeight = 0;
    std::vector<uint8_t> bitmapPixelsBack;
    int bitmapWidthBack = 0;
    int bitmapHeightBack = 0;
    std::atomic<bool> captureInProgress{ false };
    HANDLE captureDoneEvent = nullptr;

    // Custom headers for navigation
    std::mutex headersMutex;
    std::wstring customHeaders; // "Key: Value\r\n..."

    // URL pattern (allow/deny/hook) - simplified: we allow all for now
    bool allowAllUrls = true;

    // Cached for main-thread read
    std::mutex cacheMutex;
    bool canGoBack = false;
    bool canGoForward = false;
    std::atomic<int> progress{ 0 };

    // Set when Destroy is in progress; main-thread APIs return early to avoid use-after-free.
    std::atomic<bool> destroying{ false };
};

static std::mutex s_instancesMutex;
static std::vector<std::unique_ptr<WebViewInstance>> s_instances;

// macOS 번들(sendMessage:fields:)과 같은 "메서드:{json}" 형식. C#은 첫 ':' 앞을 메서드 이름, 뒤를 JSON으로 읽는다.
// fields는 이미 JSON 조각("\"url\":\"...\",\"statusCode\":200")이다.
static void PushEvent(WebViewInstance* inst, const char* method, const std::string& fields) {
    inst->messages.push(std::string(method) + ":{" + fields + "}");
}

static std::string CurrentUrl(ICoreWebView2* wv) {
    LPWSTR raw = nullptr;
    if (!wv || FAILED(wv->get_Source(&raw)) || !raw) return std::string();
    std::string s = WideToUtf8(raw);
    CoTaskMemFree(raw);
    return s;
}

//------------------------------------------------------------------------------
// PNG stream -> RGBA using WIC
//------------------------------------------------------------------------------
#include <wincodec.h>
#pragma comment(lib, "windowscodecs.lib")

static bool DecodePngStreamToRgba(IStream* stream, std::vector<uint8_t>& outPixels, int& outWidth, int& outHeight) {
    ComPtr<IWICImagingFactory> factory;
    HRESULT hr = CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&factory));
    if (FAILED(hr)) return false;

    ComPtr<IWICBitmapDecoder> decoder;
    hr = factory->CreateDecoderFromStream(stream, nullptr, WICDecodeMetadataCacheOnLoad, &decoder);
    if (FAILED(hr)) return false;

    ComPtr<IWICBitmapFrameDecode> frame;
    hr = decoder->GetFrame(0, &frame);
    if (FAILED(hr)) return false;

    UINT width, height;
    hr = frame->GetSize(&width, &height);
    if (FAILED(hr)) return false;

    ComPtr<IWICFormatConverter> converter;
    hr = factory->CreateFormatConverter(&converter);
    if (FAILED(hr)) return false;

    WICPixelFormatGUID format = GUID_WICPixelFormat32bppRGBA;
    hr = converter->Initialize(frame.Get(), format, WICBitmapDitherTypeNone, nullptr, 0.f, WICBitmapPaletteTypeCustom);
    if (FAILED(hr)) return false;

    UINT stride = width * 4;
    UINT size = stride * height;
    // 행 순서는 위→아래(top-down)로 그대로 둔다. macOS 번들의 NSBitmapImageRep과 같고,
    // Unity 텍스처는 좌하단이 원점이라 C#(GoatWebViewEditorView)이 그릴 때 뒤집는다. 여기서 뒤집으면 이중 반전이 된다.
    outPixels.resize(size);
    hr = converter->CopyPixels(nullptr, stride, size, outPixels.data());
    if (FAILED(hr)) return false;

    outWidth = (int)width;
    outHeight = (int)height;
    return true;
}

//------------------------------------------------------------------------------
// STA thread and window
//------------------------------------------------------------------------------
enum CustomMsg {
    WM_WEBVIEW_CREATE = WM_USER + 1,
    WM_WEBVIEW_DESTROY,
    WM_WEBVIEW_LOAD_URL,
    WM_WEBVIEW_LOAD_HTML,
    WM_WEBVIEW_EVAL_JS,
    WM_WEBVIEW_EVAL_JS_RESULT,
    WM_WEBVIEW_SET_RECT,
    WM_WEBVIEW_SET_VISIBILITY,
    WM_WEBVIEW_GO_BACK,
    WM_WEBVIEW_GO_FORWARD,
    WM_WEBVIEW_RELOAD,
    WM_WEBVIEW_CAPTURE,
    WM_WEBVIEW_ADD_HEADER,
    WM_WEBVIEW_REMOVE_HEADER,
    WM_WEBVIEW_CLEAR_HEADERS,
    WM_WEBVIEW_CLEAR_COOKIES,
    WM_WEBVIEW_SEND_MOUSE,
    WM_WEBVIEW_SEND_KEY,
};

struct MouseEventData {
    int x, y;
    float deltaY;
    int mouseState;
};

struct EvalData {
    int requestId;
    wchar_t* js;  // owned; STA thread must free
};

struct KeyEventData {
    char* keyChars;  // owned; STA thread must free
    unsigned short keyCode;
    int keyState;
};

struct CreateParams {
    WebViewInstance* instance = nullptr;
    std::wstring userDataFolder;
    std::wstring userAgent;
    bool transparent = false;
    bool zoom = true;
    int width = 0;
    int height = 0;
    HANDLE readyEvent = nullptr;
    HRESULT createResult = E_PENDING;
};

static LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wParam, LPARAM lParam) {
    WebViewInstance* inst = (WebViewInstance*)GetWindowLongPtr(hwnd, GWLP_USERDATA);

    switch (msg) {
    case WM_CREATE: {
        CREATESTRUCT* cs = (CREATESTRUCT*)lParam;
        CreateParams* params = (CreateParams*)cs->lpCreateParams;
        SetWindowLongPtr(hwnd, GWLP_USERDATA, (LONG_PTR)params->instance);
        params->instance->hwnd = hwnd;

        // Create WebView2 in same thread (we are on STA)
        params->instance->gameObjectName = "WebViewObject"; // will be set by Init

        // Use user-writable dir (e.g. %LOCALAPPDATA%\UnityWebView2). Program Files is not writable.
        std::wstring path;
        WCHAR localAppData[MAX_PATH];
        if (GetEnvironmentVariableW(L"LOCALAPPDATA", localAppData, MAX_PATH) > 0) {
            path = localAppData;
            path += L"\\UnityWebView2";
        } else {
            WCHAR tmp[MAX_PATH];
            GetModuleFileNameW(nullptr, tmp, MAX_PATH);
            path = tmp;
            size_t last = path.find_last_of(L"\\/");
            if (last != std::wstring::npos) path = path.substr(0, last);
            path += L"\\WebView2Data";
        }
        CreateDirectoryW(path.c_str(), nullptr);

        ComPtr<ICoreWebView2Environment> env;
        HRESULT hr = CreateCoreWebView2EnvironmentWithOptions(
            nullptr, path.c_str(), nullptr,
            Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
                [params](HRESULT err, ICoreWebView2Environment* e) -> HRESULT {
                    if (FAILED(err)) {
                        params->createResult = err;
                        SetEvent(params->readyEvent);
                        return S_OK;
                    }
                    ComPtr<ICoreWebView2Environment> env(e);
                    params->instance->hwnd = params->instance->hwnd;
                    ComPtr<ICoreWebView2Environment3> env3;
                    HRESULT hrQI = e->QueryInterface(IID_PPV_ARGS(&env3));
                    if (FAILED(hrQI) || !env3) {
                        params->createResult = hrQI;
                        SetEvent(params->readyEvent);
                        return S_OK;
                    }
                    env3->CreateCoreWebView2CompositionController(params->instance->hwnd,
                        Callback<ICoreWebView2CreateCoreWebView2CompositionControllerCompletedHandler>(
                            [params](HRESULT err2, ICoreWebView2CompositionController* compCtrl) -> HRESULT {
                                if (FAILED(err2)) {
                                    params->createResult = err2;
                                    SetEvent(params->readyEvent);
                                    return S_OK;
                                }
                                WebViewInstance* inst = params->instance;
                                inst->compositionController = compCtrl;
                                ComPtr<ICoreWebView2Controller> ctrl;
                                if (SUCCEEDED(compCtrl->QueryInterface(IID_PPV_ARGS(&ctrl)))) {
                                    inst->controller = ctrl;
                                    ctrl->get_CoreWebView2(&inst->webview);
                                }
                                if (!inst->webview) {
                                    params->createResult = E_FAIL;
                                    SetEvent(params->readyEvent);
                                    return S_OK;
                                }
                                inst->rectWidth = params->width;
                                inst->rectHeight = params->height;

                                ComPtr<ICoreWebView2Settings> settings;
                                inst->webview->get_Settings(&settings);
                                if (settings) {
                                    settings->put_IsScriptEnabled(TRUE);
                                    settings->put_AreDefaultScriptDialogsEnabled(TRUE);
                                }

                                // JS → C#: window.Unity.call(msg)는 postMessage로 보내고 WebMessageReceived에서 OnMessageReceived로 바꾼다.
                                std::wstring script = L"window.Unity = { call: function(msg) { window.chrome.webview.postMessage(String(msg)); } };";
                                inst->webview->AddScriptToExecuteOnDocumentCreated(script.c_str(), nullptr);

                                // 웹 콘솔(console.debug/log/info/warn/error)과 잡히지 않은 예외를 OnConsoleMessage로 보낸다(macOS 번들·iOS SDK와 같다).
                                // 같은 postMessage 채널을 쓰므로 kConsolePrefix를 붙여 Unity.call 메시지와 구분한다. 전송 여부는 C#이 거른다.
                                std::wstring consoleScript =
                                    L"(function() {\n"
                                    L"    var bridge = window.chrome && window.chrome.webview;\n"
                                    L"    if (!bridge || window.__goatConsoleInstalled)\n"
                                    L"        return;\n"
                                    L"    window.__goatConsoleInstalled = true;\n"
                                    L"    var sending = false;\n"
                                    L"    function text(value) {\n"
                                    L"        if (typeof value === 'string')\n"
                                    L"            return value;\n"
                                    L"        if (value instanceof Error)\n"
                                    L"            return String(value);\n"
                                    L"        if (value !== null && typeof value === 'object') {\n"
                                    L"            try {\n"
                                    L"                var json = JSON.stringify(value);\n"
                                    L"                if (json !== undefined)\n"
                                    L"                    return json;\n"
                                    L"            } catch (e) {}\n"
                                    L"        }\n"
                                    L"        return String(value);\n"
                                    L"    }\n"
                                    L"    function callerLocation(stack) {\n"
                                    L"        var lines = String(stack || '').split('\\n');\n"
                                    L"        for (var i = 0; i < lines.length; i++) {\n"
                                    L"            var match = lines[i].match(/([a-z][a-z0-9+.-]*:[^@\\s()]*):(\\d+):\\d+\\)?$/i);\n"
                                    L"            if (match)\n"
                                    L"                return { source: match[1], line: Number(match[2]) };\n"
                                    L"        }\n"
                                    L"        return { source: window.location.href, line: 0 };\n"
                                    L"    }\n"
                                    L"    function send(level, message, source, line) {\n"
                                    L"        if (sending)\n"
                                    L"            return;\n"
                                    L"        sending = true;\n"
                                    L"        try {\n"
                                    L"            bridge.postMessage('\\u0001goatConsole:' + JSON.stringify({ level: level, message: message, source: source, line: line }));\n"
                                    L"        } catch (e) {\n"
                                    L"        } finally {\n"
                                    L"            sending = false;\n"
                                    L"        }\n"
                                    L"    }\n"
                                    L"    var levels = { debug: 'DEBUG', log: 'LOG', info: 'LOG', warn: 'WARNING', error: 'ERROR' };\n"
                                    L"    Object.keys(levels).forEach(function(name) {\n"
                                    L"        var original = console[name];\n"
                                    L"        console[name] = function() {\n"
                                    L"            if (typeof original === 'function')\n"
                                    L"                original.apply(console, arguments);\n"
                                    L"            var where = callerLocation(new Error().stack);\n"
                                    L"            send(levels[name], Array.prototype.map.call(arguments, text).join(' '), where.source, where.line);\n"
                                    L"        };\n"
                                    L"    });\n"
                                    L"    window.addEventListener('error', function(event) {\n"
                                    L"        send('ERROR', event.message || text(event.error), event.filename || window.location.href, event.lineno || 0);\n"
                                    L"    });\n"
                                    L"    window.addEventListener('unhandledrejection', function(event) {\n"
                                    L"        send('ERROR', 'Unhandled promise rejection: ' + text(event.reason), window.location.href, 0);\n"
                                    L"    });\n"
                                    L"})();\n";
                                inst->webview->AddScriptToExecuteOnDocumentCreated(consoleScript.c_str(), nullptr);

                                // Message from JS
                                inst->webview->add_WebMessageReceived(
                                    Callback<ICoreWebView2WebMessageReceivedEventHandler>(
                                        [inst](ICoreWebView2* wv, ICoreWebView2WebMessageReceivedEventArgs* args) -> HRESULT {
                                            LPWSTR msgRaw = nullptr;
                                            // 문자열이 아닌 postMessage(객체 등)는 Unity.call 규약이 아니므로 버린다.
                                            if (FAILED(args->TryGetWebMessageAsString(&msgRaw)) || !msgRaw) return S_OK;
                                            std::string s = WideToUtf8(msgRaw);
                                            CoTaskMemFree(msgRaw);
                                            const size_t prefixLen = sizeof(kConsolePrefix) - 1;
                                            if (s.compare(0, prefixLen, kConsolePrefix) == 0) {
                                                // 스크립트가 만든 JSON 객체 본문을 그대로 넘긴다.
                                                std::string json = s.substr(prefixLen);
                                                if (json.size() >= 2 && json.front() == '{' && json.back() == '}')
                                                    PushEvent(inst, "OnConsoleMessage", json.substr(1, json.size() - 2));
                                                return S_OK;
                                            }
                                            LPWSTR sourceRaw = nullptr;
                                            std::string origin;
                                            if (SUCCEEDED(args->get_Source(&sourceRaw)) && sourceRaw) {
                                                origin = OriginOf(WideToUtf8(sourceRaw));
                                                CoTaskMemFree(sourceRaw);
                                            }
                                            PushEvent(inst, "OnMessageReceived",
                                                "\"message\":" + JsonString(s) + ",\"origin\":" + JsonString(origin));
                                            return S_OK;
                                        }).Get(), nullptr);

                                // Navigation events
                                inst->webview->add_NavigationStarting(
                                    Callback<ICoreWebView2NavigationStartingEventHandler>(
                                        [inst](ICoreWebView2* wv, ICoreWebView2NavigationStartingEventArgs* args) -> HRESULT {
                                            inst->progress = 0;
                                            LPWSTR uriRaw = nullptr;
                                            args->get_Uri(&uriRaw);
                                            if (uriRaw) {
                                                PushEvent(inst, "OnPageStarted", "\"url\":" + JsonString(WideToUtf8(uriRaw)));
                                                CoTaskMemFree(uriRaw);
                                            }
                                            return S_OK;
                                        }).Get(), nullptr);

                                inst->webview->add_NavigationCompleted(
                                    Callback<ICoreWebView2NavigationCompletedEventHandler>(
                                        [inst](ICoreWebView2* wv, ICoreWebView2NavigationCompletedEventArgs* args) -> HRESULT {
                                            // Unity posts WM_WEBVIEW_CAPTURE only when captureInProgress is false. If the previous
                                            // CapturePreview callback is late or stuck, no new frame is captured (texture stays stale).
                                            inst->captureInProgress = false;
                                            BOOL success = FALSE;
                                            args->get_IsSuccess(&success);
                                            inst->progress = success ? 100 : 0;
                                            std::string uri = CurrentUrl(wv);
                                            if (success) {
                                                // HTTP 4xx·5xx도 문서 로드는 끝나므로 Android·macOS처럼 OnPageFinished의 statusCode로 알린다.
                                                int statusCode = 200;
                                                ComPtr<ICoreWebView2NavigationCompletedEventArgs2> args2;
                                                if (SUCCEEDED(args->QueryInterface(IID_PPV_ARGS(&args2))) && args2) {
                                                    int http = 0;
                                                    if (SUCCEEDED(args2->get_HttpStatusCode(&http)) && http >= 400) statusCode = http;
                                                }
                                                PushEvent(inst, "OnPageFinished",
                                                    "\"url\":" + JsonString(uri) + ",\"statusCode\":" + std::to_string(statusCode));
                                            } else {
                                                COREWEBVIEW2_WEB_ERROR_STATUS status = COREWEBVIEW2_WEB_ERROR_STATUS_UNKNOWN;
                                                args->get_WebErrorStatus(&status);
                                                PushEvent(inst, "OnLoadingErrorReceived",
                                                    "\"kind\":\"NETWORK\",\"errorCode\":" + std::to_string((int)status) +
                                                    ",\"errorMessage\":" + JsonString(WebErrorStatusName(status)) +
                                                    ",\"url\":" + (uri.empty() ? std::string("null") : JsonString(uri)));
                                            }
                                            BOOL back = FALSE, fwd = FALSE;
                                            wv->get_CanGoBack(&back);
                                            wv->get_CanGoForward(&fwd);
                                            {
                                                std::lock_guard<std::mutex> lk(inst->cacheMutex);
                                                inst->canGoBack = (back != FALSE);
                                                inst->canGoForward = (fwd != FALSE);
                                            }
                                            return S_OK;
                                        }).Get(), nullptr);

                                // Resize and show (composition controller also implements controller)
                                if (inst->controller) {
                                    RECT r = { 0, 0, params->width, params->height };
                                    inst->controller->put_Bounds(r);
                                    inst->controller->put_IsVisible(TRUE);
                                }

                                params->createResult = S_OK;
                                SetEvent(params->readyEvent);
                                return S_OK;
                            }).Get());
                    return S_OK;
                }).Get());

        return 0;
    }
    case WM_WEBVIEW_DESTROY: {
        HANDLE destroyDoneEvent = (HANDLE)lParam;
        if (inst) {
            // Stop navigation before releasing COM to avoid crash when tearing down during load (e.g. heavy pages).
            if (inst->webview) {
                inst->webview->Stop();
            }
            inst->controller = nullptr;
            inst->compositionController = nullptr;
            inst->webview = nullptr;
            if (inst->captureDoneEvent) {
                CloseHandle(inst->captureDoneEvent);
                inst->captureDoneEvent = nullptr;
            }
        }
        if (destroyDoneEvent)
            SetEvent(destroyDoneEvent);
        DestroyWindow(hwnd);
        return 0;
    }
    case WM_WEBVIEW_LOAD_URL: {
        // wParam 0: Stop + queue Navigate on next message pump (Stop is not always synchronous;
        // immediate Navigate on a heavy page can be dropped until user retries).
        // wParam 1: perform Navigate and free url.
        wchar_t* url = (wchar_t*)lParam;
        if (!inst || !inst->webview) {
            delete[] url;
            return 0;
        }
        if (wParam == 0) {
            inst->webview->Stop();
            inst->captureInProgress = false;
            PostMessage(hwnd, WM_WEBVIEW_LOAD_URL, 1, (LPARAM)url);
        } else {
            inst->webview->Navigate(url);
            delete[] url;
        }
        return 0;
    }
    case WM_WEBVIEW_LOAD_HTML: {
        if (inst && inst->webview) {
            wchar_t* html = (wchar_t*)wParam;
            wchar_t* baseUrl = (wchar_t*)lParam;
            inst->webview->Stop();
            inst->captureInProgress = false;
            inst->webview->NavigateToString(html);
            delete[] html;
            if (baseUrl) delete[] baseUrl;
        }
        return 0;
    }
    case WM_WEBVIEW_EVAL_JS: {
        wchar_t* js = (wchar_t*)lParam;
        if (inst && inst->webview) {
            inst->webview->ExecuteScript(js, Callback<ICoreWebView2ExecuteScriptCompletedHandler>(
                [](HRESULT err, LPCWSTR result) -> HRESULT { return S_OK; }).Get());
        }
        delete[] js;
        return 0;
    }
    case WM_WEBVIEW_EVAL_JS_RESULT: {
        EvalData* data = (EvalData*)lParam;
        if (!data) return 0;
        if (inst && inst->webview) {
            int requestId = data->requestId;
            inst->webview->ExecuteScript(data->js, Callback<ICoreWebView2ExecuteScriptCompletedHandler>(
                [inst, requestId](HRESULT err, LPCWSTR result) -> HRESULT {
                    // macOS 번들처럼 결과를 JSON 문자열로 보내고, 오류·undefined·null은 null로 보낸다. WebView2의 result는 이미 JSON이다.
                    std::string value = (FAILED(err) || !result) ? std::string("null") : WideToUtf8(result);
                    std::string field = (value == "null") ? std::string("null") : JsonString(value);
                    PushEvent(inst, "OnJavaScriptResult", "\"requestId\":" + std::to_string(requestId) + ",\"result\":" + field);
                    return S_OK;
                }).Get());
        }
        delete[] data->js;
        delete data;
        return 0;
    }
    case WM_WEBVIEW_SET_RECT: {
        if (inst && inst->controller) {
            int w = (int)wParam;
            int h = (int)lParam;
            inst->rectWidth = w;
            inst->rectHeight = h;
            RECT r = { 0, 0, w, h };
            inst->controller->put_Bounds(r);
        }
        return 0;
    }
    case WM_WEBVIEW_SET_VISIBILITY: {
        if (inst && inst->controller) {
            inst->visible = (wParam != 0);
            inst->controller->put_IsVisible(inst->visible ? TRUE : FALSE);
        }
        return 0;
    }
    case WM_WEBVIEW_GO_BACK:
        if (inst && inst->webview) inst->webview->GoBack();
        return 0;
    case WM_WEBVIEW_GO_FORWARD:
        if (inst && inst->webview) inst->webview->GoForward();
        return 0;
    case WM_WEBVIEW_RELOAD:
        if (inst && inst->webview) inst->webview->Reload();
        return 0;
    case WM_WEBVIEW_CAPTURE: {
        if (!inst || !inst->webview) {
            if (inst && inst->captureDoneEvent) SetEvent(inst->captureDoneEvent);
            if (inst) inst->captureInProgress = false;
            return 0;
        }
        HANDLE doneEv = (HANDLE)lParam;
        inst->captureDoneEvent = doneEv;

        ComPtr<IStream> stream;
        CreateStreamOnHGlobal(nullptr, TRUE, &stream);
        ComPtr<IStream> streamRef = stream;

        HRESULT errCapture = inst->webview->CapturePreview(COREWEBVIEW2_CAPTURE_PREVIEW_IMAGE_FORMAT_PNG, stream.Get(),
            Callback<ICoreWebView2CapturePreviewCompletedHandler>(
                [inst, streamRef](HRESULT err) -> HRESULT {
                    if (SUCCEEDED(err) && streamRef) {
                        LARGE_INTEGER zero = { 0 };
                        streamRef->Seek(zero, STREAM_SEEK_SET, nullptr);
                        std::vector<uint8_t> pixels;
                        int w, h;
                        if (DecodePngStreamToRgba(streamRef.Get(), pixels, w, h)) {
                            std::lock_guard<std::mutex> lk(inst->bitmapMutex);
                            inst->bitmapPixelsBack = std::move(pixels);
                            inst->bitmapWidthBack = w;
                            inst->bitmapHeightBack = h;
                            inst->bitmapPixels.swap(inst->bitmapPixelsBack);
                            inst->bitmapWidth = inst->bitmapWidthBack;
                            inst->bitmapHeight = inst->bitmapHeightBack;
                        }
                    }
                    if (inst->captureDoneEvent) SetEvent(inst->captureDoneEvent);
                    inst->captureInProgress = false;
                    return S_OK;
                }).Get());
	if (FAILED(errCapture)) {
	    if (inst->captureDoneEvent) SetEvent(inst->captureDoneEvent);
	    inst->captureInProgress = false;
	}
        return 0;
    }
    case WM_WEBVIEW_SEND_MOUSE: {
        MouseEventData* data = (MouseEventData*)lParam;
        if (!inst || !data) return 0;
        int winX = data->x;
        int winY = inst->rectHeight > 0 ? (inst->rectHeight - 1 - data->y) : data->y;
        winX = (winX < 0) ? 0 : (winX >= inst->rectWidth ? inst->rectWidth - 1 : winX);
        winY = (winY < 0) ? 0 : (winY >= inst->rectHeight ? inst->rectHeight - 1 : winY);
        POINT pt = { winX, winY };
        COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS vk = COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS_NONE;
        if (data->mouseState == 2 || data->mouseState == 1) vk = COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS_LEFT_BUTTON;

        if (inst->compositionController) {
            WV_LOG("MOUSE SendMouseInput: x=%d y=%d state=%d", winX, winY, data->mouseState);
            inst->compositionController->SendMouseInput(COREWEBVIEW2_MOUSE_EVENT_KIND_MOVE, vk, 0, pt);
            if (data->mouseState == 1)
                inst->compositionController->SendMouseInput(COREWEBVIEW2_MOUSE_EVENT_KIND_LEFT_BUTTON_DOWN, COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS_LEFT_BUTTON, 0, pt);
            else if (data->mouseState == 3)
                inst->compositionController->SendMouseInput(COREWEBVIEW2_MOUSE_EVENT_KIND_LEFT_BUTTON_UP, COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS_NONE, 0, pt);
            if (data->deltaY != 0.f) {
                int wheelData = (int)(data->deltaY * 120);
                if (wheelData == 0 && data->deltaY != 0.f) wheelData = data->deltaY > 0 ? 120 : -120;
                inst->compositionController->SendMouseInput(COREWEBVIEW2_MOUSE_EVENT_KIND_WHEEL, COREWEBVIEW2_MOUSE_EVENT_VIRTUAL_KEYS_NONE, (UINT32)wheelData, pt);
            }
        } else {
            HWND target = GetWindow(hwnd, GW_CHILD);
            if (!target) target = hwnd;
            LPARAM lParamPos = MAKELPARAM(winX, winY);
            // Do not SetFocus(target) here: it would activate the off-screen host and cause the Unity window to lose focus (e.g. minimize).
            SendMessage(target, WM_MOUSEMOVE, 0, lParamPos);
            if (data->mouseState == 1) SendMessage(target, WM_LBUTTONDOWN, MK_LBUTTON, lParamPos);
            else if (data->mouseState == 2) SendMessage(target, WM_MOUSEMOVE, MK_LBUTTON, lParamPos);
            else if (data->mouseState == 3) SendMessage(target, WM_LBUTTONUP, 0, lParamPos);
            if (data->deltaY != 0.f) {
                short wheel = (short)(data->deltaY * 120);
                if (wheel == 0 && data->deltaY != 0.f) wheel = data->deltaY > 0 ? 120 : -120;
                POINT ptScreen = { winX, winY };
                ClientToScreen(target, &ptScreen);
                SendMessage(target, WM_MOUSEWHEEL, MAKEWPARAM(0, wheel), MAKELPARAM(ptScreen.x, ptScreen.y));
            }
        }
        delete data;
        return 0;
    }
    case WM_WEBVIEW_SEND_KEY: {
        KeyEventData* data = (KeyEventData*)lParam;
        if (!inst || !data) return 0;
        HWND child = GetWindow(hwnd, GW_CHILD);
        HWND target = child ? child : hwnd;
        WV_LOG("KEY recv: hwnd=%p child=%p target=%p keyCode=%u keyState=%d hasChars=%d",
               (void*)hwnd, (void*)child, (void*)target, (unsigned)data->keyCode, data->keyState,
               data->keyChars && data->keyChars[0] ? 1 : 0);
        HWND fg = GetForegroundWindow();
        SetFocus(target);
        if (data->keyChars && data->keyChars[0]) {
            WCHAR wch[32];
            int n = MultiByteToWideChar(CP_UTF8, 0, data->keyChars, -1, wch, 32);
            if (n > 0) {
                for (int i = 0; wch[i]; i++) {
                    SendMessage(target, WM_CHAR, (WPARAM)wch[i], 0);
                }
            }
        }
        if (data->keyCode != 0) {
            LPARAM lp = 1 | (LPARAM)MapVirtualKeyW(data->keyCode, MAPVK_VK_TO_VSC) << 16;
            if (data->keyState == 1 || data->keyState == 2)
                SendMessage(target, WM_KEYDOWN, (WPARAM)data->keyCode, lp);
            if (data->keyState == 3)
                SendMessage(target, WM_KEYUP, (WPARAM)data->keyCode, lp);
        }
        if (fg && fg != hwnd)
            SetForegroundWindow(fg);
        delete[] data->keyChars;
        delete data;
        return 0;
    }
    case WM_DESTROY:
        PostQuitMessage(0);
        return 0;
    default:
        return DefWindowProc(hwnd, msg, wParam, lParam);
    }
    return DefWindowProc(hwnd, msg, wParam, lParam);
}

// Run message loop until WebView2 is created (used in Init)
static DWORD WINAPI STAThreadProc(LPVOID param) {
    CreateParams* params = (CreateParams*)param;
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

    WNDCLASSEXW wc = {};
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = WndProc;
    wc.hInstance = GetModuleHandle(nullptr);
    wc.lpszClassName = L"UnityWebView2Window";
    RegisterClassExW(&wc);

    // Use WS_EX_TOOLWINDOW and WS_POPUP to prevent the window from appearing on the taskbar.
    // WS_EX_NOACTIVATE prevents the host from being activated when receiving focus, avoiding Unity window minimize on click.
    HWND hwnd = CreateWindowExW(WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE, wc.lpszClassName, L"", WS_POPUP,
        0, 0, params->width > 0 ? params->width : 640, params->height > 0 ? params->height : 480,
        nullptr, nullptr, wc.hInstance, params);
    if (!hwnd) {
        params->createResult = E_FAIL;
        SetEvent(params->readyEvent);
        CoUninitialize();
        return 1;
    }

    // Visible but off-screen so WebView2 child receives input; fully hidden windows may ignore mouse/key.
    SetWindowPos(hwnd, nullptr, -32000, -32000, 0, 0, SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
    ShowWindow(hwnd, SW_SHOWNOACTIVATE);

    MSG msg;
    while (GetMessage(&msg, nullptr, 0, 0)) {
        if (msg.message == WM_WEBVIEW_CREATE) {
            // already handled in WM_CREATE
        }
        TranslateMessage(&msg);
        DispatchMessage(&msg);
    }

    UnregisterClassW(wc.lpszClassName, wc.hInstance);
    CoUninitialize();
    return 0;
}

// Helper: run action on STA thread by posting to the instance's window
static void PostToInstance(WebViewInstance* inst, UINT msg, WPARAM wParam = 0, LPARAM lParam = 0) {
    if (inst && !inst->destroying && inst->hwnd)
        PostMessage(inst->hwnd, msg, wParam, lParam);
}

static void PostToInstanceAndWait(WebViewInstance* inst, UINT msg, WPARAM wParam, LPARAM lParam, HANDLE eventToSignal) {
    if (!inst || !inst->hwnd) {
        if (eventToSignal) SetEvent(eventToSignal);
        return;
    }
    PostMessage(inst->hwnd, msg, wParam, lParam);
    if (eventToSignal)
        WaitForSingleObject(eventToSignal, 10000);
}

//------------------------------------------------------------------------------
// C API (exported; match Mac signatures for Unity DllImport)
//------------------------------------------------------------------------------
extern "C" {

__declspec(dllexport) const char* _CWebViewPlugin_GetAppPath(void) {
    static std::string path;
    if (path.empty()) {
        char buf[MAX_PATH] = "";
        GetModuleFileNameA(nullptr, buf, MAX_PATH);
        std::string p(buf);
        size_t last = p.find_last_of("\\/");
        if (last != std::string::npos) p = p.substr(0, last);
        path = p;
    }
    return path.c_str();
}

__declspec(dllexport) void _CWebViewPlugin_InitStatic(bool inEditor, bool useMetal) {
    (void)inEditor;
    (void)useMetal;
}

__declspec(dllexport) bool _CWebViewPlugin_IsInitialized(void* instance) {
    WebViewInstance* inst = (WebViewInstance*)instance;
    return inst && !inst->destroying && inst->webview != nullptr;
}

__declspec(dllexport) void* _CWebViewPlugin_Init(
    const char* gameObject, bool transparent, bool zoom, int width, int height, const char* ua, bool separated)
{
    (void)transparent;
    (void)separated;
    if (!gameObject) return nullptr;

    auto inst = std::make_unique<WebViewInstance>();
    inst->rectWidth = width > 0 ? width : 640;
    inst->rectHeight = height > 0 ? height : 480;
    inst->captureDoneEvent = CreateEvent(nullptr, FALSE, FALSE, nullptr);

    CreateParams params;
    params.instance = inst.get();
    params.width = inst->rectWidth;
    params.height = inst->rectHeight;
    params.zoom = zoom;
    params.readyEvent = CreateEvent(nullptr, FALSE, FALSE, nullptr);
    if (ua && ua[0]) {
        int n = MultiByteToWideChar(CP_UTF8, 0, ua, -1, nullptr, 0);
        params.userAgent.resize(n);
        MultiByteToWideChar(CP_UTF8, 0, ua, -1, &params.userAgent[0], n);
    }

    HANDLE thread = CreateThread(nullptr, 0, STAThreadProc, &params, 0, nullptr);
    if (!thread) {
        CloseHandle(params.readyEvent);
        return nullptr;
    }
    // Reduce from 30s to 10s so slow/failed WebView2 init does not freeze the app as long
    const DWORD kInitTimeoutMs = 10000;
    WaitForSingleObject(params.readyEvent, kInitTimeoutMs);
    CloseHandle(params.readyEvent);

    if (params.createResult != S_OK) {
        WaitForSingleObject(thread, 5000);
        CloseHandle(thread);
        return nullptr;
    }

    // STA thread is now running its message loop; we keep it alive
    CloseHandle(thread);

    WebViewInstance* raw = inst.get();
    std::lock_guard<std::mutex> lk(s_instancesMutex);
    s_instances.push_back(std::move(inst));
    return raw;
}

__declspec(dllexport) int _CWebViewPlugin_Destroy(void* instance) {
    WebViewInstance* inst = (WebViewInstance*)instance;
    if (!inst) return 0;
    inst->destroying = true;
    // Wait for STA thread to finish cleanup before erasing instance (avoids use-after-free / intermittent crash on exit)
    HANDLE destroyDoneEvent = CreateEventW(nullptr, FALSE, FALSE, nullptr);
    DWORD waitResult = WAIT_TIMEOUT;
    if (destroyDoneEvent && inst->hwnd) {
        PostMessage(inst->hwnd, WM_WEBVIEW_DESTROY, 0, (LPARAM)destroyDoneEvent);
        waitResult = WaitForSingleObject(destroyDoneEvent, 10000);
    }
    if (destroyDoneEvent)
        CloseHandle(destroyDoneEvent);
    // Only erase from s_instances if STA thread completed cleanup. On timeout the STA thread may still
    // be blocked (e.g. in WebView2 on a heavy page); erasing here would run WebViewInstance destructor
    // on the main thread and release COM objects from wrong thread -> crash in EmbeddedBrowserWebView.
    if (waitResult == WAIT_OBJECT_0) {
        std::lock_guard<std::mutex> lk(s_instancesMutex);
        for (auto it = s_instances.begin(); it != s_instances.end(); ++it) {
            if (it->get() == inst) {
                s_instances.erase(it);
                break;
            }
        }
    }
    return 1;
}

__declspec(dllexport) void _CWebViewPlugin_SetRect(void* instance, int width, int height) {
    PostToInstance((WebViewInstance*)instance, WM_WEBVIEW_SET_RECT, (WPARAM)width, (LPARAM)height);
}

__declspec(dllexport) void _CWebViewPlugin_SetVisibility(void* instance, bool visibility) {
    PostToInstance((WebViewInstance*)instance, WM_WEBVIEW_SET_VISIBILITY, visibility ? 1 : 0, 0);
}

__declspec(dllexport) bool _CWebViewPlugin_SetURLPattern(void* instance, const char* allowPattern, const char* denyPattern, const char* hookPattern) {
    (void)instance;
    (void)allowPattern;
    (void)denyPattern;
    (void)hookPattern;
    return true;
}

__declspec(dllexport) void _CWebViewPlugin_LoadURL(void* instance, const char* url) {
    WebViewInstance* inst = (WebViewInstance*)instance;
    if (!inst || inst->destroying || !url) return;
    int n = MultiByteToWideChar(CP_UTF8, 0, url, -1, nullptr, 0);
    wchar_t* w = new wchar_t[n];
    MultiByteToWideChar(CP_UTF8, 0, url, -1, w, n);
    PostMessage(inst->hwnd, WM_WEBVIEW_LOAD_URL, 0, (LPARAM)w);
}

__declspec(dllexport) void _CWebViewPlugin_LoadHTML(void* instance, const char* html, const char* baseUrl) {
    WebViewInstance* inst = (WebViewInstance*)instance;
    if (!inst || inst->destroying || !html) return;
    int n = MultiByteToWideChar(CP_UTF8, 0, html, -1, nullptr, 0);
    wchar_t* wHtml = new wchar_t[n];
    MultiByteToWideChar(CP_UTF8, 0, html, -1, wHtml, n);
    wchar_t* wBase = nullptr;
    if (baseUrl && baseUrl[0]) {
        int nb = MultiByteToWideChar(CP_UTF8, 0, baseUrl, -1, nullptr, 0);
        wBase = new wchar_t[nb];
        MultiByteToWideChar(CP_UTF8, 0, baseUrl, -1, wBase, nb);
    }
    PostMessage(inst->hwnd, WM_WEBVIEW_LOAD_HTML, (WPARAM)wHtml, (LPARAM)wBase);
}

__declspec(dllexport) void _CWebViewPlugin_EvaluateJS(void* instance, const char* js) {
    WebViewInstance* inst = (WebViewInstance*)instance;
    if (!inst || inst->destroying || !js) return;
    PostMessage(inst->hwnd, WM_WEBVIEW_EVAL_JS, 0, (LPARAM)Utf8ToWideAlloc(js));
}

// 결과를 OnJavaScriptResult{requestId,result}로 돌려준다(macOS 번들·iOS·Android 래퍼와 같다).
__declspec(dllexport) void _CWebViewPlugin_EvaluateJavaScript(void* instance, int requestId, const char* js) {
    WebViewInstance* inst = (WebViewInstance*)instance;
    if (!inst || inst->destroying || !js) return;
    EvalData* data = new EvalData{ requestId, Utf8ToWideAlloc(js) };
    PostMessage(inst->hwnd, WM_WEBVIEW_EVAL_JS_RESULT, 0, (LPARAM)data);
}

// Returns load progress 0-100. Value is 0 when navigation starts, 100 when it completes successfully, and 0 on failure.
// Using Progress() == 100 to detect "load complete" is correct. For a smooth progress bar, note that WebView2 does
// not expose an estimatedProgress like WKWebView; we only get two states (0 and 100), so the progress is not gradual.
__declspec(dllexport) int _CWebViewPlugin_Progress(void* instance) {
    WebViewInstance* inst = (WebViewInstance*)instance;
    if (!inst || inst->destroying) return 0;
    return inst->progress.load();
}

__declspec(dllexport) bool _CWebViewPlugin_CanGoBack(void* instance) {
    WebViewInstance* inst = (WebViewInstance*)instance;
    if (!inst || inst->destroying) return false;
    std::lock_guard<std::mutex> lk(inst->cacheMutex);
    return inst->canGoBack;
}

__declspec(dllexport) bool _CWebViewPlugin_CanGoForward(void* instance) {
    WebViewInstance* inst = (WebViewInstance*)instance;
    if (!inst || inst->destroying) return false;
    std::lock_guard<std::mutex> lk(inst->cacheMutex);
    return inst->canGoForward;
}

__declspec(dllexport) void _CWebViewPlugin_GoBack(void* instance) {
    PostToInstance((WebViewInstance*)instance, WM_WEBVIEW_GO_BACK);
}

__declspec(dllexport) void _CWebViewPlugin_GoForward(void* instance) {
    PostToInstance((WebViewInstance*)instance, WM_WEBVIEW_GO_FORWARD);
}

__declspec(dllexport) void _CWebViewPlugin_Reload(void* instance) {
    PostToInstance((WebViewInstance*)instance, WM_WEBVIEW_RELOAD);
}

__declspec(dllexport) void _CWebViewPlugin_SendMouseEvent(void* instance, int x, int y, float deltaY, int mouseState) {
    WebViewInstance* inst = (WebViewInstance*)instance;
    WV_LOG("SendMouseEvent called: inst=%p hwnd=%s x=%d y=%d state=%d", (void*)inst, inst && inst->hwnd ? "ok" : "null", x, y, mouseState);
    if (!inst || inst->destroying || !inst->hwnd) return;
    MouseEventData* data = new MouseEventData{ x, y, deltaY, mouseState };
    PostMessage(inst->hwnd, WM_WEBVIEW_SEND_MOUSE, 0, (LPARAM)data);
}

__declspec(dllexport) void _CWebViewPlugin_SendKeyEvent(void* instance, int x, int y, char* keyChars, unsigned short keyCode, int keyState) {
    WebViewInstance* inst = (WebViewInstance*)instance;
    WV_LOG("SendKeyEvent called: inst=%p hwnd=%s keyCode=%u keyState=%d", (void*)inst, inst && inst->hwnd ? "ok" : "null", (unsigned)keyCode, keyState);
    if (!inst || inst->destroying || !inst->hwnd) return;
    KeyEventData* data = new KeyEventData();
    data->keyCode = keyCode;
    data->keyState = keyState;
    data->keyChars = nullptr;
    if (keyChars && keyChars[0]) {
        size_t len = strlen(keyChars) + 1;
        data->keyChars = new char[len];
        memcpy(data->keyChars, keyChars, len);
    }
    PostMessage(inst->hwnd, WM_WEBVIEW_SEND_KEY, 0, (LPARAM)data);
}

__declspec(dllexport) void _CWebViewPlugin_Update(void* instance, bool refreshBitmap, int devicePixelRatio) {
    WebViewInstance* inst = (WebViewInstance*)instance;
    if (!inst || inst->destroying) return;
    // Non-blocking: only start a new capture when none is in progress. STA thread uses
    // double-buffering so Render() can read current bitmapPixels without delay.
    if (refreshBitmap && inst->hwnd && inst->webview) {
        if (!inst->captureInProgress.exchange(true)) {
            PostMessage(inst->hwnd, WM_WEBVIEW_CAPTURE, 0, 0);
        }
    }
}

__declspec(dllexport) int _CWebViewPlugin_BitmapWidth(void* instance) {
    WebViewInstance* inst = (WebViewInstance*)instance;
    if (!inst || inst->destroying) return 0;
    std::lock_guard<std::mutex> lk(inst->bitmapMutex);
    return inst->bitmapWidth;
}

__declspec(dllexport) int _CWebViewPlugin_BitmapHeight(void* instance) {
    WebViewInstance* inst = (WebViewInstance*)instance;
    if (!inst || inst->destroying) return 0;
    std::lock_guard<std::mutex> lk(inst->bitmapMutex);
    return inst->bitmapHeight;
}

// 비트맵은 항상 RGBA다(macOS 번들의 ARGB 구분과 같은 함수).
__declspec(dllexport) bool _CWebViewPlugin_BitmapARGB(void* instance) {
    (void)instance;
    return false;
}

__declspec(dllexport) void _CWebViewPlugin_Render(void* instance, void* textureBuffer) {
    WebViewInstance* inst = (WebViewInstance*)instance;
    if (!inst || inst->destroying || !textureBuffer) return;
    std::lock_guard<std::mutex> lk(inst->bitmapMutex);
    if (inst->bitmapPixels.empty()) return;
    size_t copyLen = (size_t)inst->bitmapWidth * inst->bitmapHeight * 4;
    if (copyLen > inst->bitmapPixels.size()) copyLen = inst->bitmapPixels.size();
    memcpy(textureBuffer, inst->bitmapPixels.data(), copyLen);
}

__declspec(dllexport) void _CWebViewPlugin_AddCustomHeader(void* instance, const char* headerKey, const char* headerValue) {
    (void)instance;
    (void)headerKey;
    (void)headerValue;
}

__declspec(dllexport) const char* _CWebViewPlugin_GetCustomHeaderValue(void* instance, const char* headerKey) {
    (void)instance;
    (void)headerKey;
    return nullptr;
}

__declspec(dllexport) void _CWebViewPlugin_RemoveCustomHeader(void* instance, const char* headerKey) {
    (void)instance;
    (void)headerKey;
}

__declspec(dllexport) void _CWebViewPlugin_ClearCustomHeader(void* instance) {
    (void)instance;
}

__declspec(dllexport) void _CWebViewPlugin_ClearCookie(const char* url, const char* name) {
    (void)url;
    (void)name;
}

__declspec(dllexport) void _CWebViewPlugin_ClearCookies(void) {
}

__declspec(dllexport) void _CWebViewPlugin_SaveCookies(void) {
}

__declspec(dllexport) void _CWebViewPlugin_GetCookies(void* instance, const char* url) {
    (void)instance;
    (void)url;
}

__declspec(dllexport) const char* _CWebViewPlugin_GetMessage(void* instance) {
    WebViewInstance* inst = (WebViewInstance*)instance;
    if (!inst || inst->destroying) return nullptr;
    std::string msg;
    if (!inst->messages.pop(msg)) return nullptr;
    char* buf = (char*)CoTaskMemAlloc(msg.size() + 1);
    if (!buf) return nullptr;
    memcpy(buf, msg.c_str(), msg.size() + 1);
    return buf;
}

} // extern "C"
