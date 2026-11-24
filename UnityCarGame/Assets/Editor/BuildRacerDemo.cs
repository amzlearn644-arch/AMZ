using UnityEditor;
using UnityEditor.SceneManagement;
using UnityEngine;
using UnityEngine.UI;
using UnityEngine.EventSystems;

public static class BuildRacerDemo
{
    [MenuItem("AMZ Racer/Generate Demo Scene")]
    public static void Generate()
    {
        var scene = EditorSceneManager.NewScene(NewSceneSetup.DefaultGameObjects, NewSceneMode.Single);
        RenderSettings.ambientLight = new Color(0.65f, 0.68f, 0.72f);
        RenderSettings.fog = true;
        RenderSettings.fogColor = new Color(0.62f, 0.72f, 0.80f);
        RenderSettings.fogMode = FogMode.Exponential;
        RenderSettings.fogDensity = 0.004f;

        Material grass = MakeMaterial("Grass", new Color(0.12f, 0.34f, 0.18f));
        Material asphalt = MakeMaterial("Road", new Color(0.10f, 0.11f, 0.13f));
        Material blue = MakeMaterial("Car Blue", new Color(0.025f, 0.22f, 0.92f), 0.65f);
        Material glass = MakeMaterial("Dark Glass", new Color(0.035f, 0.07f, 0.11f), 0.2f);
        Material rubber = MakeMaterial("Tires", new Color(0.02f, 0.02f, 0.025f));
        Material white = MakeMaterial("Track Stripe", new Color(0.92f, 0.92f, 0.88f));

        GameObject field = GameObject.CreatePrimitive(PrimitiveType.Plane);
        field.name = "Infield";
        field.transform.localScale = new Vector3(22f, 1f, 16f);
        field.GetComponent<Renderer>().sharedMaterial = grass;

        const int count = 72;
        const float rx = 68f, rz = 42f, roadWidth = 13f;
        for (int i = 0; i < count; i++)
        {
            float a = i * Mathf.PI * 2f / count;
            float b = (i + 1) * Mathf.PI * 2f / count;
            Vector3 p0 = new Vector3(Mathf.Cos(a) * rx, 0.03f, Mathf.Sin(a) * rz);
            Vector3 p1 = new Vector3(Mathf.Cos(b) * rx, 0.03f, Mathf.Sin(b) * rz);
            Vector3 delta = p1 - p0;
            GameObject road = GameObject.CreatePrimitive(PrimitiveType.Cube);
            road.name = "Oval Road";
            road.transform.position = (p0 + p1) * 0.5f;
            road.transform.rotation = Quaternion.LookRotation(delta);
            road.transform.localScale = new Vector3(roadWidth, 0.35f, delta.magnitude + 1.5f);
            road.GetComponent<Renderer>().sharedMaterial = asphalt;
        }

        GameObject startLine = GameObject.CreatePrimitive(PrimitiveType.Cube);
        startLine.name = "Start Finish Line";
        startLine.transform.position = new Vector3(rx, 0.23f, 0f);
        startLine.transform.localScale = new Vector3(roadWidth, 0.035f, 1.1f);
        startLine.GetComponent<Renderer>().sharedMaterial = white;

        GameObject car = new GameObject("Player Car");
        car.transform.position = new Vector3(rx, 1.1f, -4.5f);
        car.transform.rotation = Quaternion.Euler(0f, 90f, 0f);
        Rigidbody rb = car.AddComponent<Rigidbody>();
        rb.mass = 1200f;
        car.AddComponent<ArcadeCarController>();

        Part(car.transform, "Main Body", PrimitiveType.Cube, new Vector3(0f, 0f, 0f), new Vector3(2f, 0.62f, 4.1f), blue);
        Part(car.transform, "Cabin", PrimitiveType.Cube, new Vector3(0f, 0.48f, -0.25f), new Vector3(1.55f, 0.68f, 1.9f), glass);
        Part(car.transform, "Hood", PrimitiveType.Cube, new Vector3(0f, 0.22f, 1.35f), new Vector3(1.85f, 0.18f, 1.2f), blue);
        foreach (float x in new float[] { -1f, 1f })
        foreach (float z in new float[] { -1.25f, 1.25f })
        {
            GameObject wheel = Part(car.transform, "Wheel", PrimitiveType.Cylinder,
                new Vector3(x, -0.32f, z), new Vector3(0.34f, 0.18f, 0.68f), rubber);
            wheel.transform.localRotation = Quaternion.Euler(0f, 0f, 90f);
        }
        foreach (Collider c in car.GetComponentsInChildren<Collider>())
            if (c.gameObject != car) Object.DestroyImmediate(c);

        Camera cam = Camera.main;
        cam.transform.position = car.transform.TransformPoint(new Vector3(0f, 5.5f, -9f));
        cam.transform.rotation = Quaternion.LookRotation(car.transform.position - cam.transform.position);
        FollowCamera follow = cam.gameObject.AddComponent<FollowCamera>();
        follow.target = car.transform;

        GameObject canvasObject = new GameObject("Game HUD", typeof(Canvas), typeof(CanvasScaler), typeof(GraphicRaycaster));
        Canvas canvas = canvasObject.GetComponent<Canvas>();
        canvas.renderMode = RenderMode.ScreenSpaceOverlay;
        CanvasScaler scaler = canvasObject.GetComponent<CanvasScaler>();
        scaler.uiScaleMode = CanvasScaler.ScaleMode.ScaleWithScreenSize;
        scaler.referenceResolution = new Vector2(1920f, 1080f);
        if (Object.FindObjectOfType<EventSystem>() == null)
            new GameObject("EventSystem", typeof(EventSystem), typeof(StandaloneInputModule));

        Text speed = CreateText(canvasObject.transform, "Speed", "0 KM/H", new Vector2(0.84f, 0.9f), new Vector2(320f, 90f), 38);
        speed.alignment = TextAnchor.MiddleCenter;
        speed.fontStyle = FontStyle.Bold;
        SpeedometerUI meter = canvasObject.AddComponent<SpeedometerUI>();
        meter.carBody = rb;
        meter.speedText = speed;

        AddButton(canvasObject.transform, car, "LEFT", MobileHoldButton.Control.Left, new Vector2(0.10f, 0.16f));
        AddButton(canvasObject.transform, car, "RIGHT", MobileHoldButton.Control.Right, new Vector2(0.25f, 0.16f));
        AddButton(canvasObject.transform, car, "BRAKE", MobileHoldButton.Control.Brake, new Vector2(0.75f, 0.16f));
        AddButton(canvasObject.transform, car, "GAS", MobileHoldButton.Control.Accelerate, new Vector2(0.90f, 0.16f));

        EditorSceneManager.SaveScene(scene, "Assets/Scenes/RacerDemo.unity");
        EditorUtility.DisplayDialog("AMZ Racer", "Scene generated. Press Play to test the car.", "OK");
    }

