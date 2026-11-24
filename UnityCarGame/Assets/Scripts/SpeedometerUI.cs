using UnityEngine;
using UnityEngine.UI;

public class SpeedometerUI : MonoBehaviour
{
    public Rigidbody carBody;
    public Text speedText;

    void Update()
    {
        if (carBody != null && speedText != null)
            speedText.text = Mathf.RoundToInt(carBody.velocity.magnitude * 3.6f) + " KM/H";
    }
}
