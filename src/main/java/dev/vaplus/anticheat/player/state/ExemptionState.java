package dev.vaplus.anticheat.player.state;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Active exemptions as a bit mask. A cause is active while it has remaining ticks (timed grant) or
 * at least one hold (between the two acknowledgements of a bracketed state change).
 *
 * <p>Holds expire after {@link #MAX_HOLD_TICKS} client ticks: a client could otherwise answer the
 * first marker of a bracket and withhold the second to stay exempt until the ack timeout. Expired
 * holds are counted ({@link #expiredHolds()}) as a withholding signal for protocol checks.
 * Owner thread writes; the mask is volatile so commands can display it.
 *
 * <p>Load holds (handoff L11): {@link #holdUntilLoaded} keeps a cause active until the client
 * reports that it finished loading the world ({@code PLAYER_LOADED}, see
 * {@code pipeline.GameStateTracker}) and {@link #releaseLoadHolds()} is called. At most one load
 * hold per cause exists; it is an ordinary hold, so the {@link #MAX_HOLD_TICKS} expiry is the
 * fallback when the packet never arrives. Load holds that expire are counted separately
 * ({@link #expiredLoadHolds()}): a slowly loading client is not a withholding signal.
 */
public final class ExemptionState {

    private static final ExemptionCause[] CAUSES = ExemptionCause.values();

    private final int[] ticksLeft = new int[CAUSES.length];
    /** 3 seconds: far above any legitimate ack delay between two back-to-back markers. */
    public static final int MAX_HOLD_TICKS = 60;

    private final int[] holds = new int[CAUSES.length];
    private final long[] holdSince = new long[CAUSES.length];
    private long tickCounter;
    private long expiredHolds;
    private long expiredLoadHolds;
    /** Causes that currently own one load hold (bit per cause). */
    private long loadHoldMask;
    private volatile long activeMask;

    /** Exempts for at least {@code ticks} client ticks (does not shorten a longer running grant). */
    public void grant(ExemptionCause cause, int ticks) {
        int index = cause.ordinal();
        if (ticks > ticksLeft[index]) {
            ticksLeft[index] = ticks;
        }
        refresh(index);
    }

    public void hold(ExemptionCause cause) {
        int index = cause.ordinal();
        if (holds[index] == 0) {
            holdSince[index] = tickCounter;
        }
        holds[index]++;
        refresh(index);
    }

    public void release(ExemptionCause cause) {
        int index = cause.ordinal();
        if (holds[index] > 0) {
            holds[index]--;
        }
        refresh(index);
    }

    /**
     * Holds {@code cause} until {@link #releaseLoadHolds()} (client finished loading). A second call
     * for the same cause while its load hold is pending does nothing.
     */
    public void holdUntilLoaded(ExemptionCause cause) {
        long bit = cause.bit();
        if ((loadHoldMask & bit) != 0L) {
            return;
        }
        loadHoldMask |= bit;
        hold(cause);
    }

    /**
     * Releases every pending load hold (the client reported it finished loading). Timed grants and
     * bracket holds of the same causes stay untouched.
     *
     * @return mask of the causes whose load hold was released
     */
    public long releaseLoadHolds() {
        long released = loadHoldMask;
        if (released == 0L) {
            return 0L;
        }
        loadHoldMask = 0L;
        for (ExemptionCause cause : CAUSES) {
            if ((released & cause.bit()) != 0L) {
                release(cause);
            }
        }
        return released;
    }

    /** Whether {@code cause} currently waits for the client to finish loading. */
    public boolean awaitingLoad(ExemptionCause cause) {
        return (loadHoldMask & cause.bit()) != 0L;
    }

    /** Load holds that ran out because the client never reported that it finished loading in time. */
    public long expiredLoadHolds() {
        return expiredLoadHolds;
    }

    /** Called once per client tick. */
    public void tick() {
        tickCounter++;
        long mask = activeMask;
        if (mask == 0L) {
            return;
        }
        for (int i = 0; i < CAUSES.length; i++) {
            boolean changed = false;
            if (ticksLeft[i] > 0) {
                ticksLeft[i]--;
                changed = true;
            }
            if (holds[i] > 0 && tickCounter - holdSince[i] > MAX_HOLD_TICKS) {
                long bit = CAUSES[i].bit();
                boolean loadHold = (loadHoldMask & bit) != 0L;
                if (loadHold) {
                    loadHoldMask &= ~bit;
                    expiredLoadHolds++;
                }
                // Only holds beyond the load hold come from withheld bracket acknowledgements.
                if (!loadHold || holds[i] > 1) {
                    expiredHolds++;
                }
                holds[i] = 0;
                changed = true;
            }
            if (changed) {
                refresh(i);
            }
        }
    }

    /** Holds that ran out because the matching acknowledgement never arrived in time. */
    public long expiredHolds() {
        return expiredHolds;
    }

    public boolean isActive(ExemptionCause cause) {
        return (activeMask & cause.bit()) != 0L;
    }

    public boolean anyActive(long mask) {
        return (activeMask & mask) != 0L;
    }

    public long activeMask() {
        return activeMask;
    }

    public int ticksLeft(ExemptionCause cause) {
        return ticksLeft[cause.ordinal()];
    }

    public void clear() {
        Arrays.fill(ticksLeft, 0);
        Arrays.fill(holds, 0);
        Arrays.fill(holdSince, 0L);
        loadHoldMask = 0L;
        activeMask = 0L;
    }

    /** Active causes, for display. Allocates; not for the hot path. */
    public List<ExemptionCause> activeCauses() {
        long mask = activeMask;
        List<ExemptionCause> result = new ArrayList<>();
        for (ExemptionCause cause : CAUSES) {
            if ((mask & cause.bit()) != 0L) {
                result.add(cause);
            }
        }
        return result;
    }

    private void refresh(int index) {
        long bit = CAUSES[index].bit();
        if (ticksLeft[index] > 0 || holds[index] > 0) {
            activeMask |= bit;
        } else {
            activeMask &= ~bit;
        }
    }
}
