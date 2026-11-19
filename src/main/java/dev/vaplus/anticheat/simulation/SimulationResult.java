package dev.vaplus.anticheat.simulation;

/**
 * Output of the movement simulation for one position packet. Reused per player. Movement checks
 * consume only this object and never re-implement physics.
 *
 * <p>{@code offset} is the distance between the reported movement and the closest movement any
 * plausible input could have produced; values near zero mean the movement is explainable.
 */
public final class SimulationResult {

    public enum Status {
        /** No simulation ran (engine not available or not applicable to this packet). */
        NOT_RUN,
        /** Simulation intentionally skipped; see {@link #skipReason()}. Checks should not judge. */
        SKIPPED,
        /** A best-matching prediction was found; offsets are valid. */
        COMPLETE
    }

    private Status status = Status.NOT_RUN;
    private String skipReason = "";
    private double offset;
    private double offsetX;
    private double offsetY;
    private double offsetZ;
    private double predictedX;
    private double predictedY;
    private double predictedZ;
    private boolean predictedOnGround;
    private int ticksSimulated;
    private int candidateIndex = -1;
    private double uncertainty;
    private double cumulativeOffset;
    private int cumulativeTicks;
    private int velocityAcked;
    private int velocityApplied;
    private double ackedVelocityOffset = Double.NaN;
    private double ackedVelocityHorizontalOffset = Double.NaN;
    private double ackedVelocityHorizontal;
    private double ackedVelocityVertical;

    /** Simulation engine only. */
    public void reset() {
        status = Status.NOT_RUN;
        skipReason = "";
        offset = 0.0;
        offsetX = 0.0;
        offsetY = 0.0;
        offsetZ = 0.0;
        predictedX = 0.0;
        predictedY = 0.0;
        predictedZ = 0.0;
        predictedOnGround = false;
        ticksSimulated = 0;
        candidateIndex = -1;
        uncertainty = 0.0;
        cumulativeOffset = 0.0;
        cumulativeTicks = 0;
        velocityAcked = 0;
        velocityApplied = 0;
        ackedVelocityOffset = Double.NaN;
        ackedVelocityHorizontalOffset = Double.NaN;
        ackedVelocityHorizontal = 0.0;
        ackedVelocityVertical = 0.0;
    }

    /** Simulation engine only. {@code reason} should be a constant to avoid allocation. */
    public void skip(String reason) {
        reset();
        status = Status.SKIPPED;
        skipReason = reason != null ? reason : "";
    }

    /**
     * Simulation engine only.
     *
     * @param predictedX predicted movement delta (not position) on the X axis
     * @param offsetX    reported delta minus predicted delta on the X axis
     */
    public void complete(double predictedX, double predictedY, double predictedZ,
                         double offsetX, double offsetY, double offsetZ,
                         boolean predictedOnGround, int ticksSimulated, int candidateIndex, double uncertainty) {
        this.status = Status.COMPLETE;
        this.skipReason = "";
        this.predictedX = predictedX;
        this.predictedY = predictedY;
        this.predictedZ = predictedZ;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.offsetZ = offsetZ;
        this.offset = Math.sqrt(offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ);
        this.predictedOnGround = predictedOnGround;
        this.ticksSimulated = ticksSimulated;
        this.candidateIndex = candidateIndex;
        this.uncertainty = uncertainty;
    }

    /**
     * Engine only: position-domain error of the chained predictions. Unlike {@link #offset()}
     * (one packet's delta residual), this grows while a player keeps moving faster than predicted,
     * because each prediction starts from the previous <em>predicted</em> position.
     */
    public void completeCumulative(double cumulativeOffset, int cumulativeTicks) {
        this.cumulativeOffset = cumulativeOffset;
        this.cumulativeTicks = cumulativeTicks;
    }

    /**
     * Engine only: server-velocity bookkeeping of this packet.
     *
     * @param acked              offered velocities the client has definitely applied (both bracket
     *                           markers acknowledged) at the start of this packet
     * @param applied            velocities the best candidate applied
     * @param forcedOffset       3D offset of the best candidate that applies at least {@code acked}
     *                           velocities; {@code NaN} when none was verified or {@code acked == 0}
     * @param forcedHorizontal   horizontal part of {@code forcedOffset}
     * @param ackedHorizontal    horizontal speed of the newest acked velocity (blocks/tick)
     * @param ackedVertical      vertical component of the newest acked velocity (blocks/tick)
     */
    public void completeVelocity(int acked, int applied, double forcedOffset, double forcedHorizontal,
                                 double ackedHorizontal, double ackedVertical) {
        this.velocityAcked = acked;
        this.velocityApplied = applied;
        this.ackedVelocityOffset = forcedOffset;
        this.ackedVelocityHorizontalOffset = forcedHorizontal;
        this.ackedVelocityHorizontal = ackedHorizontal;
        this.ackedVelocityVertical = ackedVertical;
    }

    /** Offered velocities the client had definitely applied when this packet was simulated. */
    public int velocityAcked() {
        return velocityAcked;
    }

    /** Velocities the best candidate applied. */
    public int velocityApplied() {
        return velocityApplied;
    }

    /**
     * Offset of the best candidate that honours every acknowledged velocity; {@code NaN} when there
     * was none to honour or no such candidate was verified. Anti-knockback shows up as a large value
     * here while {@link #offset()} (which may ignore the velocity) stays small.
     */
    public double ackedVelocityOffset() {
        return ackedVelocityOffset;
    }

    /** Horizontal part of {@link #ackedVelocityOffset()}; {@code NaN} like it. */
    public double ackedVelocityHorizontalOffset() {
        return ackedVelocityHorizontalOffset;
    }

    /** Horizontal speed of the newest acknowledged velocity, 0 without one. */
    public double ackedVelocityHorizontal() {
        return ackedVelocityHorizontal;
    }

    /** Vertical component of the newest acknowledged velocity, 0 without one. */
    public double ackedVelocityVertical() {
        return ackedVelocityVertical;
    }

    public Status status() {
        return status;
    }

    public boolean complete() {
        return status == Status.COMPLETE;
    }

    public String skipReason() {
        return skipReason;
    }

    /** Total 3D offset of the best candidate. */
    public double offset() {
        return offset;
    }

    public double horizontalOffset() {
        return Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);
    }

    /** Signed vertical offset: positive means the player moved higher than predicted. */
    public double verticalOffset() {
        return offsetY;
    }

    public double offsetX() {
        return offsetX;
    }

    public double offsetY() {
        return offsetY;
    }

    public double offsetZ() {
        return offsetZ;
    }

    public double predictedX() {
        return predictedX;
    }

    public double predictedY() {
        return predictedY;
    }

    public double predictedZ() {
        return predictedZ;
    }

    public boolean predictedOnGround() {
        return predictedOnGround;
    }

    public int ticksSimulated() {
        return ticksSimulated;
    }

    /** Index of the input candidate that matched best, engine specific; -1 if none. */
    public int candidateIndex() {
        return candidateIndex;
    }

    /** Extra tolerance the engine already granted (knockback, pending state changes, ...). */
    public double uncertainty() {
        return uncertainty;
    }

    /**
     * Distance between the chained predicted position and the reported one, in blocks. Grows with
     * sustained deviation; {@code 0} when the chain was just restarted or a hard desync occurred.
     */
    public double cumulativeOffset() {
        return cumulativeOffset;
    }

    /** Number of consecutive client ticks in the current prediction chain. */
    public int cumulativeTicks() {
        return cumulativeTicks;
    }
}
