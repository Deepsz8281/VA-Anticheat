package dev.vaplus.anticheat.player.state;

/**
 * Last position and rotation reported by the client plus the last position that passed all
 * movement checks (setback target). Written only on the owner thread; {@code worldEpoch} is
 * volatile because scheduled setbacks compare it from the entity's region thread.
 */
public final class MovementState {

    private double x;
    private double y;
    private double z;
    private double lastX;
    private double lastY;
    private double lastZ;
    private float yaw;
    private float pitch;
    private float lastYaw;
    private float lastPitch;
    private boolean onGround;
    private boolean lastOnGround;
    private boolean horizontalCollision;
    private boolean positionKnown;
    private boolean rotationKnown;
    private int ticksSincePosition;
    private long positionPackets;

    private double safeX;
    private double safeY;
    private double safeZ;
    private boolean safeKnown;
    private boolean setbackRequested;

    private volatile int worldEpoch;

    public void applyPosition(double x, double y, double z) {
        this.lastX = this.x;
        this.lastY = this.y;
        this.lastZ = this.z;
        this.x = x;
        this.y = y;
        this.z = z;
        this.positionKnown = true;
        this.ticksSincePosition = 0;
        this.positionPackets++;
    }

    public void applyRotation(float yaw, float pitch) {
        this.lastYaw = this.yaw;
        this.lastPitch = this.pitch;
        this.yaw = yaw;
        this.pitch = pitch;
        this.rotationKnown = true;
    }

    public void applyFlags(boolean onGround, boolean horizontalCollision) {
        this.lastOnGround = this.onGround;
        this.onGround = onGround;
        this.horizontalCollision = horizontalCollision;
    }

    /** Called once per client tick. */
    public void tick() {
        if (ticksSincePosition < Integer.MAX_VALUE) {
            ticksSincePosition++;
        }
    }

    /** Forget the position (respawn / world change); the next position is treated as the first. */
    public void invalidatePosition() {
        positionKnown = false;
        rotationKnown = false;
        safeKnown = false;
        setbackRequested = false;
    }

    /**
     * Server side of a world change: called as soon as the respawn/join packet is sent, so setbacks
     * captured for the old world are discarded even before the client acknowledged the change.
     */
    public void advanceWorldEpoch() {
        worldEpoch++;
    }

    /**
     * Pipeline, after the position checks of a packet: stores the position as setback target unless
     * a setback was requested since the previous position (by a position, packet or tick check),
     * then clears the request.
     */
    public void finishChecks() {
        if (!setbackRequested && positionKnown) {
            safeX = x;
            safeY = y;
            safeZ = z;
            safeKnown = true;
        }
        setbackRequested = false;
    }

    public void markSetbackRequested() {
        setbackRequested = true;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public double lastX() {
        return lastX;
    }

    public double lastY() {
        return lastY;
    }

    public double lastZ() {
        return lastZ;
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    public float lastYaw() {
        return lastYaw;
    }

    public float lastPitch() {
        return lastPitch;
    }

    public boolean onGround() {
        return onGround;
    }

    public boolean lastOnGround() {
        return lastOnGround;
    }

    public boolean horizontalCollision() {
        return horizontalCollision;
    }

    public boolean positionKnown() {
        return positionKnown;
    }

    /** False until the first rotation after join/respawn, whose delta is meaningless. */
    public boolean rotationKnown() {
        return rotationKnown;
    }

    /** Client ticks since the last position packet (exact for 1.21.2+ clients). */
    public int ticksSincePosition() {
        return ticksSincePosition;
    }

    public long positionPackets() {
        return positionPackets;
    }

    public boolean safeKnown() {
        return safeKnown;
    }

    public double safeX() {
        return safeX;
    }

    public double safeY() {
        return safeY;
    }

    public double safeZ() {
        return safeZ;
    }

    public boolean setbackRequested() {
        return setbackRequested;
    }

    public int worldEpoch() {
        return worldEpoch;
    }
}
