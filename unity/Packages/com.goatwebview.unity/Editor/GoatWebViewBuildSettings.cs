using UnityEditor;
using UnityEngine;

/// <summary>
/// 빌드 후처리(GoatWebViewPostGenerateGradleAndroidProject)가 읽는 옵션. ProjectSettings/GoatWebViewSettings.asset에 저장되며
/// Edit > Project Settings > Player > Goat WebView 에서 바꾼다.
/// </summary>
[FilePath("ProjectSettings/GoatWebViewSettings.asset", FilePathAttribute.Location.ProjectFolder)]
public class GoatWebViewBuildSettings : ScriptableSingleton<GoatWebViewBuildSettings>
{
    [Tooltip("Android: http:// 페이지를 열 수 있게 빌드 시 소스셋 매니페스트로 android:usesCleartextTraffic=\"true\"를 병합한다. 기본 꺼짐.")]
    public bool allowCleartextTraffic;

    public void Save()
    {
        Save(true);
    }
}

static class GoatWebViewSettingsProvider
{
    static GUIStyle sectionStyle;
    static GUIStyle labelStyle;
    static GUIStyle labelOnStyle;
    static GUIStyle descriptionStyle;

    // Project Settings 트리에서 Player 아래 하위 항목으로 보인다(경로에 "/"로 중첩).
    [SettingsProvider]
    static SettingsProvider Create() => new SettingsProvider("Project/Player/Goat WebView", SettingsScope.Project)
    {
        label = "Goat WebView",
        guiHandler = _ => Draw(),
        keywords = new[] { "webview", "android", "manifest", "cleartext", "http", "hardwareAccelerated" },
    };

    static void EnsureStyles()
    {
        if (sectionStyle != null) return;
        sectionStyle = new GUIStyle(EditorStyles.boldLabel) { margin = new RectOffset(0, 0, 8, 4) };
        labelStyle = new GUIStyle(EditorStyles.label);
        labelOnStyle = new GUIStyle(EditorStyles.label);
        labelOnStyle.normal.textColor = EditorGUIUtility.isProSkin ? new Color(0.49f, 0.68f, 1f) : new Color(0.05f, 0.33f, 0.76f);
        descriptionStyle = new GUIStyle(EditorStyles.miniLabel) { wordWrap = true, margin = new RectOffset(16, 0, 0, 8) };
        descriptionStyle.normal.textColor = EditorStyles.miniLabel.normal.textColor * 0.85f;
    }

    static void Draw()
    {
        EnsureStyles();
        var settings = GoatWebViewBuildSettings.instance;

        using (new EditorGUILayout.VerticalScope(new GUIStyle { margin = new RectOffset(10, 10, 0, 0) }))
        {
            GUILayout.Label("Android Manifest", sectionStyle);
            if (Option("Uses Cleartext Traffic", "If you need to load plain HTTP content. Applied at build time by merging a manifest overlay.", settings.allowCleartextTraffic, out var allow))
            {
                settings.allowCleartextTraffic = allow;
                settings.Save();
            }

            // 옵션이 아니라 고정 동작이므로 옵션 목록과 분리해 안내 상자로 보여 준다.
            GUILayout.Space(8);
            EditorGUILayout.HelpBox(
                "hardwareAccelerated is always set to true on the Unity activity at build time (required for WebView rendering and rounded corners).\n" +
                "Options are applied only when building for Android.",
                MessageType.Info);
        }
    }

    // UniWebView 설정 화면과 같은 한 줄 구성: 라벨(켜지면 강조색) + 오른쪽 체크박스, 아래 줄 설명. 값이 바뀌면 true.
    static bool Option(string label, string description, bool value, out bool newValue)
    {
        using (new EditorGUILayout.HorizontalScope())
        {
            GUILayout.Label(label, value ? labelOnStyle : labelStyle);
            GUILayout.FlexibleSpace();
            newValue = EditorGUILayout.Toggle(value, GUILayout.Width(18));
        }
        GUILayout.Label(description, descriptionStyle);
        return newValue != value;
    }
}
