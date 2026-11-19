package dev.vaplus.anticheat.player.state;

/**
 * Server-sent velocities of the player's own entity that the movement engine still has to account
 * for. Fed by the velocity tracker ({@code pipeline.VelocityTracker}) with two-marker latency
 * compensation and read/consumed by the movement engine. Owner-thread confined like the rest of
 * the player state: no synchronisation, no volatile fields, no allocation after construction.
 *
 * <h2>Lifecycle of an entry (FIFO, send order)</h2>
 * <ol>
 *   <li>{@link Phase#SENT}: the packet left the server; the client cannot have applied it yet.</li>
 *   <li>{@link Phase#WINDOW}: the marker sent before the packet was acknowledged; the client may or
 *       may not have applied the velocity, so the engine tries both.</li>
 *   <li>{@link Phase#ACKED}: the marker sent after the packet was acknowledged; the client has
 *       applied it. The engine offers it to the next simulated movement and then drops it (the
 *       following movements carry it through the engine's own motion seed).</li>
 * </ol>
 * Clients process packets in order, so the phases are monotonic along the queue: every ACKED entry
 * precedes every WINDOW entry, which precedes every SENT entry. The engine therefore only needs the
 * length of the {@linkplain #offerableCount() offerable prefix}.
 *
 * <p>{@link Kind#SET} is {@code set_entity_motion} (the velocity replaces the client's motion,
 * Wiki protocol "Set Entity Velocity"); {@link Kind#ADD} is explosion knockback, which the Wiki
 * protocol page documents as "Velocity difference of the player being pushed by the explosion"
 * (fetched 2026-11-13), i.e. it is added to the current motion. Values are blocks per tick.
 */
public final class VelocityState {

    /** Unknown own entity id (no join packet observed yet, e.g. after a late enable). */
    public static final int UNKNOWN_ENTITY_ID = Integer.MIN_VALUE;
    /** Pending entries kept; beyond it the oldest entry is dropped and counted. */
    public static final int CAPACITY = 8;
    /**
     * Client ticks an ACKED entry waits for a simulated movement before it is dropped; the same
     * bound the teleport queue and exemption holds use (60 ticks).
     */
    public static final int MAX_ACKED_AGE_TICKS = 60;

    public enum Kind {
        /** Replaces the client motion ({@code set_entity_motion}). */
        SET,
        /** Added to the client motion (explosion knockback). */
        ADD
    }

    public enum Phase {
        SENT, WINDOW, ACKED
    }

    private int selfEntityId = UNKNOWN_ENTITY_ID;

    private final long[] ids = new long[CAPACITY];
    private final Kind[] kinds = new Kind[CAPACITY];
    private final Phase[] phases = new Phase[CAPACITY];
    private final double[] xs = new double[CAPACITY];
    private final double[] ys = new double[CAPACITY];
    private final double[] zs = new double[CAPACITY];
    private final int[] epochs = new int[CAPACITY];
    private final long[] ackedTicks = new long[CAPACITY];
    private final boolean[] tainted = new boolean[CAPACITY];
    private int size;
    private long nextId = 1;

    private long registered;
    private long dropped;
    private long expired;
    private long appliedByEngine;

    /** Entity id of the player's own entity, from the join packet. */
    public int selfEntityId() {
        return selfEntityId;
    }

    public void selfEntityId(int entityId) {
        this.selfEntityId = entityId;
    }

    public boolean isSelf(int entityId) {
        return selfEntityId != UNKNOWN_ENTITY_ID && entityId == selfEntityId;
    }

    /**
     * Appends a velocity in phase {@link Phase#SENT}.
     *
     * @return handle for {@link #markFirstAck}/{@link #markSecondAck}
     */
    public long register(Kind kind, double x, double y, double z, int worldEpoch) {
        if (kind == null || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("velocity must have a kind and finite components");
        }
        if (size == CAPACITY) {
            removeFirst(1);
            dropped++;
        }
        long id = nextId++;
        ids[size] = id;
        kinds[size] = kind;
        phases[size] = Phase.SENT;
        xs[size] = x;
        ys[size] = y;
        zs[size] = z;
        epochs[size] = worldEpoch;
        ackedTicks[size] = 0L;
        tainted[size] = false;
        size++;
        registered++;
        return id;
    }

    /** First marker acknowledged: the velocity may now have been applied. Unknown ids are ignored. */
    public void markFirstAck(long id) {
        int index = indexOf(id);
        if (index >= 0 && phases[index] == Phase.SENT) {
            phases[index] = Phase.WINDOW;
        }
    }

