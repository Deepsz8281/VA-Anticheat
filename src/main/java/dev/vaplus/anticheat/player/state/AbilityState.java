package dev.vaplus.anticheat.player.state;

import com.github.retrooper.packetevents.protocol.player.GameMode;

/**
 * Game mode and abilities as acknowledged by the client (updated from outbound packets once the
 * bracketing marker is acknowledged). Volatile where commands read them.
 */
public final class AbilityState {

    /** Vanilla default fly speed sent in the abilities packet. */
    public static final float DEFAULT_FLY_SPEED = 0.05f;
    /** Vanilla default walk speed (the "FOV modifier" field of the abilities packet). */
    public static final float DEFAULT_WALK_SPEED = 0.1f;

    private volatile GameMode gameMode = GameMode.SURVIVAL;
    private volatile boolean flying;
    private volatile boolean flightAllowed;
    private boolean creativeMode;
    private boolean invulnerable;
    private float flySpeed = DEFAULT_FLY_SPEED;
    private float walkSpeed = DEFAULT_WALK_SPEED;

    public void applyGameMode(GameMode gameMode) {
        if (gameMode != null) {
            this.gameMode = gameMode;
        }
    }

    public void applyAbilities(boolean flying, boolean flightAllowed, boolean creativeMode, boolean invulnerable,
                               float flySpeed, float walkSpeed) {
        this.flying = flying;
        this.flightAllowed = flightAllowed;
        this.creativeMode = creativeMode;
        this.invulnerable = invulnerable;
        this.flySpeed = flySpeed;
        this.walkSpeed = walkSpeed;
    }

    /** The client toggles flight itself (double jump); the server learns it from the abilities packet. */
    public void applyClientFlying(boolean flying) {
        this.flying = flying;
    }

    public GameMode gameMode() {
        return gameMode;
    }

    public boolean flying() {
        return flying;
    }

    public boolean flightAllowed() {
        return flightAllowed;
    }

    public boolean creativeMode() {
        return creativeMode;
    }

    public boolean invulnerable() {
        return invulnerable;
    }

    public float flySpeed() {
        return flySpeed;
    }

    public float walkSpeed() {
        return walkSpeed;
    }
}
