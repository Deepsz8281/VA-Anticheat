package dev.vaplus.anticheat.latency;

import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.module.ModuleSetup;
import dev.vaplus.anticheat.player.PacketTransport;
import dev.vaplus.anticheat.player.PlayerData;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Per-server-tick marker (handoff M15): maps acknowledgement markers to server ticks, so the
 * movement engine can tell which server tick the client has provably caught up with.
 *
 * <p>Flow: a platform scheduler announces every server tick with {@link #publish(long)} (any thread:
 * Paper's main thread at tick end, Folia's entity-scheduler thread of the player). The announcement
 * only stores the tick index in a volatile field and schedules {@link #markNow()} on the owner
 * thread through the {@link PacketTransport} (heartbeat pattern, no Bukkit API involved). On the
 * owner thread one marker is sent and the pair {@code (marker, tick)} is recorded. Ticks that were
 * announced faster than the owner thread could mark them collapse onto the newest one — the
 * mapping stays monotonic and conservative (a tick is never reported acknowledged too early).
 *
 * <p>{@link #acknowledgedServerTick()} lazily drains the recorded pairs against
 * {@link AckTracker#isAcked(long)}: no task per marker, no allocation in steady state.
 *
 * <p>Tick indices are platform-defined but monotonic per player: on Paper the server's tick number
 * from {@code ServerTickEndEvent#getTickNumber()}, on Folia the number of region ticks the player's
 * entity task has run since it was attached (Folia has no global tick). Only differences and
 * ordering are meaningful to consumers.
 *
 * <p>Threading: owner-thread confined except {@link #publish(long)} and the volatile snapshots
 * ({@link #announcedServerTick()}, {@link #acknowledgedServerTickSnapshot()}).
 */
public final class ServerTickMarkers {

    /** No tick marked/acknowledged yet. */
    public static final long NONE = Long.MIN_VALUE;

    /** Ring capacity (power of two). At 20 ticks/s this covers 12.8 s, well above the ack timeout's useful range. */
    static final int CAPACITY = 256;

    private final AckTracker acks;
    private final PacketTransport transport;
    private final BooleanSupplier disconnecting;
    private final Runnable markTask = this::markNow;

    private final long[] markers = new long[CAPACITY];
    private final long[] ticks = new long[CAPACITY];
    private int head;
    private int size;

    private volatile long announcedTick = NONE;
    private long lastMarkedTick = NONE;
    private long acknowledgedTick = NONE;
    private volatile long acknowledgedSnapshot = NONE;
    private long overflows;

    public ServerTickMarkers(AckTracker acks, PacketTransport transport, BooleanSupplier disconnecting) {
        this.acks = Objects.requireNonNull(acks, "acks");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.disconnecting = Objects.requireNonNull(disconnecting, "disconnecting");
    }

    /** Component factory used by the core-tracking module. */
    public static ServerTickMarkers create(PlayerData player) {
        return new ServerTickMarkers(player.acks(), player.transport(), player::disconnecting);
    }

    /**
     * Announces that server tick {@code serverTick} ended. Any thread; schedules the marker send on
     * the owner thread. Older or repeated indices are ignored when the task runs.
     */
    public void publish(long serverTick) {
        if (disconnecting.getAsBoolean()) {
            return;
        }
        announcedTick = serverTick;
        transport.runOnOwnerThread(markTask);
    }

    /**
     * Owner thread: sends one marker for the newest announced tick (if it is newer than the last
     * marked one) and records the mapping.
     */
    public void markNow() {
        if (disconnecting.getAsBoolean()) {
            return;
        }
        long tick = announcedTick;
        if (tick == NONE || (lastMarkedTick != NONE && tick <= lastMarkedTick)) {
            return;
        }
        lastMarkedTick = tick;
        drain();
        // When the transport refuses (non-play state) sendMarker hands out the next handle; the tick
        // then resolves once a later real marker is acknowledged — late, never early.
        long marker = acks.sendMarker();
        record(marker, tick);
    }

    /**
     * Newest server tick whose marker the client has acknowledged, {@link #NONE} if none yet.
     * Owner thread (drains acknowledged entries).
     */
    public long acknowledgedServerTick() {
        drain();
        return acknowledgedTick;
    }

    /** Newest server tick a marker was sent for, {@link #NONE} if none yet. Owner thread. */
    public long lastMarkedServerTick() {
        return lastMarkedTick;
    }

    /** Server ticks marked but not yet acknowledged (how many ticks the client is behind). Owner thread. */
    public int pendingTicks() {
        drain();
        return size;
    }

    /** Newest announced tick (diagnostics; any thread). */
    public long announcedServerTick() {
        return announcedTick;
    }

    /** Last value computed by {@link #acknowledgedServerTick()} (diagnostics; any thread, may be stale). */
    public long acknowledgedServerTickSnapshot() {
        return acknowledgedSnapshot;
    }

    /** Mappings dropped because the client left more than {@link #CAPACITY} ticks unanswered. */
    public long overflows() {
        return overflows;
    }

    private void record(long marker, long tick) {
        if (size == CAPACITY) {
            // The client is far behind (the ack timeout disconnects it soon). Drop the oldest
            // mapping; its tick can no longer be reported, later ticks stay correct.
            head = (head + 1) & (CAPACITY - 1);
            size--;
            overflows++;
        }
        int tail = (head + size) & (CAPACITY - 1);
        markers[tail] = marker;
        ticks[tail] = tick;
        size++;
    }

    private void drain() {
        boolean changed = false;
        while (size > 0 && acks.isAcked(markers[head])) {
            acknowledgedTick = ticks[head];
            head = (head + 1) & (CAPACITY - 1);
            size--;
            changed = true;
        }
        if (changed) {
            acknowledgedSnapshot = acknowledgedTick;
        }
    }

    /**
     * Finds the component key in a module setup, or {@code null} when the core-tracking module is
     * not installed. Scans the (small) component list; resolve once and cache the key.
     */
    @SuppressWarnings("unchecked")
    public static ComponentKey<ServerTickMarkers> keyOf(ModuleSetup setup) {
        for (ComponentKey<?> key : setup.components()) {
            if (key.type() == ServerTickMarkers.class) {
                return (ComponentKey<ServerTickMarkers>) key;
            }
        }
        return null;
    }
}
