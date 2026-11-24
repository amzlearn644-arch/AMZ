using UnityEngine;

[RequireComponent(typeof(Rigidbody))]
public class ArcadeCarController : MonoBehaviour
{
    [Header("Driving")]
    public float acceleration = 24f;
    public float reverseAcceleration = 12f;
    public float maxSpeed = 34f;
    public float steeringPower = 105f;
    public float grip = 7f;
    public float brakePower = 22f;

    Rigidbody body;
    float throttle;
    float steering;
    bool braking;

    void Awake()
    {
        body = GetComponent<Rigidbody>();
        body.mass = 1200f;
        body.drag = 0.12f;
        body.angularDrag = 2.8f;
        body.centerOfMass = new Vector3(0f, -0.45f, 0f);
    }

    void Update()
    {
        float vertical = Input.GetAxisRaw("Vertical");
        float horizontal = Input.GetAxisRaw("Horizontal");
        if (Mathf.Abs(vertical) > 0.01f) throttle = vertical;
        if (Mathf.Abs(horizontal) > 0.01f) steering = horizontal;
        if (Input.GetKeyDown(KeyCode.Space)) braking = true;
        if (Input.GetKeyUp(KeyCode.Space)) braking = false;
    }

    public void SetThrottle(float value) { throttle = Mathf.Clamp(value, -1f, 1f); }
    public void SetSteering(float value) { steering = Mathf.Clamp(value, -1f, 1f); }
    public void SetBraking(bool value) { braking = value; }

    void FixedUpdate()
    {
        Vector3 localVelocity = transform.InverseTransformDirection(body.velocity);
        float speed = body.velocity.magnitude;

        if (throttle > 0f && speed < maxSpeed)
            body.AddForce(transform.forward * throttle * acceleration, ForceMode.Acceleration);
        else if (throttle < 0f && localVelocity.z > -8f)
            body.AddForce(transform.forward * throttle * reverseAcceleration, ForceMode.Acceleration);

        if (braking && speed > 0.1f)
            body.AddForce(-body.velocity.normalized * brakePower, ForceMode.Acceleration);

        float turnFactor = Mathf.Clamp01(speed / 4f);
        float direction = localVelocity.z >= -0.5f ? 1f : -1f;
        body.MoveRotation(body.rotation * Quaternion.Euler(0f, steering * steeringPower * turnFactor * direction * Time.fixedDeltaTime, 0f));

        Vector3 lateralVelocity = transform.right * localVelocity.x;
        body.AddForce(-lateralVelocity * grip, ForceMode.Acceleration);
    }
}
