using UnityEngine;
using UnityEngine.UI;

/// <summary>
/// 프리팹 방식 데모. 웹뷰는 씬에 둔 GoatWebView 프리팹이 Inspector 설정(Url On Start, Reference Rect Transform)대로
/// Start에서 로드하고 PopupArea 영역에 맞춘다. Open은 딤드와 함께 표시하고, 딤드를 누르면 숨긴다.
/// 웹페이지가 닫기를 제공하지 않는 팝업 구성의 예.
/// </summary>
public class PrefabSampleController : MonoBehaviour
{
    [SerializeField] GoatWebView webView;
    [SerializeField] RectTransform safeAreaRoot;
    [SerializeField] GameObject dim;
    [SerializeField] Text statusText;

    Rect appliedSafeArea;

    void Start()
    {
        dim.SetActive(false);
        statusText.text = "로드 중";
        webView.OnPageFinished += (view, statusCode, url) => statusText.text = $"Finished {url} ({statusCode})";
        webView.OnLoadingErrorReceived += (view, error) => statusText.text = $"Error {error.ErrorCode}: {error.ErrorMessage}";
    }

    void Update()
    {
        // 화면 UI는 safe area 안에 둔다. 웹뷰는 PopupArea를 따라가므로 따로 맞추지 않는다.
        if (appliedSafeArea == Screen.safeArea) return;
        appliedSafeArea = Screen.safeArea;
        var size = new Vector2(Screen.width, Screen.height);
        safeAreaRoot.anchorMin = appliedSafeArea.min / size;
        safeAreaRoot.anchorMax = appliedSafeArea.max / size;
    }

    public void OnClickOpen()
    {
        dim.SetActive(true);
        webView.Show();
    }

    public void OnClickDim()
    {
        webView.Hide();
        dim.SetActive(false);
    }
}
