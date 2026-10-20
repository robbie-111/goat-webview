#if UNITY_EDITOR_OSX
/*
 * Copyright (C) 2011 Keijiro Takahashi
 * Copyright (C) 2012 GREE, Inc.
 *
 * This software is provided 'as-is', without any express or implied
 * warranty.  In no event will the authors be held liable for any damages
 * arising from the use of this software.
 *
 * Permission is granted to anyone to use this software for any purpose,
 * including commercial applications, and to alter it and redistribute it
 * freely, subject to the following restrictions:
 *
 * 1. The origin of this software must not be misrepresented; you must not
 *    claim that you wrote the original software. If you use this software
 *    in a product, an acknowledgment in the product documentation would be
 *    appreciated but is not required.
 * 2. Altered source versions must be plainly marked as such, and must not be
 *    misrepresented as being the original software.
 * 3. This notice may not be removed or altered from any source distribution.
 */
// Modified for goat-webview: gree/unity-webview plugins/WebViewObject.cs(5604a7d)의 macOS Update·OnGUI 경로만 옮겨
// GoatWebView 컴포넌트용으로 고쳤다. 메시지는 이벤트 이름 그대로 SendMessage로 넘기고,
// 스크롤은 클릭 포커스와 관계없이 커서가 웹뷰 위에 있으면 넘긴다.

using System;
using System.Runtime.InteropServices;
using Newtonsoft.Json.Linq;
using UnityEngine;
using UnityEngine.Rendering;

/// <summary>
/// macOS Editor에서 <c>GoatWebView.bundle</c>을 쓰는 확인용 웹뷰. 번들은 화면 밖 창의 WKWebView를 비트맵으로 주므로
/// 매 프레임 텍스처로 받아 OnGUI로 그리고, 마우스·키 입력을 번들에 넘긴다.
/// Editor에서는 UnitySendMessage를 쓸 수 없어 번들이 쌓은 "메서드:JSON" 메시지를 꺼내
/// 같은 GameObject에 SendMessage로 보낸다(<see cref="GoatWebViewListener"/>가 받는다).
/// </summary>
[AddComponentMenu("")]
internal sealed class GoatWebViewEditorView : MonoBehaviour
{
    IntPtr instance;
    bool visible;
    // 상단 safe area를 뺀 화면 영역에 대한 비율(모바일 SetFrame과 같다). null이면 그 영역 전체.
    Rect? ratio;
    // 화면 픽셀, 좌하단 원점(OnGUI 그리기와 입력 좌표 기준).
    Rect rect;
    Vector2Int appliedSize;
    Texture2D texture;
    bool hasFocus;
    string inputString = "";

    internal string Url { get; private set; }
    internal bool CanGoBack => instance != IntPtr.Zero && _CWebViewPlugin_CanGoBack(instance);
    internal bool CanGoForward => instance != IntPtr.Zero && _CWebViewPlugin_CanGoForward(instance);

    void Awake()
    {
        _CWebViewPlugin_InitStatic(true, SystemInfo.graphicsDeviceType == GraphicsDeviceType.Metal);
        instance = _CWebViewPlugin_Init(gameObject.name, false, false, Screen.width, Screen.height, null, false);
        _CWebViewPlugin_SetVisibility(instance, false);
        ApplyRect();
    }

    void OnDestroy()
    {
        if (instance != IntPtr.Zero)
        {
            _CWebViewPlugin_Destroy(instance);
            instance = IntPtr.Zero;
        }
        if (texture != null) Destroy(texture);
    }

    void OnApplicationFocus(bool focus)
    {
        if (!focus) hasFocus = false;
    }

    internal void LoadUrl(string url) => _CWebViewPlugin_LoadURL(instance, url);
    internal void LoadHtml(string html, string baseUrl) => _CWebViewPlugin_LoadHTML(instance, html, baseUrl);
    internal void EvaluateJavaScript(int requestId, string script) => _CWebViewPlugin_EvaluateJavaScript(instance, requestId, script);
    internal void GoBack() => _CWebViewPlugin_GoBack(instance);
    internal void GoForward() => _CWebViewPlugin_GoForward(instance);
    internal void Reload() => _CWebViewPlugin_Reload(instance);

    internal void SetVisible(bool value)
    {
        visible = value;
        _CWebViewPlugin_SetVisibility(instance, value);
    }

    internal void SetFrame(Rect value)
    {
        ratio = value;
        ApplyRect();
    }

