package dev.vaplus.anticheat.player.state;

/**
 * Client-declared pose flags and, for 1.21.2+ clients, the raw movement key state from the
 * player-input packet. Owner thread only.
 */
public final class PoseState {

    private boolean sneaking;
    private boolean sprinting;
    private boolean gliding;
    private boolean swimming;
    private boolean glideRequested;
    private long glideRequests;
    private int glideRequestAge;

    private boolean inputKnown;
    private boolean forward;
    private boolean backward;
    private boolean left;
    private boolean right;
    private boolean jump;
    private boolean shift;
    private boolean sprintKey;
    private long lastInputTick;

    public void sneaking(boolean value) {
        this.sneaking = value;
    }

    public void sprinting(boolean value) {
        this.sprinting = value;
    }

    public void gliding(boolean value) {
        this.gliding = value;
        if (!value) {
            glideRequested = false;
        }
    }

    /** Gliding ended, but a glide request made after that metadata stays pending. */
    public void glidingEndedKeepRequest() {
        this.gliding = false;
    }

    public void swimming(boolean value) {
        this.swimming = value;
    }

    /** The client asked to start elytra flight; the server confirms through entity metadata. */
    public void requestGlide() {
        this.glideRequested = true;
        this.glideRequests++;
        this.glideRequestAge = 0;
    }

    /** Glide requests ever made (lets a tracker tell whether a request came after a packet). */
    public long glideRequests() {
        return glideRequests;
    }

    /**
     * Per client tick: an unconfirmed glide request older than {@code maxTicks} is dropped (the
     * server rejects requests without an elytra silently: the flag does not change, so no metadata
     * follows).
     */
    public void ageGlideRequest(int maxTicks) {
        if (!glideRequested || gliding) {
            glideRequestAge = 0;
            return;
        }
        if (++glideRequestAge > maxTicks) {
            glideRequested = false;
            glideRequestAge = 0;
        }
    }

    /** Clears entity-data pose flags (respawn without kept entity data). Raw key input is kept. */
    public void resetFlags() {
        sneaking = false;
        sprinting = false;
        gliding = false;
        swimming = false;
        glideRequested = false;
    }

    public void applyInput(boolean forward, boolean backward, boolean left, boolean right,
                           boolean jump, boolean shift, boolean sprintKey, long clientTick) {
        this.inputKnown = true;
        this.forward = forward;
        this.backward = backward;
        this.left = left;
        this.right = right;
        this.jump = jump;
        this.shift = shift;
        this.sprintKey = sprintKey;
        this.lastInputTick = clientTick;
    }

    public boolean sneaking() {
        return sneaking;
    }

    public boolean sprinting() {
        return sprinting;
    }

    public boolean gliding() {
        return gliding;
    }

    public boolean swimming() {
        return swimming;
    }

    public boolean glideRequested() {
        return glideRequested;
    }

    /** Whether at least one player-input packet was received (1.21.2+ clients). */
    public boolean inputKnown() {
        return inputKnown;
    }

    public boolean forward() {
        return forward;
    }

    public boolean backward() {
        return backward;
    }

    public boolean left() {
        return left;
    }

    public boolean right() {
        return right;
    }

    public boolean jump() {
        return jump;
    }

    public boolean shift() {
        return shift;
    }

    public boolean sprintKey() {
        return sprintKey;
    }

    public long lastInputTick() {
        return lastInputTick;
    }

    /** Forward input as -1, 0 or 1 (vanilla's "zza" sign) from the raw keys. */
    public int forwardImpulse() {
        return (forward ? 1 : 0) - (backward ? 1 : 0);
    }

    /** Strafe input as -1, 0 or 1 (left positive, vanilla's "xxa" sign) from the raw keys. */
    public int strafeImpulse() {
        return (left ? 1 : 0) - (right ? 1 : 0);
    }
}
