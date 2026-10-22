using System;
using UnityEngine;
using UnityEngine.UI;

/// <summary>
/// Android·iOS 네이티브 샘플과 같은 구성의 데모. URL과 설정(상단 safe area 색, 스크롤 바운스, 모서리)을 고르고
/// Load(숨긴 채 로드)·Show(표시)로 웹뷰를 띄운다. 웹뷰는 설정 화면 위의 팝업 영역에 놓고,
/// 그 아래 여백의 Back 버튼(Android는 Back 키도)으로 뒤로 가거나 닫는다.
/// UI 참조와 버튼 연결은 씬의 Inspector에서 한다.
/// </summary>
public class WebViewSampleController : MonoBehaviour
{
    // 네이티브 샘플의 Popup 여백(dp·pt). 캔버스 단위로 쓴다.
    const float PopupHorizontalInset = 24;
    const float PopupVerticalInset = 96;

    static readonly Color[] SafeAreaColors =
    {
        new Color(0, 0, 0, 0), Color.black, Color.white, Color.red,
    };

    static readonly Vector4[] CornerRadii =
    {
        Vector4.zero, new Vector4(12, 12, 12, 12), new Vector4(24, 24, 24, 24), new Vector4(24, 24, 0, 0),
    };

    static readonly Color SelectedColor = new Color(0.25f, 0.4f, 0.85f);
    static readonly Color NormalColor = Color.white;

    [SerializeField] Canvas canvas;
    [SerializeField] RectTransform safeAreaRoot;
    [SerializeField] InputField urlField;
    [SerializeField] Button[] safeAreaButtons;
    [SerializeField] Toggle bounceToggle;
    [SerializeField] Button[] cornerRadiusButtons;
    [SerializeField] Text statusText;
    [SerializeField] GameObject backButton;

    GoatWebView webView;
    bool showing;
    int safeAreaIndex = 1;
    int cornerRadiusIndex;
    Rect appliedSafeArea;

    void Start()
    {
        SetStatus("웹뷰 없음");
        UpdateSelection();
        backButton.SetActive(false);
    }

    void Update()
    {
        if (appliedSafeArea != Screen.safeArea) ApplySafeArea();
#if ENABLE_LEGACY_INPUT_MANAGER
        // Android Back 키
        if (showing && Input.GetKeyDown(KeyCode.Escape)) OnClickBack();
#endif
    }

    public void OnClickLoad()
    {
        var url = urlField.text.Trim();
        if (!url.Contains("://")) url = "https://" + url;
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri) || (uri.Scheme != "http" && uri.Scheme != "https"))
        {
            SetStatus("HTTP(S) URL만 사용할 수 있습니다.");
            return;
        }
        if (webView == null) CreateWebView();
        webView.Load(url);
    }

    public void OnClickShow()
    {
        if (webView == null) OnClickLoad();
        if (webView == null) return;
        webView.Show();
        showing = true;
        backButton.SetActive(true);
    }

    public void OnClickBack()
    {
        if (webView == null) return;
        if (webView.CanGoBack) webView.GoBack();
        else CloseWebView("웹뷰를 닫았습니다.");
    }

    public void OnClickClearCache()
    {
        GoatWebView.ClearCache();
        SetStatus("캐시를 비웠습니다.");
    }

    public void OnClickSafeArea(int index)
    {
        safeAreaIndex = index;
        UpdateSelection();
        if (webView != null) webView.SetStatusBarColor(SafeAreaColors[index]);
    }

    public void OnClickCornerRadius(int index)
    {
        cornerRadiusIndex = index;
        UpdateSelection();
        if (webView != null) ApplyCornerRadius();
    }

    public void OnToggleBounce(bool enabled)
    {
        if (webView != null) webView.SetBouncesEnabled(enabled);
    }

    void CreateWebView()
    {
        webView = new GameObject("SampleWebView").AddComponent<GoatWebView>();
        webView.OnPageStarted += (view, url) => SetStatus($"Started {url}");
        webView.OnPageFinished += (view, statusCode, url) => SetStatus($"Finished {url} ({statusCode})");
        webView.OnLoadingErrorReceived += (view, error) =>
        {
            SetStatus($"Error {error.ErrorCode}: {error.ErrorMessage}");
            // Android는 렌더러가 종료되면 웹뷰를 더 쓸 수 없다.
            if (error.Kind == GoatWebViewLoadingError.ErrorKind.Renderer) CloseWebView(statusText.text);
        };
        // 폴백 화면의 닫기 버튼으로 네이티브가 웹뷰를 해제했다.
        webView.OnClosed += view => CloseWebView("웹뷰를 닫았습니다.");
        // 샘플은 웹 페이지 디버깅을 위해 웹 콘솔 로그를 Unity 콘솔로 출력한다.
        webView.SetConsoleMessageEnabled(true);
        webView.OnConsoleMessage += (view, message) => Debug.Log($"[WebConsole] {message}");
        webView.SetStatusBarColor(SafeAreaColors[safeAreaIndex]);
        webView.SetBouncesEnabled(bounceToggle.isOn);
        ApplyCornerRadius();
        ApplyPopupFrame();
    }

    void CloseWebView(string status)
    {
        if (webView != null) Destroy(webView.gameObject);
        webView = null;
        showing = false;
        backButton.SetActive(false);
        SetStatus(status);
    }

    void ApplySafeArea()
    {
        // 설정 화면은 safe area 안에 두고, 웹뷰 팝업 영역은 새 화면 크기로 다시 계산한다.
        appliedSafeArea = Screen.safeArea;
        var size = new Vector2(Screen.width, Screen.height);
        safeAreaRoot.anchorMin = appliedSafeArea.min / size;
        safeAreaRoot.anchorMax = appliedSafeArea.max / size;
        if (webView != null) ApplyPopupFrame();
    }

    // Frame은 상단 safe area를 뺀 영역에 대한 비율이다. 여백을 캔버스 단위에서 화면 픽셀로 바꿔 비율로 넣는다.
    void ApplyPopupFrame()
    {
        var width = (float)Screen.width;
        var available = Screen.safeArea.yMax;
        var horizontal = PopupHorizontalInset * canvas.scaleFactor / width;
        var vertical = PopupVerticalInset * canvas.scaleFactor / available;
        webView.Frame = new Rect(horizontal, vertical, 1 - horizontal * 2, 1 - vertical * 2);
    }

    void ApplyCornerRadius()
    {
        var radius = CornerRadii[cornerRadiusIndex] * canvas.scaleFactor;
        webView.SetRoundCornerRadius(radius.x, radius.y, radius.z, radius.w);
    }

    void UpdateSelection()
    {
        for (var i = 0; i < safeAreaButtons.Length; i++) SetSelected(safeAreaButtons[i], i == safeAreaIndex);
        for (var i = 0; i < cornerRadiusButtons.Length; i++) SetSelected(cornerRadiusButtons[i], i == cornerRadiusIndex);
    }

    static void SetSelected(Button button, bool selected)
    {
        button.image.color = selected ? SelectedColor : NormalColor;
        button.GetComponentInChildren<Text>().color = selected ? Color.white : Color.black;
    }

    void SetStatus(string status) => statusText.text = status;
}
