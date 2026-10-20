#if !UNITY_EDITOR_OSX && !((UNITY_ANDROID || UNITY_IOS) && !UNITY_EDITOR)
using UnityEngine;

/// <summary>
/// 지원하지 않는 플랫폼(Windows Editor, Standalone 등). 처음 한 번만 경고를 남기고 아무것도 하지 않는다.
/// </summary>
internal static class NativeInterface
{
    static bool warned;

    public static bool Create(GoatWebViewListener listener)
    {
        if (!warned)
        {
            warned = true;
            Debug.LogWarning("[GoatWebView] 이 플랫폼에서는 웹뷰를 표시하지 않습니다. Android·iOS 기기나 macOS Editor에서 실행하세요.");
        }
        return true;
    }

    public static void Destroy(string name) { }
    public static void SetAllowedMessageOrigins(string name, string[] origins) { }
    public static void Load(string name, string url) { }
    public static void LoadHTML(string name, string html, string baseUrl) { }
    public static void EvaluateJavaScript(string name, int requestId, string js) { }
    public static void GoBack(string name) { }
    public static void GoForward(string name) { }
    public static void Reload(string name) { }
    public static void Stop(string name) { }
    public static bool CanGoBack(string name) => false;
    public static bool CanGoForward(string name) => false;
    public static string GetUrl(string name) => null;
    public static void SetVisibility(string name, bool visible) { }
    public static void SetFrame(string name, float x, float y, float width, float height) { }
    public static void SetTopSafeAreaColor(string name, float r, float g, float b, float a) { }
    public static void SetRoundCornerRadius(string name, float topLeft, float topRight, float bottomLeft, float bottomRight) { }
    public static void SetBouncesEnabled(string name, bool enabled) { }
    public static void SetPaused(string name, bool paused) { }
    public static void ClearCache() { }
    public static void SetForwardWebConsoleToNativeOutput(bool enabled) { }
}
#endif
