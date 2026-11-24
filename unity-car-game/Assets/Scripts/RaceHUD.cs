using UnityEngine;
using UnityEngine.UI;

public class RaceHUD : MonoBehaviour
{
    public ArcadeCarController car;
    public Text speedLabel;

    void Update()
    {
        if (car && speedLabel)
            speedLabel.text = Mathf.RoundToInt(car.SpeedKmh).ToString("000") + " KM/H";
    }
}