    static Material MakeMaterial(string name, Color color, float metallic = 0f)
    {
        Shader shader = Shader.Find("Standard");
        if (shader == null) shader = Shader.Find("Universal Render Pipeline/Lit");
        Material material = new Material(shader);
        material.name = name;
        material.color = color;
        if (material.HasProperty("_Metallic")) material.SetFloat("_Metallic", metallic);
        return material;
    }

    static GameObject Part(Transform parent, string name, PrimitiveType type, Vector3 pos, Vector3 scale, Material material)
    {
        GameObject part = GameObject.CreatePrimitive(type);
        part.name = name;
        part.transform.SetParent(parent, false);
        part.transform.localPosition = pos;
        part.transform.localScale = scale;
        part.GetComponent<Renderer>().sharedMaterial = material;
        return part;
    }

    static Text CreateText(Transform parent, string name, string value, Vector2 anchor, Vector2 size, int fontSize)
    {
        GameObject go = new GameObject(name, typeof(RectTransform), typeof(CanvasRenderer), typeof(Text));
        go.transform.SetParent(parent, false);
        RectTransform rect = go.GetComponent<RectTransform>();
        rect.anchorMin = rect.anchorMax = anchor;
        rect.anchoredPosition = Vector2.zero;
        rect.sizeDelta = size;
        Text text = go.GetComponent<Text>();
        text.text = value;
        text.font = Resources.GetBuiltinResource<Font>("Arial.ttf");
        text.fontSize = fontSize;
        text.color = Color.white;
        return text;
    }

    static void AddButton(Transform parent, GameObject car, string label, MobileHoldButton.Control control, Vector2 anchor)
    {
        GameObject go = new GameObject(label, typeof(RectTransform), typeof(CanvasRenderer), typeof(Image), typeof(MobileHoldButton));
        go.transform.SetParent(parent, false);
        RectTransform rect = go.GetComponent<RectTransform>();
        rect.anchorMin = rect.anchorMax = anchor;
        rect.anchoredPosition = Vector2.zero;
        rect.sizeDelta = new Vector2(150f, 120f);
        go.GetComponent<Image>().color = new Color(0.03f, 0.07f, 0.13f, 0.76f);
        MobileHoldButton hold = go.GetComponent<MobileHoldButton>();
        hold.car = car.GetComponent<ArcadeCarController>();
        hold.control = control;
        Text text = CreateText(go.transform, "Label", label, new Vector2(0.5f, 0.5f), new Vector2(140f, 100f), 25);
        text.alignment = TextAnchor.MiddleCenter;
    }
}
