using System.Collections.Generic;
using System.Globalization;
using Newtonsoft.Json;
using Newtonsoft.Json.Linq;

/// <summary>
/// 페이지가 보낸 메시지. UniWebView의 UniWebViewMessage에 대응한다.
/// 프론트엔드는 JSON 문자열 <c>{"type": "…", "value": {…}}</c>를 보낸다. JSON이 아니거나 type이 없으면 <see cref="Path"/>는 빈 문자열이다.
/// </summary>
public class GoatWebViewMessage
{
    /// <summary>받은 원문</summary>
    public string RawMessage { get; }
    /// <summary>보낸 페이지의 출처(<c>https://example.com</c>)</summary>
    public string Origin { get; }
    /// <summary>메시지의 <c>type</c></summary>
    public string Path { get; }
    /// <summary><c>value</c>의 최상위 필드. 숫자·불리언은 문자열, 객체·배열은 JSON 문자열로 담는다.</summary>
    public Dictionary<string, string> Args { get; }

    readonly JToken value;

    public GoatWebViewMessage(string rawMessage, string origin)
    {
        RawMessage = rawMessage ?? "";
        Origin = origin ?? "";
        Args = new Dictionary<string, string>();
        var root = ParseObject(RawMessage);
        Path = root?["type"]?.Type == JTokenType.String ? (string)root["type"] : "";
        value = root?["value"];
        if (value is JObject fields)
        {
            foreach (var pair in fields) Args[pair.Key] = ToArg(pair.Value);
        }
    }

    /// <summary><c>value</c> 전체를 <typeparamref name="T"/>로 바꾼다. value가 없으면 default.</summary>
    public T GetData<T>() => value == null || value.Type == JTokenType.Null ? default : value.ToObject<T>();

    static JObject ParseObject(string json)
    {
        try
        {
            return JToken.Parse(json) as JObject;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    static string ToArg(JToken token)
    {
        switch (token.Type)
        {
            case JTokenType.Null: return null;
            case JTokenType.Object:
            case JTokenType.Array: return token.ToString(Formatting.None);
            case JTokenType.Boolean: return (bool)token ? "true" : "false";
            default: return System.Convert.ToString(((JValue)token).Value, CultureInfo.InvariantCulture);
        }
    }
}