    /** Second marker acknowledged: the velocity has been applied. Unknown ids are ignored. */
    public void markSecondAck(long id, long clientTick) {
        int index = indexOf(id);
        if (index >= 0) {
            phases[index] = Phase.ACKED;
            ackedTicks[index] = clientTick;
        }
    }

    /** Entries currently tracked (any phase). */
    public int size() {
        return size;
    }

    /** Length of the prefix the client may already have applied (phase WINDOW or ACKED). */
    public int offerableCount() {
        int count = 0;
        while (count < size && phases[count] != Phase.SENT) {
            count++;
        }
        return count;
    }

    /** Length of the prefix the client has definitely applied (phase ACKED). */
    public int ackedCount() {
        int count = 0;
        while (count < size && phases[count] == Phase.ACKED) {
            count++;
        }
        return count;
    }

    /** Handle of the entry (monotonic in send order). */
    public long id(int index) {
        check(index);
        return ids[index];
    }

    public Kind kind(int index) {
        check(index);
        return kinds[index];
    }

    public Phase phase(int index) {
        check(index);
        return phases[index];
    }

    public double x(int index) {
        check(index);
        return xs[index];
    }

    public double y(int index) {
        check(index);
        return ys[index];
    }

    public double z(int index) {
        check(index);
        return zs[index];
    }

    /**
     * Whether the entry was offerable while the engine skipped a movement: the client may already
     * have applied it in a movement nobody simulated, so whether a later movement must contain it
     * is unknown.
     */
    public boolean tainted(int index) {
        check(index);
        return tainted[index];
    }

    /** Engine, on a skipped movement: marks every offerable entry {@linkplain #tainted(int) tainted}. */
    public void taintOfferable() {
        int count = offerableCount();
        for (int i = 0; i < count; i++) {
            tainted[i] = true;
        }
    }

    public int worldEpoch(int index) {
        check(index);
        return epochs[index];
    }

    /**
     * Engine, after a simulated movement: drops the first {@code applied} entries (used by the best
     * candidate) and every remaining ACKED entry (offered once; later movements carry it through
     * the motion seed).
     */
    public void consume(int applied) {
        int count = Math.max(Math.max(0, Math.min(applied, size)), ackedCount());
        appliedByEngine += Math.max(0, Math.min(applied, size));
        removeFirst(count);
    }

    /**
     * Drops entries of another world epoch (respawn/world change) and ACKED entries older than
     * {@link #MAX_ACKED_AGE_TICKS}.
     */
    public void expire(long nowClientTick, int currentWorldEpoch) {
        int write = 0;
        for (int read = 0; read < size; read++) {
            boolean stale = epochs[read] != currentWorldEpoch
                    || (phases[read] == Phase.ACKED && nowClientTick - ackedTicks[read] > MAX_ACKED_AGE_TICKS);
            if (stale) {
                expired++;
                continue;
            }
            copy(read, write++);
        }
        clearTail(write);
        size = write;
    }

    /** Drops every entry (join). The own entity id and counters are kept. */
    public void clear() {
        clearTail(0);
        size = 0;
    }

    /** Entries ever registered. */
    public long registered() {
        return registered;
    }

    /** Entries dropped on overflow. */
    public long dropped() {
        return dropped;
    }

    /** Entries dropped by {@link #expire}. */
    public long expired() {
        return expired;
    }

    /** Entries the engine's best candidate applied. */
    public long appliedByEngine() {
        return appliedByEngine;
    }

    private int indexOf(long id) {
        for (int i = 0; i < size; i++) {
            if (ids[i] == id) {
                return i;
            }
        }
        return -1;
    }

    private void removeFirst(int count) {
        if (count <= 0) {
            return;
        }
        int remaining = size - count;
        for (int i = 0; i < remaining; i++) {
            copy(i + count, i);
        }
        clearTail(remaining);
        size = remaining;
    }

    private void copy(int from, int to) {
        if (from == to) {
            return;
        }
        ids[to] = ids[from];
        kinds[to] = kinds[from];
        phases[to] = phases[from];
        xs[to] = xs[from];
        ys[to] = ys[from];
        zs[to] = zs[from];
        epochs[to] = epochs[from];
        ackedTicks[to] = ackedTicks[from];
        tainted[to] = tainted[from];
    }

    private void clearTail(int from) {
        for (int i = from; i < CAPACITY; i++) {
            kinds[i] = null;
            phases[i] = null;
            tainted[i] = false;
        }
    }

    private void check(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(index);
        }
    }
}
