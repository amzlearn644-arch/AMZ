using UnityEngine;
using UnityEngine.EventSystems;

public class MobileHoldButton : MonoBehaviour, IPointerDownHandler, IPointerUpHandler, IPointerExitHandler
{
    public ArcadeCarController car;
    public Control control;
    public enum Control { Accelerate, Reverse, Left, Right, Brake }

    public void OnPointerDown(PointerEventData eventData) { Apply(true); }
    public void OnPointerUp(PointerEventData eventData) { Apply(false); }
    public void OnPointerExit(PointerEventData eventData) { Apply(false); }

    void Apply(bool pressed)
    {
        if (car == null) return;
        switch (control)
        {
            case Control.Accelerate: car.SetThrottle(pressed ? 1f : 0f); break;
            case Control.Reverse: car.SetThrottle(pressed ? -1f : 0f); break;
            case Control.Left: car.SetSteering(pressed ? -1f : 0f); break;
            case Control.Right: car.SetSteering(pressed ? 1f : 0f); break;
            case Control.Brake: car.SetBraking(pressed); break;
        }
    }
}
