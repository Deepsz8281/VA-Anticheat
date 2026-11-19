package dev.vaplus.anticheat.check;

import dev.vaplus.anticheat.movement.MovementUpdate;

/**
 * Capability interface for checks that evaluate position changes. Implementations must extend
 * {@link Check}. Invoked after the simulation ran; the result is available through
 * {@link MovementUpdate#simulation()}. Not invoked for the first position after join/respawn.
 */
public interface PositionCheck {

    void onPosition(MovementUpdate update);
}