    // 비율을 화면 픽셀로 바꾼다. 화면(Game 뷰) 크기가 바뀌면 Update에서 다시 부른다.
    void ApplyRect()
    {
        float width = Screen.width, height = Screen.height;
        var safeTop = height - Screen.safeArea.yMax;
        var available = height - safeTop;
        var r = ratio ?? new Rect(0, 0, 1, 1);
        var w = r.width * width;
        var h = r.height * available;
        var top = safeTop + r.y * available;
        rect = new Rect(r.x * width, height - top - h, w, h);
        appliedSize = new Vector2Int(Screen.width, Screen.height);
        _CWebViewPlugin_SetRect(instance, Mathf.Max((int)w, 1), Mathf.Max((int)h, 1));
    }

    void Update()
    {
        if (instance == IntPtr.Zero) return;
        if (appliedSize.x != Screen.width || appliedSize.y != Screen.height) ApplyRect();
#if ENABLE_LEGACY_INPUT_MANAGER
        if (hasFocus) inputString += Input.inputString;
#endif
        for (;;)
        {
            var s = _CWebViewPlugin_GetMessage(instance);
            if (s == null) break;
            var i = s.IndexOf(':');
            if (i == -1) continue;
            var method = s.Substring(0, i);
            var json = s.Substring(i + 1);
            if (method == "OnPageStarted" || method == "OnPageFinished")
                Url = (string)JObject.Parse(json)["url"];
            SendMessage(method, json, SendMessageOptions.DontRequireReceiver);
            // 메시지 처리 중 GoatWebView가 해제되면 instance가 없어진다.
            if (instance == IntPtr.Zero) return;
        }
        if (!visible) return;
        _CWebViewPlugin_Update(instance, true, 1);
        var bitmapWidth = _CWebViewPlugin_BitmapWidth(instance);
        var bitmapHeight = _CWebViewPlugin_BitmapHeight(instance);
        if (bitmapWidth <= 0 || bitmapHeight <= 0) return;
        if (texture == null || texture.width != bitmapWidth || texture.height != bitmapHeight)
        {
            if (texture != null) Destroy(texture);
            var format = _CWebViewPlugin_BitmapARGB(instance) ? TextureFormat.ARGB32 : TextureFormat.RGBA32;
            var isLinearSpace = QualitySettings.activeColorSpace == ColorSpace.Linear;
            texture = new Texture2D(bitmapWidth, bitmapHeight, format, false, !isLinearSpace)
            {
                filterMode = FilterMode.Bilinear,
                wrapMode = TextureWrapMode.Clamp,
            };
        }
        var pixels = _CWebViewPlugin_Render(instance, IntPtr.Zero);
        if (pixels == IntPtr.Zero) return;
        texture.LoadRawTextureData(pixels, bitmapWidth * bitmapHeight * 4);
        texture.Apply();
    }

    void OnGUI()
    {
        if (instance == IntPtr.Zero || !visible) return;
        var e = Event.current;
        if (e.type == EventType.MouseDown || e.type == EventType.MouseUp)
            hasFocus = rect.Contains(ToBottomLeft(e.mousePosition));
        switch (e.type)
        {
            case EventType.MouseMove:
            case EventType.MouseDown:
            case EventType.MouseDrag:
            case EventType.MouseUp:
                if (hasFocus)
                {
                    var p = ToWebView(e.mousePosition);
                    var mouseState = e.type == EventType.MouseDown ? 1
                        : e.type == EventType.MouseDrag ? 2
                        : e.type == EventType.MouseUp ? 3 : 0;
                    _CWebViewPlugin_SendMouseEvent(instance, (int)p.x, (int)p.y, 0, mouseState);
                }
                break;
            case EventType.ScrollWheel:
                // 휠·트랙패드 스크롤은 먼저 클릭하지 않아도 커서 아래 웹뷰로 보낸다.
                if (rect.Contains(ToBottomLeft(e.mousePosition)))
                {
                    var p = ToWebView(e.mousePosition);
                    _CWebViewPlugin_SendMouseEvent(instance, (int)p.x, (int)p.y, -e.delta.y, 0);
                }
                break;
            case EventType.Repaint:
                while (!string.IsNullOrEmpty(inputString))
                {
                    var keyChars = inputString.Substring(0, 1);
                    var keyCode = (ushort)inputString[0];
                    inputString = inputString.Substring(1);
                    var p = ToWebView(e.mousePosition);
                    _CWebViewPlugin_SendKeyEvent(instance, (int)p.x, (int)p.y, keyChars, keyCode, 1);
                }
                if (texture != null)
                {
                    // 비트맵은 아래에서 위로 쌓여 있으므로 뒤집어 그린다.
                    var matrix = GUI.matrix;
                    GUI.matrix = Matrix4x4.TRS(new Vector3(0, Screen.height, 0), Quaternion.identity, new Vector3(1, -1, 1));
                    Graphics.DrawTexture(rect, texture);
                    GUI.matrix = matrix;
                }
                break;
        }
    }

