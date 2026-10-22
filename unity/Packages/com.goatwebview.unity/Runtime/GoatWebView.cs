using System;
using System.Collections.Generic;
using UnityEngine;

/// <summary>
/// 네이티브 웹뷰 컴포넌트. UniWebView처럼 <c>gameObject.AddComponent&lt;GoatWebView&gt;()</c>로 붙이고
/// <see cref="Frame"/>, <see cref="Load"/>, <see cref="Show"/>를 호출한다.
/// <para>
/// 붙이는 즉시(Awake) 네이티브 웹뷰를 숨긴 상태로 만든다. 다 쓰면 <c>Destroy(webView)</c>로 해제한다.
/// </para>
/// <para>
/// 프리팹(Prefabs/GoatWebView.prefab)이나 씬에 둔 컴포넌트의 Inspector로도 URL·영역·출처·테마를 정할 수 있다.
/// </para>
/// 플랫폼 호출은 <c>NativeInterface</c>(Interface 폴더의 플랫폼별 파일)가 맡는다.
/// </summary>
[DisallowMultipleComponent]
public class GoatWebView : MonoBehaviour
{
    public delegate void PageStartedDelegate(GoatWebView webView, string url);
    public delegate void PageFinishedDelegate(GoatWebView webView, int statusCode, string url);
    public delegate void LoadingErrorReceivedDelegate(GoatWebView webView, GoatWebViewLoadingError error);
    public delegate void MessageReceivedDelegate(GoatWebView webView, GoatWebViewMessage message);
    public delegate void ConsoleMessageDelegate(GoatWebView webView, GoatWebViewConsoleMessage message);

    /// <summary>메인 프레임 탐색 시작. 서버 리다이렉트 때도 새 URL로 다시 불린다.</summary>
    public event PageStartedDelegate OnPageStarted;
    /// <summary>메인 프레임 로드 완료. HTTP 오류도 로드는 끝나므로 statusCode(오류가 없으면 200)로 알린다.</summary>
    public event PageFinishedDelegate OnPageFinished;
    /// <summary>네트워크 오류, 웹 콘텐츠 프로세스 종료, 잘못된 호출 등 로드 실패.</summary>
    public event LoadingErrorReceivedDelegate OnLoadingErrorReceived;
    /// <summary>허용 출처의 메인 프레임이 보낸 메시지.</summary>
    public event MessageReceivedDelegate OnMessageReceived;
    /// <summary>허용 출처를 줬지만 기기의 System WebView가 메시지 브리지를 지원하지 않는다(Android 전용). 인자는 이유.</summary>
    public event Action<GoatWebView, string> OnMessageBridgeUnavailable;
    /// <summary>
    /// 사용자가 폴백 화면(로드 전·실패 시 화면)의 닫기 버튼을 눌러 네이티브가 웹뷰를 해제했다.
    /// 이 컴포넌트는 남아 있지만 이후 호출은 무시되므로 보통 Destroy한다.
    /// </summary>
    public event Action<GoatWebView> OnClosed;
    /// <summary>웹 콘솔 로그(console.log 등). 토큰 등이 섞일 수 있어 기본으로 보내지 않으며 <see cref="SetConsoleMessageEnabled"/>로 켠다.</summary>
    public event ConsoleMessageDelegate OnConsoleMessage;

    readonly Dictionary<int, Action<string>> javaScriptCallbacks = new Dictionary<int, Action<string>>();
    static readonly Vector3[] corners = new Vector3[4];

    [SerializeField, Tooltip("Start에서 로드할 http(s) URL. 비우면 로드하지 않는다.")]
    string urlOnStart = "";
    [SerializeField, Tooltip("Start에서 Show를 부를지. 끄면 직접 Show를 부른다.")]
    bool showOnStart;
    [SerializeField, Tooltip("상단 safe area를 뺀 화면 영역에 대한 비율(0..1). Reference Rect Transform이 있으면 무시한다.")]
    Rect frame = new Rect(0, 0, 1, 1);
    [SerializeField, Tooltip("이 RectTransform의 화면 영역에 웹뷰를 맞춘다. 레이아웃·해상도가 바뀌면 따라간다.")]
    RectTransform referenceRectTransform;
    [SerializeField, Tooltip("메시지를 받을 출처(https://example.com). 비우면 메시지 브리지를 설치하지 않는다.")]
    string[] allowedMessageOrigins = new string[0];
    [SerializeField, Tooltip("로드 전·실패 시 웹뷰 자리에 보이는 폴백 화면의 테마.")]
    GoatWebViewFallbackTheme fallbackTheme = GoatWebViewFallbackTheme.Light;

