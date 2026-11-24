using System.IO;
using UnityEditor;
using UnityEditor.Build.Reporting;
using UnityEditor.SceneManagement;
using UnityEngine;

public static class BuildAndroid
{
    public static void Build()
    {
        const string scenePath = "Assets/Scenes/AMZRacer.unity";
        Directory.CreateDirectory("Assets/Scenes");

        if (!File.Exists(scenePath))
            BuildRacerScene.CreateScene();

        if (!File.Exists(scenePath))
            throw new System.Exception("Racing scene was not generated.");

        EditorSceneManager.OpenScene(scenePath);

        PlayerSettings.companyName = "AMZ";
        PlayerSettings.productName = "AMZ Racer";
        PlayerSettings.applicationIdentifier = "com.amz.racer";
        PlayerSettings.bundleVersion = "1.0.0";
        PlayerSettings.Android.bundleVersionCode = 1;
        PlayerSettings.defaultInterfaceOrientation = UIOrientation.LandscapeLeft;
        PlayerSettings.Android.minSdkVersion = AndroidSdkVersions.AndroidApiLevel23;
        EditorUserBuildSettings.buildAppBundle = false;
        EditorUserBuildSettings.androidBuildSystem = AndroidBuildSystem.Gradle;

        string output = Path.GetFullPath("Build/Android/AMZ-Racer.apk");
        Directory.CreateDirectory(Path.GetDirectoryName(output));

        var options = new BuildPlayerOptions
        {
            scenes = new[] { scenePath },
            locationPathName = output,
            target = BuildTarget.Android,
            options = BuildOptions.None
        };

        BuildReport report = BuildPipeline.BuildPlayer(options);
        if (report.summary.result != BuildResult.Succeeded)
            throw new System.Exception("Android build failed: " + report.summary.result);
        Debug.Log("APK created: " + output);
    }
}
