package dev.vaplus.anticheat.player.state;

import java.util.function.Consumer;

/**
 * Fixed-capacity ring buffer of the last {@link #CAPACITY} per-client-tick motion snapshots
 * (Handoff M12). Every client tick the motion-tracking module pushes one immutable
 * {@link TickSnapshot}; the oldest entry is overwritten once the buffer is full. The movement
 * engine reads it to reconstruct multi-tick motion without keeping its own history.
 *
 * <p>Threading contract: owner-thread confined, like the rest of the player state. The buffer is
 * written and read only from the player's netty event-loop thread (tick listeners and checks);
 * no synchronization and no volatile fields.
 */
public final class MotionHistory {

    /** Number of snapshots kept; the oldest is overwritten beyond this. */
    public static final int CAPACITY = 32;

    private final TickSnapshot[] ring = new TickSnapshot[CAPACITY];
    private int start;
    private int size;

    /**
     * One client tick of observed motion. All fields are exactly what the trackers knew at that
     * tick; the engine decides what to trust. Deltas are the movement of the most recent position
     * packet, not a guaranteed single-tick distance (see the motion-tracking module).
     *
     * @param clientTick the {@code PlayerData#clientTicks()} value of the tick
     * @param nanos      monotonic {@code nanoTime()} of the tick (owner-thread clock)
     * @param dx         horizontal x movement of the last position packet (0 before the first)
     * @param dy         vertical movement of the last position packet (0 before the first)
     * @param dz         horizontal z movement of the last position packet (0 before the first)
     * @param onGround   client-declared ground flag of the last movement packet
     * @param sprinting  pose flag (entity metadata / sprint packets)
     * @param sneaking   pose flag (entity metadata, or the input packet on 1.21.6+ servers)
     * @param jumping    raw jump key from the player-input packet (always false before the first
     *                   input packet, i.e. typically pre-1.21.2 clients)
     * @param gliding    pose flag (elytra entity metadata)
     * @param worldEpoch world generation counter of {@link MovementState} at that tick
     */
    public record TickSnapshot(long clientTick, long nanos,
                               double dx, double dy, double dz,
                               boolean onGround, boolean sprinting, boolean sneaking,
                               boolean jumping, boolean gliding,
                               int worldEpoch) {
    }

    /** Pushes one snapshot; once full, the oldest entry is overwritten. Owner thread only. */
    public void push(TickSnapshot snapshot) {
        if (size < CAPACITY) {
            ring[(start + size) % CAPACITY] = snapshot;
            size++;
        } else {
            ring[start] = snapshot;
            start = (start + 1) % CAPACITY;
        }
    }

    /** Number of snapshots currently stored (0..{@link #CAPACITY}). */
    public int size() {
        return size;
    }

    /** Whether a snapshot with exactly this client tick is still in the buffer. */
    public boolean isPresent(long clientTick) {
        for (int i = 0; i < size; i++) {
            if (ring[(start + i) % CAPACITY].clientTick() == clientTick) {
                return true;
            }
        }
        return false;
    }

    /** Most recently pushed snapshot, or {@code null} when the buffer is empty. No allocation. */
    public TickSnapshot newest() {
        return size == 0 ? null : ring[(start + size - 1) % CAPACITY];
    }

    /**
     * Visits the snapshots from newest to oldest (the order the movement engine consumes them in).
     */
    public void forEachReversed(Consumer<TickSnapshot> action) {
        for (int i = size - 1; i >= 0; i--) {
            action.accept(ring[(start + i) % CAPACITY]);
        }
    }

    /** Drops every snapshot (join/respawn style reset). Owner thread only. */
    public void clear() {
        start = 0;
        size = 0;
        for (int i = 0; i < CAPACITY; i++) {
            ring[i] = null;
        }
    }
}
