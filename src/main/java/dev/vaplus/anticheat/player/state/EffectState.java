package dev.vaplus.anticheat.player.state;

import java.util.Arrays;

/**
 * Active effect amplifiers by {@link TrackedEffect}; {@code -1} means not active. Owner thread only.
 *
 * <p>Uncertainty windows (phase 2 round 3, fed by {@code pipeline.EffectTracker}): between the
 * acknowledgement of the marker before an effect packet and the one after it, the client may or may
 * not have applied the change. The tracker brackets such packets with
 * {@link #beginPending}/{@link #endPending}; consumers (the movement engine) ask {@link #pending}
 * and skip instead of guessing. The counters are bracket bookkeeping only: {@link #clear()} does not
 * touch them, so every begin is matched by its end even across a respawn.
 */
public final class EffectState {

    private static final int NONE = -1;

    private final int[] amplifiers = new int[TrackedEffect.values().length];
    private final int[] pending = new int[TrackedEffect.values().length];
    private int pendingTotal;
    private long version;

    public EffectState() {
        Arrays.fill(amplifiers, NONE);
    }

    public void apply(TrackedEffect effect, int amplifier) {
        amplifiers[effect.ordinal()] = Math.max(0, amplifier);
        version++;
    }

    public void remove(TrackedEffect effect) {
        amplifiers[effect.ordinal()] = NONE;
        version++;
    }

    public void clear() {
        Arrays.fill(amplifiers, NONE);
        version++;
    }

    public boolean has(TrackedEffect effect) {
        return amplifiers[effect.ordinal()] != NONE;
    }

    /** Amplifier (0 = level I) or {@code -1} when the effect is not active. */
    public int amplifier(TrackedEffect effect) {
        return amplifiers[effect.ordinal()];
    }

    /** Effect level (amplifier + 1), or 0 when not active; convenient for physics formulas. */
    public int level(TrackedEffect effect) {
        return amplifiers[effect.ordinal()] + 1;
    }

    /** Local mutation stamp of the committed amplifiers (apply/remove/clear). */
    public long version() {
        return version;
    }

    /** A change of {@code effect} may now be applied by the client (first bracket ack). */
    public void beginPending(TrackedEffect effect) {
        pending[effect.ordinal()]++;
        pendingTotal++;
    }

    /** The change opened by {@link #beginPending} is committed (second bracket ack). */
    public void endPending(TrackedEffect effect) {
        int index = effect.ordinal();
        if (pending[index] > 0) {
            pending[index]--;
            pendingTotal--;
        }
    }

    /** Whether the client may currently hold a different state of {@code effect} than committed. */
    public boolean pending(TrackedEffect effect) {
        return pending[effect.ordinal()] > 0;
    }

    /** Whether any effect is in an uncertainty window. */
    public boolean anyPending() {
        return pendingTotal > 0;
    }

    /** Open uncertainty windows over all effects. */
    public int pendingCount() {
        return pendingTotal;
    }
}
