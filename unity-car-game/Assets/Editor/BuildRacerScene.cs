using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.UI;
using UnityEngine.EventSystems;
using UnityEngine.SceneManagement;

public static class BuildRacerScene
{
    [MenuItem("AMZ Racer/Create Racing Scene")]
    public static void CreateScene()
    {
        var scene = EditorSceneManager.NewScene(NewSceneSetup.DefaultGameObjects, NewSceneMode.Single);
        RenderSettings.ambientLight = new Color(0.68f, 0.7f, 0.74f);
        RenderSettings.fog = true;
        RenderSettings.fogColor = new Color(0.62f, 0.72f, 0.8f);
        RenderSettings.fogDensity = 0.004f;

        Material grass = MakeMaterial("Grass", new Color(0.12f, 0.32f, 0.17f));
        Material asphalt = MakeMaterial("Track Asphalt", new Color(0.11f, 0.12f, 0.14f));
        Material line = MakeMaterial("Track Lines", new Color(0.92f, 0.91f, 0.82f));
        Material blue = MakeMaterial("AMZ Blue", new Color(0.025f, 0.22f, 0.9f), 0.65f);
        Material glass = MakeMaterial("Dark Glass", new Color(0.035f, 0.075f, 0.11f), 0.3f);
        Material rubber = MakeMaterial("Tire Rubber", new Color(0.018f, 0.02f, 0.025f));

        GameObject field = GameObject.CreatePrimitive(PrimitiveType.Plane);
        field.name = "Infield";
        field.transform.localScale = new Vector3(20f, 1f, 20f);
        field.GetComponent<Renderer>().sharedMaterial = grass;

        const int count = 72;
        const float radiusX = 66f, radiusZ = 42f, roadWidth = 13f;
        for (int i = 0; i < count; i++)
        {
            float a = i * Mathf.PI * 2f / count;
            float b = (i + 1) * Mathf.PI * 2f / count;
            Vector3 p0 = new Vector3(Mathf.Cos(a) * radiusX, 0.05f, Mathf.Sin(a) * radiusZ);
            Vector3 p1 = new Vector3(Mathf.Cos(b) * radiusX, 0.05f, Mathf.Sin(b) * radiusZ);
            Vector3 delta = p1 - p0;
            GameObject segment = GameObject.CreatePrimitive(PrimitiveType.Cube);
            segment.name = "Road Segment";
            segment.transform.position = (p0 + p1) * 0.5f;
            segment.transform.rotation = Quaternion.LookRotation(delta);
            segment.transform.localScale = new Vector3(roadWidth, 0.35f, delta.magnitude + 1.25f);
            segment.GetComponent<Renderer>().sharedMaterial = asphalt;
        }

        GameObject startLine = GameObject.CreatePrimitive(PrimitiveType.Cube);
        startLine.name = "Start Finish Line";
        startLine.transform.position = new Vector3(radiusX, 0.25f, 0f);
        startLine.transform.localScale = new Vector3(roadWidth, 0.04f, 1.25f);
        startLine.GetComponent<Renderer>().sharedMaterial = line;

        GameObject car = new GameObject("AMZ Player Car");
        car.transform.position = new Vector3(radiusX, 1.05f, -5f);
        car.transform.rotation = Quaternion.Euler(0f, 90f, 0f);
        Rigidbody rb = car.AddComponent<Rigidbody>();
        ArcadeCarController controller = car.AddComponent<ArcadeCarController>();

        Part(car.transform, "Main Body", PrimitiveType.Cube, new Vector3(0, 0, 0), new Vector3(2f, 0.62f, 4.1f), blue);
        Part(car.transform, "Cabin", PrimitiveType.Cube, new Vector3(0, 0.52f, -0.28f), new Vector3(1.52f, 0.68f, 1.85f), glass);
        Part(car.transform, "Hood", PrimitiveType.Cube, new Vector3(0, 0.25f, 1.35f), new Vector3(1.85f, 0.16f, 1.15f), blue);
        foreach (float x in new[] { -1f, 1f })
        foreach (float z in new[] { -1.28f, 1.28f })
        {
            GameObject wheel = Part(car.transform, "Wheel", PrimitiveType.Cylinder,
                new Vector3(x, -0.32f, z), new Vector3(0.34f, 0.19f, 0.68f), rubber);
            wheel.transform.localRotation = Quaternion.Euler(0f, 0f, 90f);
        }
        foreach (Collider col in car.GetComponentsInChildren<Collider>())
            if (col.gameObject != car) Object.DestroyImmediate(col);

        Camera cam = Camera.main;
        cam.transform.position = car.transform.TransformPoint(new Vector3(0f, 5.5f, -9f));
        cam.transform.rotation = Quaternion.LookRotation(car.transform.position - cam.transform.position);
        ChaseCamera chase = cam.gameObject.AddComponent<ChaseCamera>();
        chase.target = car.transform;

        BuildCanvas(car, controller, rb);
        EditorSceneManager.SaveScene(scene, "Assets/Scenes/AMZRacer.unity");
        EditorUtility.DisplayDialog("AMZ Racer", "Racing scene created. Press Play to test.", "Let's race!");
    }

