using Newtonsoft.Json.Linq;
using UnityEngine;

/// <summary>
/// 네이티브가 <c>UnitySendMessage(GameObject 이름, 메서드 이름, JSON)</c>으로 보내는 이벤트를 받아
/// <see cref="Owner"/>로 넘긴다. GoatWebView가 웹뷰마다 만드는 GUID 이름의 숨긴 GameObject에 붙으며 직접 추가하지 않는다.
/// 메서드 이름(OnPageStarted 등)이 GoatWebView의 C# 이벤트 이름과 같아 별도 컴포넌트로 둔다.
/// </summary>
[AddComponentMenu("")]
internal sealed class GoatWebViewListener : MonoBehaviour
{
    internal GoatWebView Owner { get; set; }

    void OnPageStarted(string json)
    {
        var payload = JObject.Parse(json);
        Owner?.HandlePageStarted((string)payload["url"]);
    }

    void OnPageFinished(string json)
    {
        var payload = JObject.Parse(json);
        Owner?.HandlePageFinished((int?)payload["statusCode"] ?? 200, (string)payload["url"]);
    }

    void OnLoadingErrorReceived(string json)
    {
        var payload = JObject.Parse(json);
        Owner?.HandleLoadingErrorReceived(new GoatWebViewLoadingError(
            GoatWebViewLoadingError.ParseKind((string)payload["kind"]), (int?)payload["errorCode"] ?? -1,
            (string)payload["errorMessage"], (string)payload["url"]));
    }

    void OnMessageReceived(string json)
    {
        var payload = JObject.Parse(json);
        Owner?.HandleMessageReceived(new GoatWebViewMessage((string)payload["message"], (string)payload["origin"]));
    }

    void OnJavaScriptResult(string json)
    {
        var payload = JObject.Parse(json);
        // undefined·오류 결과는 iOS가 null, Android가 문자열 "null"로 보낸다. 둘 다 null로 넘긴다.
        var result = (string)payload["result"];
        Owner?.HandleJavaScriptResult((int?)payload["requestId"] ?? 0, result == "null" ? null : result);
    }

    void OnMessageBridgeUnavailable(string json)
    {
        var payload = JObject.Parse(json);
        var reason = (string)payload["reason"];
        Debug.LogWarning($"[GoatWebView] 메시지 브리지를 쓸 수 없습니다: {reason}");
        Owner?.HandleMessageBridgeUnavailable(reason);
    }
}