    static Vector2 ToBottomLeft(Vector2 guiPosition) => new Vector2(guiPosition.x, Screen.height - guiPosition.y);

    Vector2 ToWebView(Vector2 guiPosition) => ToBottomLeft(guiPosition) - rect.position;

    const string Bundle = "GoatWebView";

    // Objective-C BOOL은 1바이트이므로 bool 인자와 반환값은 I1로 마샬링한다.
    // _CWebViewPlugin_GetMessage가 malloc한 문자열은 string 반환 마샬링이 해제한다.
    [DllImport(Bundle)] static extern void _CWebViewPlugin_InitStatic([MarshalAs(UnmanagedType.I1)] bool inEditor, [MarshalAs(UnmanagedType.I1)] bool useMetal);
    [DllImport(Bundle)] static extern IntPtr _CWebViewPlugin_Init([MarshalAs(UnmanagedType.LPUTF8Str)] string gameObject, [MarshalAs(UnmanagedType.I1)] bool transparent,
        [MarshalAs(UnmanagedType.I1)] bool zoom, int width, int height, [MarshalAs(UnmanagedType.LPUTF8Str)] string ua, [MarshalAs(UnmanagedType.I1)] bool separated);
    [DllImport(Bundle)] static extern void _CWebViewPlugin_Destroy(IntPtr instance);
    [DllImport(Bundle)] static extern void _CWebViewPlugin_SetRect(IntPtr instance, int width, int height);
    [DllImport(Bundle)] static extern void _CWebViewPlugin_SetVisibility(IntPtr instance, [MarshalAs(UnmanagedType.I1)] bool visibility);
    [DllImport(Bundle)] static extern void _CWebViewPlugin_LoadURL(IntPtr instance, [MarshalAs(UnmanagedType.LPUTF8Str)] string url);
    [DllImport(Bundle)] static extern void _CWebViewPlugin_LoadHTML(IntPtr instance, [MarshalAs(UnmanagedType.LPUTF8Str)] string html, [MarshalAs(UnmanagedType.LPUTF8Str)] string baseUrl);
    [DllImport(Bundle)] static extern void _CWebViewPlugin_EvaluateJavaScript(IntPtr instance, int requestId, [MarshalAs(UnmanagedType.LPUTF8Str)] string js);
    [DllImport(Bundle)] [return: MarshalAs(UnmanagedType.I1)] static extern bool _CWebViewPlugin_CanGoBack(IntPtr instance);
    [DllImport(Bundle)] [return: MarshalAs(UnmanagedType.I1)] static extern bool _CWebViewPlugin_CanGoForward(IntPtr instance);
    [DllImport(Bundle)] static extern void _CWebViewPlugin_GoBack(IntPtr instance);
    [DllImport(Bundle)] static extern void _CWebViewPlugin_GoForward(IntPtr instance);
    [DllImport(Bundle)] static extern void _CWebViewPlugin_Reload(IntPtr instance);
    [DllImport(Bundle)] static extern void _CWebViewPlugin_SendMouseEvent(IntPtr instance, int x, int y, float deltaY, int mouseState);
    [DllImport(Bundle)] static extern void _CWebViewPlugin_SendKeyEvent(IntPtr instance, int x, int y, [MarshalAs(UnmanagedType.LPUTF8Str)] string keyChars, ushort keyCode, int keyState);
    [DllImport(Bundle)] static extern void _CWebViewPlugin_Update(IntPtr instance, [MarshalAs(UnmanagedType.I1)] bool refreshBitmap, int devicePixelRatio);
    [DllImport(Bundle)] static extern int _CWebViewPlugin_BitmapWidth(IntPtr instance);
    [DllImport(Bundle)] static extern int _CWebViewPlugin_BitmapHeight(IntPtr instance);
    [DllImport(Bundle)] [return: MarshalAs(UnmanagedType.I1)] static extern bool _CWebViewPlugin_BitmapARGB(IntPtr instance);
    [DllImport(Bundle)] static extern IntPtr _CWebViewPlugin_Render(IntPtr instance, IntPtr textureBuffer);
    [DllImport(Bundle)] [return: MarshalAs(UnmanagedType.LPUTF8Str)] static extern string _CWebViewPlugin_GetMessage(IntPtr instance);
}
#endif
