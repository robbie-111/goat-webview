// UnityEditor.Android는 Android Build Support가 설치된 Editor에만 있으므로 Android 대상일 때만 컴파일한다.
#if UNITY_ANDROID
using System;
using System.IO;
using System.Xml;
using UnityEditor.Android;
using UnityEditor.Build;
using UnityEngine;

/// <summary>
/// Android Gradle 프로젝트 생성 직후 unityLibrary 매니페스트를 손본다. XML은 모두 System.Xml로 처리한다.
/// 1. src/main/AndroidManifest.xml: Unity 액티비티(com.unity3d.player.*)의 android:hardwareAccelerated를 true로 고정.
///    Unity 기본값 false에서는 WebView가 소프트웨어로 그려지고 모서리 라운딩(outline 클리핑)이 무시된다.
///    unityLibrary 매니페스트가 이미 선언한 속성이라 병합으로는 못 바꾸므로 직접 고친다.
/// 2. 옵션(GoatWebViewBuildSettings)이 켜져 있으면 src/debug, src/release 에 AndroidManifest.xml 을 만든다.
///    Gradle이 빌드 타입 소스셋 매니페스트를 main과 자동 병합하므로 settings.gradle·build.gradle·Assets는 건드리지 않는다.
/// </summary>
public class GoatWebViewPostGenerateGradleAndroidProject : IPostGenerateGradleAndroidProject
{
    private const string ANDROID_NS = "http://schemas.android.com/apk/res/android";
    private const string UNITY_ACTIVITY_PREFIX = "com.unity3d.player.";
    private static readonly string[] BUILD_TYPES = { "debug", "release" };

    int IOrderedCallback.callbackOrder => 100;

    public void OnPostGenerateGradleAndroidProject(string path)
    {
        GoatWebViewBuildSettings settings = GoatWebViewBuildSettings.instance;
        SetHardwareAccelerated(Path.Combine(path, "src", "main", "AndroidManifest.xml"));
        if (settings.allowCleartextTraffic)
        {
            GenerateOverlayManifests(path, settings);
        }
        else
        {
            // Unity는 이전 빌드의 Gradle 프로젝트를 재사용하므로, 옵션을 끄면 전에 만든 오버레이를 지운다.
            RemoveOverlayManifests(path);
        }
    }

    // 1. main 매니페스트의 Unity 액티비티에 hardwareAccelerated="true"
    private void SetHardwareAccelerated(string manifestPath)
    {
        if (File.Exists(manifestPath) is false)
        {
            Debug.LogWarning($"GoatWebViewPostGenerateGradleAndroidProject::Could not find AndroidManifest.xml file at: {manifestPath}");
            return;
        }

        try
        {
            XmlDocument xmlDoc = new XmlDocument();
            xmlDoc.Load(manifestPath);

            XmlNode applicationNode = xmlDoc.SelectSingleNode("manifest/application");
            if (applicationNode == null)
            {
                Debug.LogError("GoatWebViewPostGenerateGradleAndroidProject::Could not find application tag");
                return;
            }

            bool changed = false;
            foreach (XmlElement activity in applicationNode.SelectNodes("activity"))
            {
                // Unity 액티비티만 다룬다. 사용자 정의 액티비티는 그대로 둔다.
                XmlAttribute nameAttr = activity.GetAttributeNode("name", ANDROID_NS);
                if (nameAttr == null || nameAttr.Value.StartsWith(UNITY_ACTIVITY_PREFIX) is false)
                {
                    continue;
                }
                changed |= SetAndroidAttribute(xmlDoc, activity, "hardwareAccelerated", "true");
            }

            if (changed)
            {
                xmlDoc.Save(manifestPath);
                Debug.Log("GoatWebViewPostGenerateGradleAndroidProject::Set android:hardwareAccelerated=\"true\" on Unity activity.");
            }
        }
        catch (XmlException e)
        {
            Debug.LogError($"GoatWebViewPostGenerateGradleAndroidProject::Failed to parse AndroidManifest.xml: {e.Message}");
        }
        catch (IOException e)
        {
            Debug.LogError($"GoatWebViewPostGenerateGradleAndroidProject::Failed to save AndroidManifest.xml: {e.Message}");
        }
        catch (Exception e)
        {
            Debug.LogException(e);
        }
    }

    // 2. 옵션을 담은 소스셋 매니페스트. <manifest xmlns:android="..."><application android:usesCleartextTraffic="true"/></manifest>
    private void GenerateOverlayManifests(string unityLibraryPath, GoatWebViewBuildSettings settings)
    {
        try
        {
            XmlDocument xmlDoc = new XmlDocument();
            xmlDoc.AppendChild(xmlDoc.CreateXmlDeclaration("1.0", "utf-8", null));
            xmlDoc.AppendChild(xmlDoc.CreateComment(" Generated at build time by Goat WebView settings (Project Settings > Player > Goat WebView). Merged into the main manifest. "));

            XmlElement manifest = xmlDoc.CreateElement("manifest");
            manifest.SetAttribute("xmlns:android", ANDROID_NS);
            xmlDoc.AppendChild(manifest);

            XmlElement application = xmlDoc.CreateElement("application");
            manifest.AppendChild(application);
            if (settings.allowCleartextTraffic)
            {
                SetAndroidAttribute(xmlDoc, application, "usesCleartextTraffic", "true");
            }

            foreach (string buildType in BUILD_TYPES)
            {
                string directory = Path.Combine(unityLibraryPath, "src", buildType);
                Directory.CreateDirectory(directory);
                xmlDoc.Save(Path.Combine(directory, "AndroidManifest.xml"));
            }
            Debug.Log("GoatWebViewPostGenerateGradleAndroidProject::Generated src/{debug,release}/AndroidManifest.xml (usesCleartextTraffic=true).");
        }
        catch (IOException e)
        {
            Debug.LogError($"GoatWebViewPostGenerateGradleAndroidProject::Failed to save overlay AndroidManifest.xml: {e.Message}");
        }
        catch (Exception e)
        {
            Debug.LogException(e);
        }
    }

    private void RemoveOverlayManifests(string unityLibraryPath)
    {
        foreach (string buildType in BUILD_TYPES)
        {
            string manifestPath = Path.Combine(unityLibraryPath, "src", buildType, "AndroidManifest.xml");
            if (File.Exists(manifestPath))
            {
                File.Delete(manifestPath);
                Debug.Log($"GoatWebViewPostGenerateGradleAndroidProject::Removed stale overlay {manifestPath}");
            }
        }
    }

    /// <summary>android:attrName 속성을 만들거나 값을 바꾼다. 값이 이미 같으면 false.</summary>
    private static bool SetAndroidAttribute(XmlDocument xmlDoc, XmlElement element, string attrName, string attrValue)
    {
        XmlAttribute attr = element.GetAttributeNode(attrName, ANDROID_NS);
        if (attr == null)
        {
            attr = xmlDoc.CreateAttribute("android", attrName, ANDROID_NS);
            element.Attributes.Append(attr);
        }

        if (attr.Value == attrValue)
        {
            return false;
        }

        attr.Value = attrValue;
        return true;
    }
}
#endif
