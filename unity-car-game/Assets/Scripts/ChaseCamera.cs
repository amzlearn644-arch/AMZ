using UnityEngine;

public class ChaseCamera : MonoBehaviour
{
    public Transform target;
    public Vector3 offset = new Vector3(0f, 5.5f, -9f);
    public float smooth = 6f;
    public float lookAhead = 3f;

    void LateUpdate()
    {
        if (!target) return;
        Vector3 desired = target.TransformPoint(offset);
        transform.position = Vector3.Lerp(transform.position, desired, smooth * Time.deltaTime);
        Vector3 lookPoint = target.position + Vector3.up * 1.1f + target.forward * lookAhead;
        transform.rotation = Quaternion.Slerp(transform.rotation,
            Quaternion.LookRotation(lookPoint - transform.position), smooth * Time.deltaTime);
    }
}
