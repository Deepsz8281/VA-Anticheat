package dev.vaplus.anticheat.check;

import dev.vaplus.anticheat.movement.LookChange;

/** Capability interface for checks that evaluate rotation changes. Implementations must extend {@link Check}. */
public interface RotationCheck {

    void onRotation(LookChange update);
}
