#if UNITY_IOS && !UNITY_EDITOR
using System;
using System.Runtime.InteropServices;

/// <summary>
/// iOS: GoatUnityWrapper.swift의 <c>goatwebview_*</c> C 함수를 부른다. 래퍼가 GameObject 이름으로 웹뷰를 구분하고
/// 같은 이름의 GameObject로 UnitySendMessage를 보내 <see cref="GoatWebViewListener"/>가 받는다.
/// 모든 호출은 Unity 메인 스레드(iOS 메인 스레드)에서 해야 한다.
/// </summary>
internal static class NativeInterface
{
    public static bool Create(GoatWebViewListener listener) => goatwebview_create(listener.gameObject.name);

    // 허용 출처는 \n으로 잇는다. 빈 문자열은 메시지 브리지를 내린다(Android와 같다).
    public static void SetAllowedMessageOrigins(string name, string[] origins) =>
        goatwebview_set_allowed_message_origins(name, string.Join("\n", origins));

    public static void Destroy(string name) => goatwebview_dispose(name);
    public static void Load(string name, string url) => goatwebview_load_url(name, url);
    public static void LoadHTML(string name, string html, string baseUrl) => goatwebview_load_html(name, html, baseUrl);
    public static void EvaluateJavaScript(string name, int requestId, string js) => goatwebview_evaluate_js(name, requestId, js);
    public static void GoBack(string name) => goatwebview_go_back(name);
    public static void GoForward(string name) => goatwebview_go_forward(name);
    public static void Reload(string name) => goatwebview_reload(name);
    public static void Stop(string name) => goatwebview_stop_loading(name);
    public static bool CanGoBack(string name) => goatwebview_can_go_back(name);
    public static bool CanGoForward(string name) => goatwebview_can_go_forward(name);

    public static string GetUrl(string name)
    {
        // 래퍼가 strdup한 문자열이므로 읽은 뒤 해제한다.
        var pointer = goatwebview_get_url(name);
        if (pointer == IntPtr.Zero) return null;
        var url = Marshal.PtrToStringUTF8(pointer);
        Marshal.FreeHGlobal(pointer);
        return url;
    }

    public static void SetVisibility(string name, bool visible) => goatwebview_set_visibility(name, visible);
    public static void SetFrame(string name, float x, float y, float width, float height) => goatwebview_set_frame(name, x, y, width, height);
    public static void SetFallbackTheme(string name, int theme) => goatwebview_set_fallback_theme(name, theme);
    public static void SetTopSafeAreaColor(string name, float r, float g, float b, float a) => goatwebview_set_top_safe_area_color(name, r, g, b, a);

    public static void SetRoundCornerRadius(string name, float topLeft, float topRight, float bottomLeft, float bottomRight) =>
        goatwebview_set_round_corner_radius(name, topLeft, topRight, bottomLeft, bottomRight);

    public static void SetBouncesEnabled(string name, bool enabled) => goatwebview_set_scroll_bounce_enabled(name, enabled);
    public static void SetPaused(string name, bool paused) => goatwebview_set_suspended(name, paused);
    public static void ClearCache() => goatwebview_clear_cache();
    public static void SetForwardWebConsoleToNativeOutput(bool enabled) => goatwebview_set_forward_web_console_to_native_output(enabled);

    // Swift Bool은 1바이트이므로 bool 인자와 반환값은 I1로 마샬링한다.
    [DllImport("__Internal")] [return: MarshalAs(UnmanagedType.I1)]
    static extern bool goatwebview_create([MarshalAs(UnmanagedType.LPUTF8Str)] string name);
    [DllImport("__Internal")] static extern void goatwebview_set_allowed_message_origins([MarshalAs(UnmanagedType.LPUTF8Str)] string name, [MarshalAs(UnmanagedType.LPUTF8Str)] string allowedOrigins);
    [DllImport("__Internal")] static extern void goatwebview_dispose([MarshalAs(UnmanagedType.LPUTF8Str)] string name);
    [DllImport("__Internal")] static extern void goatwebview_load_url([MarshalAs(UnmanagedType.LPUTF8Str)] string name, [MarshalAs(UnmanagedType.LPUTF8Str)] string url);
    [DllImport("__Internal")] static extern void goatwebview_load_html([MarshalAs(UnmanagedType.LPUTF8Str)] string name, [MarshalAs(UnmanagedType.LPUTF8Str)] string html, [MarshalAs(UnmanagedType.LPUTF8Str)] string baseUrl);
    [DllImport("__Internal")] static extern void goatwebview_evaluate_js([MarshalAs(UnmanagedType.LPUTF8Str)] string name, int requestId, [MarshalAs(UnmanagedType.LPUTF8Str)] string js);
    [DllImport("__Internal")] [return: MarshalAs(UnmanagedType.I1)] static extern bool goatwebview_can_go_back([MarshalAs(UnmanagedType.LPUTF8Str)] string name);
    [DllImport("__Internal")] [return: MarshalAs(UnmanagedType.I1)] static extern bool goatwebview_can_go_forward([MarshalAs(UnmanagedType.LPUTF8Str)] string name);
    [DllImport("__Internal")] static extern void goatwebview_go_back([MarshalAs(UnmanagedType.LPUTF8Str)] string name);
    [DllImport("__Internal")] static extern void goatwebview_go_forward([MarshalAs(UnmanagedType.LPUTF8Str)] string name);
    [DllImport("__Internal")] static extern void goatwebview_reload([MarshalAs(UnmanagedType.LPUTF8Str)] string name);
    [DllImport("__Internal")] static extern void goatwebview_stop_loading([MarshalAs(UnmanagedType.LPUTF8Str)] string name);
    [DllImport("__Internal")] static extern IntPtr goatwebview_get_url([MarshalAs(UnmanagedType.LPUTF8Str)] string name);
    [DllImport("__Internal")] static extern void goatwebview_set_visibility([MarshalAs(UnmanagedType.LPUTF8Str)] string name, [MarshalAs(UnmanagedType.I1)] bool visible);
    [DllImport("__Internal")] static extern void goatwebview_set_frame([MarshalAs(UnmanagedType.LPUTF8Str)] string name, float x, float y, float width, float height);
    [DllImport("__Internal")] static extern void goatwebview_set_fallback_theme([MarshalAs(UnmanagedType.LPUTF8Str)] string name, int theme);
    [DllImport("__Internal")] static extern void goatwebview_set_top_safe_area_color([MarshalAs(UnmanagedType.LPUTF8Str)] string name, float r, float g, float b, float a);
    [DllImport("__Internal")] static extern void goatwebview_set_round_corner_radius([MarshalAs(UnmanagedType.LPUTF8Str)] string name, float topLeft, float topRight, float bottomLeft, float bottomRight);
    [DllImport("__Internal")] static extern void goatwebview_set_scroll_bounce_enabled([MarshalAs(UnmanagedType.LPUTF8Str)] string name, [MarshalAs(UnmanagedType.I1)] bool enabled);
    [DllImport("__Internal")] static extern void goatwebview_set_suspended([MarshalAs(UnmanagedType.LPUTF8Str)] string name, [MarshalAs(UnmanagedType.I1)] bool suspended);
    [DllImport("__Internal")] static extern void goatwebview_clear_cache();
    [DllImport("__Internal")] static extern void goatwebview_set_forward_web_console_to_native_output([MarshalAs(UnmanagedType.I1)] bool enabled);
}
#endif
