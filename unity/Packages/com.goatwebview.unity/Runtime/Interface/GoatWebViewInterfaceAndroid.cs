#if UNITY_ANDROID && !UNITY_EDITOR
using System.Collections.Generic;
using UnityEngine;

/// <summary>
/// Android: unity-wrapper의 <c>com.goatwebview.unity.UnityWebView</c>를 GameObject 이름마다 하나씩 만들어 부른다.
/// 이벤트는 래퍼가 같은 이름의 GameObject로 UnitySendMessage를 보내 <see cref="GoatWebViewListener"/>가 받는다.
/// 래퍼 메서드는 어느 스레드에서 불러도 메인 루퍼에서 순서대로 실행된다.
/// </summary>
internal static class NativeInterface
{
    const string ClassName = "com.goatwebview.unity.UnityWebView";
    static readonly Dictionary<string, AndroidJavaObject> webViews = new Dictionary<string, AndroidJavaObject>();

    public static bool Create(GoatWebViewListener listener)
    {
        var name = listener.gameObject.name;
        if (webViews.ContainsKey(name)) return false;
        try
        {
            webViews[name] = new AndroidJavaObject(ClassName, name);
            return true;
        }
        catch (AndroidJavaException e)
        {
            Debug.LogError($"[GoatWebView] 웹뷰를 만들지 못했습니다: {e.Message}");
            return false;
        }
    }

    public static void Destroy(string name)
    {
        if (!webViews.TryGetValue(name, out var webView)) return;
        webViews.Remove(name);
        webView.Call("destroy");
        webView.Dispose();
    }

    // 배열을 params 인자 하나로 넘기도록 object로 감싼다. 잘못된 출처는 래퍼가 INVALID_CALL로 알린다.
    public static void SetAllowedMessageOrigins(string name, string[] origins) => Call(name, "setAllowedMessageOrigins", (object)origins);
    public static void Load(string name, string url) => Call(name, "loadUrl", url);
    public static void LoadHTML(string name, string html, string baseUrl) => Call(name, "loadHtml", html, baseUrl);
    public static void EvaluateJavaScript(string name, int requestId, string js) => Call(name, "evaluateJavaScript", requestId, js);
    public static void GoBack(string name) => Call(name, "goBack");
    public static void GoForward(string name) => Call(name, "goForward");
    public static void Reload(string name) => Call(name, "reload");
    public static void Stop(string name) => Call(name, "stopLoading");
    public static bool CanGoBack(string name) => webViews.TryGetValue(name, out var webView) && webView.Call<bool>("canGoBack");
    public static bool CanGoForward(string name) => webViews.TryGetValue(name, out var webView) && webView.Call<bool>("canGoForward");
    public static string GetUrl(string name) => webViews.TryGetValue(name, out var webView) ? webView.Call<string>("getUrl") : null;
    public static void SetVisibility(string name, bool visible) => Call(name, "setVisible", visible);
    public static void SetFrame(string name, float x, float y, float width, float height) => Call(name, "setFrame", x, y, width, height);
    public static void SetFallbackTheme(string name, int theme) => Call(name, "setFallbackTheme", theme);
    public static void SetStatusBarColor(string name, float r, float g, float b, float a) => Call(name, "setStatusBarColor", r, g, b, a);

    public static void SetRoundCornerRadius(string name, float topLeft, float topRight, float bottomLeft, float bottomRight) =>
        Call(name, "setRoundCornerRadius", topLeft, topRight, bottomLeft, bottomRight);

    public static void SetBouncesEnabled(string name, bool enabled) => Call(name, "setScrollBounceEnabled", enabled);
    public static void SetConsoleMessageEnabled(string name, bool enabled) => Call(name, "setConsoleMessageEnabled", enabled);
    public static void SetPaused(string name, bool paused) => Call(name, paused ? "pause" : "resume");

    public static void ClearCache()
    {
        using (var cls = new AndroidJavaClass(ClassName)) cls.CallStatic("clearSharedCache");
    }

    static void Call(string name, string method, params object[] args)
    {
        if (webViews.TryGetValue(name, out var webView)) webView.Call(method, args);
    }
}
#endif
