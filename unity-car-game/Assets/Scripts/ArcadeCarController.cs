using UnityEngine;

[RequireComponent(typeof(Rigidbody))]
public class ArcadeCarController : MonoBehaviour
{
    [Header("Driving")]
    [SerializeField] float acceleration = 24f;
    [SerializeField] float reverseAcceleration = 11f;
    [SerializeField] float maxSpeed = 38f;
    [SerializeField] float steeringPower = 105f;
    [SerializeField] float lateralGrip = 8f;
    [SerializeField] float brakePower = 22f;

    Rigidbody body;
    float throttle;
    float steering;
    bool braking;

    public float SpeedKmh => body ? body.velocity.magnitude * 3.6f : 0f;

    void Awake()
    {
        body = GetComponent<Rigidbody>();
        body.mass = 1250f;
        body.drag = 0.12f;
        body.angularDrag = 2.8f;
        body.centerOfMass = new Vector3(0f, -0.45f, 0f);
    }

    void Update()
    {
        // Keyboard controls for Editor and desktop builds.
        float vertical = Input.GetAxisRaw("Vertical");
        float horizontal = Input.GetAxisRaw("Horizontal");
        if (Mathf.Abs(vertical) > 0.01f) throttle = vertical;
        else if (Mathf.Abs(horizontal) < 0.01f && !braking) throttle = 0f;
        if (Mathf.Abs(horizontal) > 0.01f) steering = horizontal;
        else steering = 0f;
        if (Input.GetKeyDown(KeyCode.Space)) braking = true;
        if (Input.GetKeyUp(KeyCode.Space)) braking = false;
    }

    public void SetThrottle(float value) => throttle = Mathf.Clamp(value, -1f, 1f);
    public void SetSteering(float value) => steering = Mathf.Clamp(value, -1f, 1f);
    public void SetBrake(bool value) => braking = value;

    void FixedUpdate()
    {
        Vector3 localVelocity = transform.InverseTransformDirection(body.velocity);
        if (throttle > 0f && body.velocity.magnitude < maxSpeed)
            body.AddForce(transform.forward * throttle * acceleration, ForceMode.Acceleration);
        else if (throttle < 0f && localVelocity.z > -9f)
            body.AddForce(transform.forward * throttle * reverseAcceleration, ForceMode.Acceleration);

        if (braking && body.velocity.sqrMagnitude > 0.1f)
            body.AddForce(-body.velocity.normalized * brakePower, ForceMode.Acceleration);

        float speedFactor = Mathf.Clamp01(body.velocity.magnitude / 3f);
        float direction = localVelocity.z >= -0.5f ? 1f : -1f;
        body.MoveRotation(body.rotation * Quaternion.Euler(0f, steering * steeringPower * speedFactor * direction * Time.fixedDeltaTime, 0f));

        Vector3 lateralVelocity = transform.right * localVelocity.x;
        body.AddForce(-lateralVelocity * lateralGrip, ForceMode.Acceleration);
    }
}
