#if UNITY_EDITOR_OSX || UNITY_EDITOR_WIN
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
using UnityEngine.UI;

/// <summary>
/// macOS Editor에서 <c>GoatWebView.bundle</c>(WKWebView), Windows Editor에서 <c>GoatWebViewPlugin.dll</c>(WebView2)을 쓰는 확인용 웹뷰.
/// 플러그인은 화면 밖 창의 웹뷰를 비트맵으로 주므로 매 프레임 텍스처로 받아 OnGUI로 그리고, 마우스·키 입력을 플러그인에 넘긴다.
/// Editor에서는 UnitySendMessage를 쓸 수 없어 플러그인이 쌓은 "메서드:JSON" 메시지를 꺼내
/// 같은 GameObject에 SendMessage로 보낸다(<see cref="GoatWebViewListener"/>가 받는다). 두 플러그인의 C 함수와 메시지 형식은 같다.
/// 비트맵은 둘 다 위→아래 행 순서이고 Unity 텍스처는 좌하단이 원점이라 뒤집어 그리며, Windows DLL은 버퍼에 복사하고 macOS 번들은 포인터를 돌려주는 점만 다르다.
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
#if UNITY_EDITOR_WIN
    // Windows DLL은 비트맵을 이 버퍼에 복사한다.
    byte[] pixelBuffer;
#endif
    bool hasFocus;
    // 번들은 웹 콘솔을 항상 보내고, 켜기 전에는 여기서 버린다(기기와 같이 기본은 꺼짐).
    bool consoleMessageEnabled;
    string inputString = "";
    GameObject blockerCanvas;
    RectTransform blocker;

    internal string Url { get; private set; }
    internal bool CanGoBack => instance != IntPtr.Zero && _CWebViewPlugin_CanGoBack(instance);
    internal bool CanGoForward => instance != IntPtr.Zero && _CWebViewPlugin_CanGoForward(instance);

    void Awake()
    {
        _CWebViewPlugin_InitStatic(true, SystemInfo.graphicsDeviceType == GraphicsDeviceType.Metal);
        instance = _CWebViewPlugin_Init(gameObject.name, false, false, Screen.width, Screen.height, null, false);
        _CWebViewPlugin_SetVisibility(instance, false);
        CreateBlocker();
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
        if (blockerCanvas != null) Destroy(blockerCanvas);
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

    internal void SetConsoleMessageEnabled(bool enabled) => consoleMessageEnabled = enabled;

    internal void SetVisible(bool value)
    {
        visible = value;
        blockerCanvas.SetActive(value);
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
        // 좌하단 원점 화면 픽셀. 차단 Canvas에는 스케일러가 없어 1단위가 1px이다.
        blocker.anchoredPosition = rect.position;
        blocker.sizeDelta = rect.size;
        _CWebViewPlugin_SetRect(instance, Mathf.Max((int)w, 1), Mathf.Max((int)h, 1));
    }

    // 웹뷰는 OnGUI로 그린 텍스처라 uGUI 레이캐스트에 잡히지 않는다. 기기처럼 웹뷰 영역의 클릭이 아래 UI로 가지 않도록
    // 맨 위 Overlay Canvas에 투명 Image를 둔다.
    void CreateBlocker()
    {
        blockerCanvas = new GameObject("InputBlocker", typeof(Canvas), typeof(GraphicRaycaster));
        blockerCanvas.transform.SetParent(transform, false);
        var canvas = blockerCanvas.GetComponent<Canvas>();
        canvas.renderMode = RenderMode.ScreenSpaceOverlay;
        canvas.sortingOrder = short.MaxValue;
        blocker = (RectTransform)new GameObject("Blocker", typeof(RectTransform), typeof(Image)).transform;
        blocker.SetParent(blockerCanvas.transform, false);
        blocker.anchorMin = blocker.anchorMax = blocker.pivot = Vector2.zero;
        blocker.GetComponent<Image>().color = Color.clear;
        blockerCanvas.SetActive(false);
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
            if (method == "OnConsoleMessage" && !consoleMessageEnabled) continue;
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
#if UNITY_EDITOR_WIN
        var length = bitmapWidth * bitmapHeight * 4;
        if (pixelBuffer == null || pixelBuffer.Length != length) pixelBuffer = new byte[length];
        _CWebViewPlugin_Render(instance, pixelBuffer);
        texture.LoadRawTextureData(pixelBuffer);
#else
        var pixels = _CWebViewPlugin_Render(instance, IntPtr.Zero);
        if (pixels == IntPtr.Zero) return;
        texture.LoadRawTextureData(pixels, bitmapWidth * bitmapHeight * 4);
#endif
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
                    // 비트맵은 위→아래 행 순서인데 LoadRawTextureData는 첫 행을 텍스처 아래로 두므로 뒤집어 그린다.
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

    // macOS는 GoatWebView.bundle이다. Windows DLL은 asmdef가 만드는 관리 어셈블리 GoatWebView.dll과 이름이 겹치지 않도록 GoatWebViewPlugin.dll이다.
#if UNITY_EDITOR_WIN
    const string Bundle = "GoatWebViewPlugin";
#else
    const string Bundle = "GoatWebView";
#endif

    // Objective-C BOOL과 MSVC bool은 1바이트이므로 bool 인자와 반환값은 I1로 마샬링한다.
    // _CWebViewPlugin_GetMessage가 준 문자열(macOS malloc, Windows CoTaskMemAlloc)은 string 반환 마샬링이 해제한다.
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
#if UNITY_EDITOR_WIN
    [DllImport(Bundle)] static extern void _CWebViewPlugin_Render(IntPtr instance, byte[] textureBuffer);
#else
    [DllImport(Bundle)] static extern IntPtr _CWebViewPlugin_Render(IntPtr instance, IntPtr textureBuffer);
#endif
    [DllImport(Bundle)] [return: MarshalAs(UnmanagedType.LPUTF8Str)] static extern string _CWebViewPlugin_GetMessage(IntPtr instance);
}
#endif
