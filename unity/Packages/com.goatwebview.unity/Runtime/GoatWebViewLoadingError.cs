/// <summary><see cref="GoatWebView.OnLoadingErrorReceived"/>로 받는 로드 실패 정보.</summary>
public class GoatWebViewLoadingError
{
    public enum ErrorKind
    {
        /// <summary>네트워크 오류. ErrorCode는 플랫폼 오류 코드(Android WebViewClient, iOS NSURLError).</summary>
        Network,
        /// <summary>웹 콘텐츠 프로세스가 종료됐다. Android는 웹뷰가 해제돼 더 쓸 수 없다.</summary>
        Renderer,
        /// <summary>잘못된 URL 등 호출 오류. ErrorCode는 -1.</summary>
        InvalidCall,
    }

    public ErrorKind Kind { get; }
    public int ErrorCode { get; }
    public string ErrorMessage { get; }
    /// <summary>실패한 URL. 알 수 없으면 null.</summary>
    public string Url { get; }

    public GoatWebViewLoadingError(ErrorKind kind, int errorCode, string errorMessage, string url)
    {
        Kind = kind;
        ErrorCode = errorCode;
        ErrorMessage = errorMessage ?? "";
        Url = url;
    }

    internal static ErrorKind ParseKind(string kind)
    {
        switch (kind)
        {
            case "NETWORK": return ErrorKind.Network;
            case "RENDERER": return ErrorKind.Renderer;
            default: return ErrorKind.InvalidCall;
        }
    }

    public override string ToString() => $"{Kind} {ErrorCode}: {ErrorMessage}";
}
