using UnityEngine;
using UnityEngine.EventSystems;

public class TouchDriveButton : MonoBehaviour, IPointerDownHandler, IPointerUpHandler, IPointerExitHandler
{
    public enum ActionType { Gas, Reverse, Left, Right, Brake }
    public ArcadeCarController car;
    public ActionType action;

    public void OnPointerDown(PointerEventData eventData) => Apply(true);
    public void OnPointerUp(PointerEventData eventData) => Apply(false);
    public void OnPointerExit(PointerEventData eventData) => Apply(false);

    void Apply(bool pressed)
    {
        if (!car) return;
        switch (action)
        {
            case ActionType.Gas: car.SetThrottle(pressed ? 1f : 0f); break;
            case ActionType.Reverse: car.SetThrottle(pressed ? -1f : 0f); break;
            case ActionType.Left: car.SetSteering(pressed ? -1f : 0f); break;
            case ActionType.Right: car.SetSteering(pressed ? 1f : 0f); break;
            case ActionType.Brake: car.SetBrake(pressed); break;
        }
    }
}