    GoatWebViewListener listener;
    string nativeName;
    int nextRequestId;
    Vector2Int appliedScreenSize;
    Rect appliedSafeArea;
    Rect appliedFrame;

    /// <summary>앱의 모든 웹뷰가 공유하는 캐시를 비운다. 쿠키와 웹 저장소는 유지한다.</summary>
    public static void ClearCache() => NativeInterface.ClearCache();

    /// <summary>
    /// 메시지를 받을 출처(<c>https://example.com</c>). 비우면 메시지 브리지를 설치하지 않는다(기본).
    /// 지금 페이지에는 적용되지 않고 다음 <see cref="Load"/>·<see cref="LoadHTML"/>부터 적용되므로 로드 전에 정한다.
    /// </summary>
    public string[] AllowedMessageOrigins
    {
        get => allowedMessageOrigins;
        set
        {
            allowedMessageOrigins = value ?? new string[0];
            if (nativeName != null) NativeInterface.SetAllowedMessageOrigins(nativeName, allowedMessageOrigins);
        }
    }

    /// <summary>
    /// 상단 safe area를 뺀 화면 영역에 대한 비율(0..1). 원점은 safe area 바로 아래의 좌상단이고 기본은 전체(0, 0, 1, 1).
    /// 웹뷰는 상단 safe area 위로 올라가지 않는다. <see cref="ReferenceRectTransform"/>이 있으면 무시한다.
    /// </summary>
    public Rect Frame
    {
        get => frame;
        set
        {
            frame = value;
            if (nativeName != null) UpdateFrame();
        }
    }

    /// <summary>Frame 대신 이 RectTransform의 화면 영역에 맞춘다. 레이아웃·해상도가 바뀌면 따라간다. null이면 Frame을 쓴다.</summary>
    public RectTransform ReferenceRectTransform
    {
        get => referenceRectTransform;
        set
        {
            referenceRectTransform = value;
            if (nativeName != null) UpdateFrame();
        }
    }

    /// <summary>현재 페이지 URL. 웹뷰가 없거나 아직 탐색하지 않았으면 null.</summary>
    public string Url => nativeName != null ? NativeInterface.GetUrl(nativeName) : null;
    public bool CanGoBack => nativeName != null && NativeInterface.CanGoBack(nativeName);
    public bool CanGoForward => nativeName != null && NativeInterface.CanGoForward(nativeName);

    void Awake()
    {
        // 네이티브는 리스너 GameObject 이름으로 웹뷰를 구분하고 이벤트를 보낸다. 사용자 GameObject 이름과 무관하게
        // 고유하도록 GUID 이름의 루트 GameObject를 만든다. 이 GameObject가 비활성이거나 씬이 바뀌어도 이벤트를 받도록
        // 루트에 두고 DontDestroyOnLoad로 유지하며, OnDestroy에서 함께 파괴한다.
        var listenerObject = new GameObject("GoatWebView-" + Guid.NewGuid().ToString("N"));
        DontDestroyOnLoad(listenerObject);
        listener = listenerObject.AddComponent<GoatWebViewListener>();
        listener.Owner = this;
        if (!NativeInterface.Create(listener))
        {
            // AddComponent 중이라 아직 이벤트 구독자가 없으므로 로그로 알린다. 이후 호출은 무시된다.
            Debug.LogError("[GoatWebView] 웹뷰를 만들지 못했습니다.");
            Destroy(listenerObject);
            listener = null;
            return;
        }
        nativeName = listenerObject.name;
        // Inspector(프리팹)로 정한 값을 적용한다. 기본값은 네이티브도 같으므로 건너뛴다.
        if (allowedMessageOrigins.Length > 0) NativeInterface.SetAllowedMessageOrigins(nativeName, allowedMessageOrigins);
        if (fallbackTheme != GoatWebViewFallbackTheme.Light) NativeInterface.SetFallbackTheme(nativeName, (int)fallbackTheme);
        UpdateFrame();
    }

