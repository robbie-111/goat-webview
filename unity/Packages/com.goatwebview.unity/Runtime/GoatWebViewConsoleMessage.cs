/// <summary><see cref="GoatWebView.OnConsoleMessage"/>로 받는 웹 콘솔 로그. Android onConsoleMessage와 iOS 콘솔 훅이 같은 형식으로 보낸다.</summary>
public class GoatWebViewConsoleMessage
{
    public enum LogLevel
    {
        Debug,
        Log,
        Warning,
        Error,
    }

    public LogLevel Level { get; }
    public string Message { get; }
    /// <summary>로그를 찍은 스크립트 URL. 알 수 없으면 빈 문자열.</summary>
    public string Source { get; }
    /// <summary>로그를 찍은 줄 번호. 알 수 없으면 0.</summary>
    public int Line { get; }

    public GoatWebViewConsoleMessage(LogLevel level, string message, string source, int line)
    {
        Level = level;
        Message = message ?? "";
        Source = source ?? "";
        Line = line;
    }

    internal static LogLevel ParseLevel(string level)
    {
        switch (level)
        {
            case "DEBUG": return LogLevel.Debug;
            case "WARNING": return LogLevel.Warning;
            case "ERROR": return LogLevel.Error;
            default: return LogLevel.Log;
        }
    }

    public override string ToString() => $"[{Level}] {Message} ({Source}:{Line})";
}
