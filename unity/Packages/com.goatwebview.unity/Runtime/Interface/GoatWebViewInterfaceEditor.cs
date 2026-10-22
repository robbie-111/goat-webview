#if UNITY_EDITOR_OSX || UNITY_EDITOR_WIN
using System.Collections.Generic;
using UnityEngine;

/// <summary>
/// macOS·Windows Editor 확인용: GameObject마다 <see cref="GoatWebViewEditorView"/>를 붙여 <c>GoatWebView.bundle</c>(macOS) 또는 <c>GoatWebViewPlugin.dll</c>(Windows)을 부른다.
/// 그리기·입력·메시지 꺼내기는 GoatWebViewEditorView가 맡는다.
/// 웹 콘솔은 플러그인이 OnConsoleMessage로 보내며 켜기 전에는 GoatWebViewEditorView가 버린다.
/// 출처 검사, 폴백 테마, 상단 safe area 색, 모서리 라운딩, 바운스, 캐시 비우기, Stop, 일시정지는 Editor에서 지원하지 않아 무시한다.
/// </summary>
internal static class NativeInterface
{
    static readonly Dictionary<string, GoatWebViewEditorView> views = new Dictionary<string, GoatWebViewEditorView>();

    public static bool Create(GoatWebViewListener listener)
    {
        var name = listener.gameObject.name;
        if (views.TryGetValue(name, out var existing) && existing != null) return false;
        var view = listener.gameObject.AddComponent<GoatWebViewEditorView>();
        view.hideFlags = HideFlags.HideInInspector;
        views[name] = view;
        return true;
    }

    public static void Destroy(string name)
    {
        if (!views.TryGetValue(name, out var view)) return;
        views.Remove(name);
        if (view != null) Object.Destroy(view);
    }

    public static void SetAllowedMessageOrigins(string name, string[] origins) { }
    public static void Load(string name, string url) => View(name)?.LoadUrl(url);
    public static void LoadHTML(string name, string html, string baseUrl) => View(name)?.LoadHtml(html, baseUrl);
    public static void EvaluateJavaScript(string name, int requestId, string js) => View(name)?.EvaluateJavaScript(requestId, js);
    public static void GoBack(string name) => View(name)?.GoBack();
    public static void GoForward(string name) => View(name)?.GoForward();
    public static void Reload(string name) => View(name)?.Reload();
    public static void Stop(string name) { }
    public static bool CanGoBack(string name) => View(name)?.CanGoBack ?? false;
    public static bool CanGoForward(string name) => View(name)?.CanGoForward ?? false;
    public static string GetUrl(string name) => View(name)?.Url;
    public static void SetVisibility(string name, bool visible) => View(name)?.SetVisible(visible);
    public static void SetFrame(string name, float x, float y, float width, float height) => View(name)?.SetFrame(new Rect(x, y, width, height));
    public static void SetFallbackTheme(string name, int theme) { }
    public static void SetStatusBarColor(string name, float r, float g, float b, float a) { }
    public static void SetRoundCornerRadius(string name, float topLeft, float topRight, float bottomLeft, float bottomRight) { }
    public static void SetBouncesEnabled(string name, bool enabled) { }
    public static void SetPaused(string name, bool paused) { }
    public static void ClearCache() { }
    public static void SetConsoleMessageEnabled(string name, bool enabled) => View(name)?.SetConsoleMessageEnabled(enabled);

    static GoatWebViewEditorView View(string name) => views.TryGetValue(name, out var view) && view != null ? view : null;
}
#endif
