package dev.vaplus.anticheat.movement;

/**
 * One rotation change as seen by {@link dev.vaplus.anticheat.check.RotationCheck}s. Reused per
 * player; checks must not keep a reference beyond the callback.
 */
public final class LookChange {

    private float fromYaw;
    private float fromPitch;
    private float toYaw;
    private float toPitch;
    private boolean teleport;
    private long timestampNanos;

    /** Pipeline only. */
    public void assign(float fromYaw, float fromPitch, float toYaw, float toPitch, boolean teleport, long timestampNanos) {
        this.fromYaw = fromYaw;
        this.fromPitch = fromPitch;
        this.toYaw = toYaw;
        this.toPitch = toPitch;
        this.teleport = teleport;
        this.timestampNanos = timestampNanos;
    }

    /** The rotation was forced by a server teleport and says nothing about the player's aim. */
    public boolean teleport() {
        return teleport;
    }

    public float fromYaw() {
        return fromYaw;
    }

    public float fromPitch() {
        return fromPitch;
    }

    public float toYaw() {
        return toYaw;
    }

    public float toPitch() {
        return toPitch;
    }

    /** Raw yaw difference; the client does not wrap yaw, so this can exceed 360. */
    public float deltaYaw() {
        return toYaw - fromYaw;
    }

    /** Yaw difference wrapped to [-180, 180). */
    public float wrappedDeltaYaw() {
        return wrapDegrees(toYaw - fromYaw);
    }

    public float deltaPitch() {
        return toPitch - fromPitch;
    }

    public long timestampNanos() {
        return timestampNanos;
    }

    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped >= 180.0f) {
            wrapped -= 360.0f;
        }
        if (wrapped < -180.0f) {
            wrapped += 360.0f;
        }
        return wrapped;
    }
}
