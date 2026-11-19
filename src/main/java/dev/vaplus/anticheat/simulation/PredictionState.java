package dev.vaplus.anticheat.simulation;

/**
 * Engine-owned per-player motion state (handoff M12 "engine-owned interpretation") and reusable
 * scratch of {@link PredictionSimulator}. Registered as a component by {@link SimulationModule}.
 *
 * <p>The "seed" is the client motion at the start of the next tick (after the previous tick's
 * friction, gravity and drag), derived from the last reported movement. It is valid only for the
 * position packet directly following the one that produced it and only within the same world
 * epoch; teleports, first positions and unsimulated packets invalidate it implicitly.
 *
 * <p>Owner-thread confined; allocation-free after construction.
 */
public final class PredictionState {

    final SupportScan scan = new SupportScan();

    boolean seeded;
    double seedX;
    double seedY;
    double seedZ;
    long seedPositionPackets = -1;
    int seedEpoch;

    /** {@code positionPackets} of the last packet the engine saw, and its tick count. */
    long lastSeenPositionPackets = -1;
    int lastSeenTicks;

    /** Chained predicted position after the last completed packet (position-domain accuracy). */
    boolean chainSeeded;
    double chainX;
    double chainY;
    double chainZ;
    long chainPositionPackets = -1;
    int chainEpoch;
    int chainTicks;

    long attributeVersion = -1;
    double movementSpeedNoSprint;
    boolean speedEffectInAttribute;
    boolean slownessEffectInAttribute;

    long completed;
    long skipped;

    /** Whether a motion seed exists for the given next packet. */
    boolean seedValidFor(long positionPackets, int worldEpoch) {
        return seeded && seedPositionPackets == positionPackets - 1 && seedEpoch == worldEpoch;
    }

    void seed(double x, double y, double z, long positionPackets, int worldEpoch) {
        seeded = true;
        seedX = x;
        seedY = y;
        seedZ = z;
        seedPositionPackets = positionPackets;
        seedEpoch = worldEpoch;
    }

    void invalidateSeed() {
        seeded = false;
    }

    /** Whether the chained prediction can be extended by the next packet. */
    boolean chainValidFor(long positionPackets, int worldEpoch) {
        return chainSeeded && chainPositionPackets == positionPackets - 1 && chainEpoch == worldEpoch;
    }

    void chain(double x, double y, double z, long positionPackets, int worldEpoch, int ticks) {
        chainSeeded = true;
        chainX = x;
        chainY = y;
        chainZ = z;
        chainPositionPackets = positionPackets;
        chainEpoch = worldEpoch;
        chainTicks = ticks;
    }

    void invalidateChain() {
        chainSeeded = false;
    }

    /** Start-of-tick motion the next simulation will use (diagnostics/tests). */
    public boolean seeded() {
        return seeded;
    }

    public double seedX() {
        return seedX;
    }

    public double seedY() {
        return seedY;
    }

    public double seedZ() {
        return seedZ;
    }

    /** Simulations that ended COMPLETE. */
    public long completed() {
        return completed;
    }

    /** Simulations that ended SKIPPED. */
    public long skipped() {
        return skipped;
    }
}
