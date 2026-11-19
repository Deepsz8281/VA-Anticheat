package dev.vaplus.anticheat.movement;

import dev.vaplus.anticheat.simulation.SimulationResult;

/**
 * One processed movement packet as seen by {@link dev.vaplus.anticheat.check.PositionCheck}s.
 * A single instance per player is reused for every packet (no allocation); checks must not keep a
 * reference beyond the callback. Written only by the movement pipeline.
 */
public final class MovementUpdate {

    private final SimulationResult simulation;

    private double fromX;
    private double fromY;
    private double fromZ;
    private double toX;
    private double toY;
    private double toZ;
    private boolean fromGround;
    private boolean toGround;
    private boolean horizontalCollision;
    private boolean positionChanged;
    private boolean rotationChanged;
    private boolean firstPosition;
    private boolean teleport;
    private int elapsedTicks;
    private long timestampNanos;

    public MovementUpdate(SimulationResult simulation) {
        this.simulation = simulation;
    }

    /** Pipeline only. */
    public void assign(double fromX, double fromY, double fromZ,
                       double toX, double toY, double toZ,
                       boolean fromGround, boolean toGround, boolean horizontalCollision,
                       boolean positionChanged, boolean rotationChanged, boolean firstPosition,
                       int elapsedTicks, long timestampNanos) {
        this.fromX = fromX;
        this.fromY = fromY;
        this.fromZ = fromZ;
        this.toX = toX;
        this.toY = toY;
        this.toZ = toZ;
        this.fromGround = fromGround;
        this.toGround = toGround;
        this.horizontalCollision = horizontalCollision;
        this.positionChanged = positionChanged;
        this.rotationChanged = rotationChanged;
        this.firstPosition = firstPosition;
        this.teleport = false;
        this.elapsedTicks = elapsedTicks;
        this.timestampNanos = timestampNanos;
    }

    /** Pipeline only: marks this packet as the client's answer to a server teleport. */
    public void markTeleport() {
        this.teleport = true;
    }

    public double fromX() {
        return fromX;
    }

    public double fromY() {
        return fromY;
    }

    public double fromZ() {
        return fromZ;
    }

    public double toX() {
        return toX;
    }

    public double toY() {
        return toY;
    }

    public double toZ() {
        return toZ;
    }

    public double deltaX() {
        return toX - fromX;
    }

    public double deltaY() {
        return toY - fromY;
    }

    public double deltaZ() {
        return toZ - fromZ;
    }

    public double horizontalDistance() {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** Ground flag of the previous movement packet. */
    public boolean fromGround() {
        return fromGround;
    }

    /** Ground flag claimed by this packet (client-controlled, verify before trusting). */
    public boolean toGround() {
        return toGround;
    }

    /** Horizontal-collision flag claimed by the client (1.21.2+ protocol; always false for older clients). */
    public boolean horizontalCollision() {
        return horizontalCollision;
    }

    public boolean positionChanged() {
        return positionChanged;
    }

    public boolean rotationChanged() {
        return rotationChanged;
    }

    /** First known position after join or respawn; there is no meaningful delta. */
    public boolean firstPosition() {
        return firstPosition;
    }

    /** This position confirms a server teleport and must not be judged as movement. */
    public boolean teleport() {
        return teleport;
    }

    /**
     * Client ticks since the previous position packet. Exact for 1.21.2+ clients (tick-end packet);
     * for older clients it is 1 and the simulation has to consider skipped idle ticks itself.
     */
    public int elapsedTicks() {
        return elapsedTicks;
    }

    public long timestampNanos() {
        return timestampNanos;
    }

    /** Result of the movement simulation for this packet. */
    public SimulationResult simulation() {
        return simulation;
    }
}