    void Start()
    {
        if (!string.IsNullOrEmpty(urlOnStart)) Load(urlOnStart);
        if (showOnStart) Show();
    }

    void Update()
    {
        // 회전·해상도·safe area가 바뀌면 다시 맞춘다. RectTransform은 레이아웃이 바뀌어도 알림이 없어 매 프레임 비교한다.
        if (nativeName == null) return;
        if (referenceRectTransform != null || appliedScreenSize.x != Screen.width || appliedScreenSize.y != Screen.height ||
            appliedSafeArea != Screen.safeArea)
            UpdateFrame();
    }

    void OnApplicationPause(bool paused)
    {
        if (nativeName != null) NativeInterface.SetPaused(nativeName, paused);
    }

    void OnDestroy()
    {
        if (nativeName != null) NativeInterface.Destroy(nativeName);
        if (listener != null) Destroy(listener.gameObject);
        listener = null;
        nativeName = null;
        javaScriptCallbacks.Clear();
    }

    public void Load(string url)
    {
        if (nativeName != null) NativeInterface.Load(nativeName, url);
    }

    /// <summary>HTML을 로드한다. baseUrl(http(s))은 상대 경로와 메시지 출처 판정에 쓴다.</summary>
    public void LoadHTML(string html, string baseUrl = null)
    {
        if (nativeName != null) NativeInterface.LoadHTML(nativeName, html, baseUrl);
    }

    /// <summary>스크립트를 실행하고 결과를 JSON 문자열로 넘긴다(문자열이면 따옴표 포함). 오류·undefined면 null.</summary>
    public void EvaluateJavaScript(string jsString, Action<string> completionHandler = null)
    {
        if (nativeName == null) return;
        var requestId = ++nextRequestId;
        if (completionHandler != null) javaScriptCallbacks[requestId] = completionHandler;
        NativeInterface.EvaluateJavaScript(nativeName, requestId, jsString);
    }

    public void Show()
    {
        if (nativeName != null) NativeInterface.SetVisibility(nativeName, true);
    }

    public void Hide()
    {
        if (nativeName != null) NativeInterface.SetVisibility(nativeName, false);
    }

    public void GoBack()
    {
        if (nativeName != null) NativeInterface.GoBack(nativeName);
    }

    public void GoForward()
    {
        if (nativeName != null) NativeInterface.GoForward(nativeName);
    }

    public void Reload()
    {
        if (nativeName != null) NativeInterface.Reload(nativeName);
    }

    public void Stop()
    {
        if (nativeName != null) NativeInterface.Stop(nativeName);
    }

    /// <summary>
    /// 폴백 화면(로드 전·실패 시 웹뷰 자리에 보이는 화면과 닫기 버튼)의 테마. 기본 Light.
    /// System은 기기의 다크 모드를 따른다. Editor에서는 무시한다.
    /// </summary>
    public void SetFallbackTheme(GoatWebViewFallbackTheme theme)
    {
        fallbackTheme = theme;
        if (nativeName != null) NativeInterface.SetFallbackTheme(nativeName, (int)theme);
    }

    /// <summary>
    /// 웹 콘솔 로그를 <see cref="OnConsoleMessage"/>로 받을지 정한다. 기본 false. 켠 뒤에 찍힌 로그부터 받는다.
    /// </summary>
    public void SetConsoleMessageEnabled(bool enabled)
    {
        if (nativeName != null) NativeInterface.SetConsoleMessageEnabled(nativeName, enabled);
    }

    /// <summary>상단 safe area(상태바·노치)를 칠할 색. 알파가 0이면 칠하지 않는다.</summary>
    public void SetStatusBarColor(Color color)
    {
        if (nativeName != null) NativeInterface.SetStatusBarColor(nativeName, color.r, color.g, color.b, color.a);
    }

    /// <summary>스크롤 끝에서 튕기는 효과. 기본 true.</summary>
    public void SetBouncesEnabled(bool enabled)
    {
        if (nativeName != null) NativeInterface.SetBouncesEnabled(nativeName, enabled);
    }

