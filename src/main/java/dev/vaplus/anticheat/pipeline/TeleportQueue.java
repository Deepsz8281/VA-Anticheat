package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import dev.vaplus.anticheat.movement.SetbackHandler.SetbackTarget;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.module.ModuleSetup;
import dev.vaplus.anticheat.player.PlayerData;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Per-player FIFO of outbound position syncs ({@code player_position_and_look}) whose
 * acknowledgement ({@code TELEPORT_CONFIRM}) is still missing. Registered as a component by
 * {@link CoreTrackingModule}; resolved through {@link #resolve(PlayerData)}.
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>Every outbound sync is appended with its teleport id, the target values as sent, the
 *       relative-flag mask and the {@code worldEpoch} at send time. Syncs sent by other plugins or
 *       by the server itself are observed by {@link TeleportSyncTracker}; a packet-level setback
 *       registers itself before sending ({@link #registerOwn}).</li>
 *   <li>Only a confirmation for the <em>oldest</em> pending entry pops the queue (vanilla clients
 *       confirm in order). Duplicate, unknown and out-of-order confirmations only raise
 *       {@link #anomalies()}; they never unlock the queue.</li>
 *   <li>An entry that is not confirmed within {@link #MAX_AGE_TICKS} client ticks is dropped and
 *       counted separately ({@link #expired()}). This bounds the "checks paused" window without
 *       trusting a forged confirmation.</li>
 *   <li>While an entry is pending, {@link MovementProcessor} skips hooks, simulation and position
 *       checks ("awaiting teleport").</li>
 * </ul>
 *
 * <p>Threading: owner-thread confined like the rest of the player state (inbound tracker,
 * outbound tracker and movement processing all run on the connection's event loop).
 */
public final class TeleportQueue {

    // Relative-flag bits exactly as exposed by PacketEvents 2.14.0 (RelativeFlag.X ... ROTATE_DELTA)
    // and as documented for protocol 26.3. The velocity/delta bits only appear for 1.21.2+ servers
    // because PacketEvents reads a full int mask from that version on (byte mask before).
    public static final int FLAG_X = 0x0001;
    public static final int FLAG_Y = 0x0002;
    public static final int FLAG_Z = 0x0004;
    public static final int FLAG_YAW = 0x0008;
    public static final int FLAG_PITCH = 0x0010;
    public static final int FLAG_DELTA_X = 0x0020;
    public static final int FLAG_DELTA_Y = 0x0040;
    public static final int FLAG_DELTA_Z = 0x0080;
    public static final int FLAG_ROTATE_DELTA = 0x0100;

    /** Upper bound for unconfirmed syncs; on overflow the oldest entry is dropped as an anomaly. */
    public static final int MAX_PENDING = 16;
    /**
     * Client ticks after which an unanswered sync is dropped. Vanilla disconnects a client that does
     * not answer a teleport within 60 ticks; the same window bounds how long checks stay paused.
     */
    public static final int MAX_AGE_TICKS = 60;

    /** Reason of a sync that was observed on the wire (server or another plugin). */
    public static final String SERVER_SYNC = "server-sync";

    /** Result of {@link #confirm(int)}. */
    public enum ConfirmResult {
        /** The oldest pending sync was acknowledged and removed. */
        CONFIRMED,
        /** No sync is pending; the confirmation matches nothing. */
        NO_PENDING,
        /** The id belongs to a pending sync but not to the oldest one. */
        OUT_OF_ORDER,
        /** The id was never sent (or is a duplicate/stale confirmation). */
        UNKNOWN
    }

    /**
     * One unconfirmed outbound sync.
     *
     * @param teleportId            id echoed by the client in {@code TELEPORT_CONFIRM}
     * @param target                values as sent; with relative flags these are offsets, not
     *                              absolute coordinates
     * @param relativeFlags         relative-flag mask ({@code FLAG_*} bits)
     * @param worldEpoch            {@code MovementState.worldEpoch()} at send time
     * @param registeredAtClientTick {@code PlayerData.clientTicks()} at send time
     * @param reason                diagnostic origin ({@link #SERVER_SYNC} or a setback reason)
     */
    public record Entry(int teleportId, SetbackTarget target, int relativeFlags, int worldEpoch,
                        long registeredAtClientTick, String reason) {

        public Entry {
            Objects.requireNonNull(target, "target");
        }

        public boolean relativeX() {
            return (relativeFlags & FLAG_X) != 0;
        }

        public boolean relativeY() {
            return (relativeFlags & FLAG_Y) != 0;
        }

        public boolean relativeZ() {
            return (relativeFlags & FLAG_Z) != 0;
        }

        public boolean relativeYaw() {
            return (relativeFlags & FLAG_YAW) != 0;
        }

        public boolean relativePitch() {
            return (relativeFlags & FLAG_PITCH) != 0;
        }

        public boolean relativeVelocity() {
            return (relativeFlags & (FLAG_DELTA_X | FLAG_DELTA_Y | FLAG_DELTA_Z | FLAG_ROTATE_DELTA)) != 0;
        }
    }

    private final Deque<Entry> pending = new ArrayDeque<>();
    private int anomalies;
    private int expired;
    private int dropped;
    /** Next id for our own syncs: negative and descending, so it never collides with server ids. */
    private int nextOwnId = -1;

    /**
     * Appends a sync.
     *
     * @return {@code false} if an entry with this id is already pending (the outbound observer then
     *         does not duplicate a self-registered setback sync)
     */
    public boolean register(int teleportId, SetbackTarget target, int relativeFlags, int worldEpoch,
                            long clientTick, String reason) {
        if (containsId(teleportId)) {
            return false;
        }
        if (pending.size() >= MAX_PENDING) {
            pending.pollFirst();
            anomalies++;
            dropped++;
        }
        pending.addLast(new Entry(teleportId, target, relativeFlags, worldEpoch, clientTick, reason));
        return true;
    }

    /**
     * Registers a sync sent by the anticheat itself and returns the teleport id to encode. Ids are
     * negative and descending, which keeps them disjoint from the server's own (non-negative,
     * increasing) teleport ids as long as both are pending.
     */
    public int registerOwn(SetbackTarget target, int worldEpoch, long clientTick, String reason) {
        int teleportId = nextOwnTeleportId();
        register(teleportId, target, 0, worldEpoch, clientTick, reason);
        return teleportId;
    }

    /** Next unused negative id for a self-sent position sync. */
    public int nextOwnTeleportId() {
        int candidate = nextOwnId--;
        while (containsId(candidate)) {
            candidate = nextOwnId--;
        }
        return candidate;
    }

    /** Whether this id is currently pending (used for de-duplicating observed self-sent syncs). */
    public boolean containsId(int teleportId) {
        for (Entry entry : pending) {
            if (entry.teleportId() == teleportId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Confirms a sync. Only the oldest pending id is accepted; everything else is counted as an
     * anomaly and leaves the queue untouched (a manipulated confirmation cannot force-unlock it).
     */
    public ConfirmResult confirm(int teleportId) {
        Entry head = pending.peekFirst();
        if (head == null) {
            anomalies++;
            return ConfirmResult.NO_PENDING;
        }
        if (head.teleportId() == teleportId) {
            pending.pollFirst();
            return ConfirmResult.CONFIRMED;
        }
        anomalies++;
        return containsId(teleportId) ? ConfirmResult.OUT_OF_ORDER : ConfirmResult.UNKNOWN;
    }

    /**
     * Drops entries older than {@link #MAX_AGE_TICKS} and reports whether a sync is still pending.
     * Called by the movement gate once per movement packet.
     */
    public boolean awaitingTeleport(long nowClientTick) {
        expire(nowClientTick);
        return !pending.isEmpty();
    }

    private void expire(long nowClientTick) {
        while (!pending.isEmpty()
                && nowClientTick - pending.peekFirst().registeredAtClientTick() > MAX_AGE_TICKS) {
            pending.pollFirst();
            anomalies++;
            expired++;
        }
    }

    /**
     * Drops entries recorded before the current world epoch (respawn/dimension change). New syncs
     * sent after the epoch advance are kept.
     *
     * @return number of dropped entries
     */
    public int discardOtherEpochs(int currentWorldEpoch) {
        int before = pending.size();
        pending.removeIf(entry -> entry.worldEpoch() != currentWorldEpoch);
        int removed = before - pending.size();
        dropped += removed;
        return removed;
    }

    /** Drops every pending sync (join). Counters stay readable for diagnostics. */
    public void clear() {
        dropped += pending.size();
        pending.clear();
    }

    public boolean hasPending() {
        return !pending.isEmpty();
    }

    public int pendingCount() {
        return pending.size();
    }

    /** Oldest pending entry, or {@code null}. */
    public Entry head() {
        return pending.peekFirst();
    }

    /** Copy of the pending entries, oldest first (diagnostics/tests). */
    public List<Entry> snapshot() {
        return new ArrayList<>(pending);
    }

    /** Duplicate, unknown, out-of-order, expired and overflowed confirmations. */
    public int anomalies() {
        return anomalies;
    }

    /** Syncs that were never confirmed within {@link #MAX_AGE_TICKS}. */
    public int expired() {
        return expired;
    }

    /** Entries dropped by {@link #discardOtherEpochs(int)}, {@link #clear()} or overflow. */
    public int dropped() {
        return dropped;
    }

    /**
     * Finds the teleport-queue component key in a module setup, or {@code null} if
     * {@link CoreTrackingModule} is not installed. Scans the (small) component list; used on setup
     * once by the pipeline and per setback request.
     */
    @SuppressWarnings("unchecked")
    public static ComponentKey<TeleportQueue> keyOf(ModuleSetup setup) {
        for (ComponentKey<?> key : setup.components()) {
            if (key.type() == TeleportQueue.class) {
                return (ComponentKey<TeleportQueue>) key;
            }
        }
        return null;
    }

    /**
     * Resolves the player's queue, or {@code null} when the core-tracking module is not installed
     * (then callers fall back to the Bukkit setback path).
     */
    public static TeleportQueue resolve(PlayerData player) {
        ComponentKey<TeleportQueue> key = keyOf(player.context().setup());
        return key == null ? null : player.component(key);
    }

    /** Asserts the flag constants match the installed PacketEvents version (documentation test). */
    static void assertRelativeFlagMapping() {
        if (FLAG_X != RelativeFlag.X.getFullMask()
                || FLAG_Y != RelativeFlag.Y.getFullMask()
                || FLAG_Z != RelativeFlag.Z.getFullMask()
                || FLAG_YAW != RelativeFlag.YAW.getFullMask()
                || FLAG_PITCH != RelativeFlag.PITCH.getFullMask()
                || FLAG_DELTA_X != RelativeFlag.DELTA_X.getFullMask()
                || FLAG_DELTA_Y != RelativeFlag.DELTA_Y.getFullMask()
                || FLAG_DELTA_Z != RelativeFlag.DELTA_Z.getFullMask()
                || FLAG_ROTATE_DELTA != RelativeFlag.ROTATE_DELTA.getFullMask()) {
            throw new AssertionError("RelativeFlag mapping differs from PacketEvents");
        }
    }
}
