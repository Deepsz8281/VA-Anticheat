package dev.vaplus.anticheat.check.impl.combat;

/**
 * One entity interaction as seen by combat checks: the client attacked (or interacted with) an
 * entity. Reused per player; checks must not keep a reference beyond the callback.
 *
 * <p>Only {@link Kind#ATTACK} is dispatched today; {@code INTERACT}/{@code INTERACT_AT} are parsed
 * for completeness but carry no detection value yet.
 */
public final class EntityAttack {

    public enum Kind {
        ATTACK,
        INTERACT
    }

    private Kind kind = Kind.ATTACK;
    private int entityId;
    private boolean hasTarget;
    private double targetX;
    private double targetY;
    private double targetZ;
    private long timestampNanos;

    /** Pipeline only. */
    public void assign(int entityId, Kind kind, boolean hasTarget, double targetX, double targetY,
                       double targetZ, long timestampNanos) {
        this.entityId = entityId;
        this.kind = kind;
        this.hasTarget = hasTarget;
        this.targetX = targetX;
        this.targetY = targetY;
        this.targetZ = targetZ;
        this.timestampNanos = timestampNanos;
    }

    public Kind kind() {
        return kind;
    }

    /** Whether this is a real attack (left click on an entity), not a right-click interaction. */
    public boolean attacked() {
        return kind == Kind.ATTACK;
    }

    public int entityId() {
        return entityId;
    }

    /** Whether the packet carried a hit point ({@code INTERACT_AT}); attacks do not. */
    public boolean hasTarget() {
        return hasTarget;
    }

    public double targetX() {
        return targetX;
    }

    public double targetY() {
        return targetY;
    }

    public double targetZ() {
        return targetZ;
    }

    public long timestampNanos() {
        return timestampNanos;
    }
}