    /// <summary>
    /// 모서리별 반지름(Unity 화면 픽셀). 음수는 0으로 본다. Android는 기기 px, iOS는 pt로 바뀌어 같은 크기로 보인다.
    /// </summary>
    public void SetRoundCornerRadius(float topLeft, float topRight, float bottomLeft, float bottomRight)
    {
        if (nativeName == null) return;
        // Unity 해상도 스케일링을 반영해 기기 픽셀로 바꾼다. iOS 래퍼는 이를 contentScaleFactor로 나눠 pt로 바꾼다.
        var scale = Screen.width > 0 ? (float)Display.main.systemWidth / Screen.width : 1;
        NativeInterface.SetRoundCornerRadius(nativeName, Mathf.Max(topLeft, 0) * scale, Mathf.Max(topRight, 0) * scale,
            Mathf.Max(bottomLeft, 0) * scale, Mathf.Max(bottomRight, 0) * scale);
    }

    // Reference Rect Transform이 있으면 그 화면 영역을 Frame 비율로 바꾼다. 화면과 영역이 그대로면 네이티브를 부르지 않는다.
    void UpdateFrame()
    {
        var target = referenceRectTransform != null ? FrameOf(referenceRectTransform) : frame;
        var screenSize = new Vector2Int(Screen.width, Screen.height);
        if (target == appliedFrame && screenSize == appliedScreenSize && Screen.safeArea == appliedSafeArea) return;
        appliedFrame = target;
        appliedScreenSize = screenSize;
        appliedSafeArea = Screen.safeArea;
        NativeInterface.SetFrame(nativeName, target.x, target.y, Mathf.Max(target.width, 0), Mathf.Max(target.height, 0));
    }

    // RectTransform의 화면 픽셀 영역(좌하단 원점)을 상단 safe area 아래 영역(좌상단 원점)의 비율로 바꾼다.
    static Rect FrameOf(RectTransform rectTransform)
    {
        rectTransform.GetWorldCorners(corners);
        var canvas = rectTransform.GetComponentInParent<Canvas>();
        var rootCanvas = canvas != null ? canvas.rootCanvas : null;
        var camera = rootCanvas == null || rootCanvas.renderMode == RenderMode.ScreenSpaceOverlay ? null : rootCanvas.worldCamera;
        var min = RectTransformUtility.WorldToScreenPoint(camera, corners[0]);
        var max = RectTransformUtility.WorldToScreenPoint(camera, corners[2]);
        var width = (float)Screen.width;
        var available = Screen.safeArea.yMax;
        if (width <= 0 || available <= 0) return Rect.zero;
        return new Rect(min.x / width, (available - max.y) / available, (max.x - min.x) / width, (max.y - min.y) / available);
    }

    internal void HandlePageStarted(string url) => OnPageStarted?.Invoke(this, url);

    internal void HandlePageFinished(int statusCode, string url) => OnPageFinished?.Invoke(this, statusCode, url);

    internal void HandleLoadingErrorReceived(GoatWebViewLoadingError error) => OnLoadingErrorReceived?.Invoke(this, error);

    internal void HandleMessageReceived(GoatWebViewMessage message) => OnMessageReceived?.Invoke(this, message);

    internal void HandleMessageBridgeUnavailable(string reason) => OnMessageBridgeUnavailable?.Invoke(this, reason);

    internal void HandleConsoleMessage(GoatWebViewConsoleMessage message) => OnConsoleMessage?.Invoke(this, message);

    // 네이티브는 이미 해제됐으므로 Destroy를 부르지 않고 연결만 끊는다.
    internal void HandleClosed()
    {
        if (nativeName == null) return;
        nativeName = null;
        if (listener != null) Destroy(listener.gameObject);
        listener = null;
        javaScriptCallbacks.Clear();
        OnClosed?.Invoke(this);
    }

    internal void HandleJavaScriptResult(int requestId, string result)
    {
        if (!javaScriptCallbacks.TryGetValue(requestId, out var callback)) return;
        javaScriptCallbacks.Remove(requestId);
        callback(result);
    }
}