    static Material MakeMaterial(string name, Color color, float metallic = 0f)
    {
        Shader shader = Shader.Find("Standard");
        if (!shader) shader = Shader.Find("Universal Render Pipeline/Lit");
        Material material = new Material(shader) { name = name, color = color };
        if (material.HasProperty("_Metallic")) material.SetFloat("_Metallic", metallic);
        if (material.HasProperty("_Smoothness")) material.SetFloat("_Smoothness", 0.45f);
        return material;
    }

    static GameObject Part(Transform parent, string name, PrimitiveType type, Vector3 position, Vector3 scale, Material material)
    {
        GameObject part = GameObject.CreatePrimitive(type);
        part.name = name;
        part.transform.SetParent(parent, false);
        part.transform.localPosition = position;
        part.transform.localScale = scale;
        part.GetComponent<Renderer>().sharedMaterial = material;
        return part;
    }

    static void BuildCanvas(GameObject car, ArcadeCarController controller, Rigidbody rb)
    {
        GameObject canvasObject = new GameObject("Race HUD", typeof(Canvas), typeof(CanvasScaler), typeof(GraphicRaycaster), typeof(RaceHUD));
        Canvas canvas = canvasObject.GetComponent<Canvas>();
        canvas.renderMode = RenderMode.ScreenSpaceOverlay;
        CanvasScaler scaler = canvasObject.GetComponent<CanvasScaler>();
        scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
        scaler.referenceResolution = new Vector2(1920f, 1080f);
        if (!Object.FindObjectOfType<EventSystem>())
            new GameObject("EventSystem", typeof(EventSystem), typeof(StandaloneInputModule));

        Text speed = MakeText(canvasObject.transform, "Speed", "000 KM/H", new Vector2(0.84f, 0.9f), new Vector2(300f, 90f), 42);
        speed.alignment = TextAnchor.MiddleCenter;
        speed.fontStyle = FontStyle.Bold;
        RaceHUD hud = canvasObject.GetComponent<RaceHUD>();
        hud.car = controller;
        hud.speedLabel = speed;

        AddButton(canvasObject.transform, controller, "LEFT", TouchDriveButton.ActionType.Left, new Vector2(0.1f, 0.16f));
        AddButton(canvasObject.transform, controller, "RIGHT", TouchDriveButton.ActionType.Right, new Vector2(0.25f, 0.16f));
        AddButton(canvasObject.transform, controller, "BRAKE", TouchDriveButton.ActionType.Brake, new Vector2(0.75f, 0.16f));
        AddButton(canvasObject.transform, controller, "GAS", TouchDriveButton.ActionType.Gas, new Vector2(0.9f, 0.16f));
    }

    static Text MakeText(Transform parent, string name, string value, Vector2 anchor, Vector2 size, int fontSize)
    {
        GameObject obj = new GameObject(name, typeof(RectTransform), typeof(CanvasRenderer), typeof(Text));
        obj.transform.SetParent(parent, false);
        RectTransform rect = obj.GetComponent<RectTransform>();
        rect.anchorMin = rect.anchorMax = anchor;
        rect.anchoredPosition = Vector2.zero;
        rect.sizeDelta = size;
        Text text = obj.GetComponent<Text>();
        text.text = value;
        text.font = Resources.GetBuiltinResource<Font>("Arial.ttf");
        text.fontSize = fontSize;
        text.color = Color.white;
        return text;
    }

    static void AddButton(Transform parent, ArcadeCarController car, string label, TouchDriveButton.ActionType action, Vector2 anchor)
    {
        GameObject obj = new GameObject(label, typeof(RectTransform), typeof(CanvasRenderer), typeof(Image), typeof(TouchDriveButton));
        obj.transform.SetParent(parent, false);
        RectTransform rect = obj.GetComponent<RectTransform>();
        rect.anchorMin = rect.anchorMax = anchor;
        rect.anchoredPosition = Vector2.zero;
        rect.sizeDelta = new Vector2(150f, 120f);
        obj.GetComponent<Image>().color = new Color(0.025f, 0.055f, 0.1f, 0.76f);
        TouchDriveButton button = obj.GetComponent<TouchDriveButton>();
        button.car = car;
        button.action = action;
        Text caption = MakeText(obj.transform, "Caption", label, new Vector2(0.5f, 0.5f), new Vector2(140f, 100f), 25);
        caption.alignment = TextAnchor.MiddleCenter;
        caption.fontStyle = FontStyle.Bold;
    }
}
