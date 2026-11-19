package dev.vaplus.anticheat.world.mirror;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable physics metadata, not a complete voxel shape or a physics implementation.
 * Friction denotes Java block slipperiness, not a slowdown percentage or entity air drag.
 * Both sets are defensively copied in enum declaration order.
 */
public record BlockProperties(double friction, double speedFactor, double jumpFactor,
                              Liquid liquid, boolean climbable, Collision collision,
                              Set<SpecialFlag> flags, Set<Uncertainty> uncertainties,
                              double collisionHeight) {

    /** Full-height block (the default); kept so existing call sites and tests stay readable. */
    public BlockProperties(double friction, double speedFactor, double jumpFactor,
                           Liquid liquid, boolean climbable, Collision collision,
                           Set<SpecialFlag> flags, Set<Uncertainty> uncertainties) {
        this(friction, speedFactor, jumpFactor, liquid, climbable, collision, flags, uncertainties, 1.0);
    }

    public enum Liquid {
        NONE, WATER, LAVA
    }

    public enum Collision {
        FULL,
        EMPTY,
        /** Treat as FULL only as a placeholder; consumers must honour the uncertainty. */
        FULL_FALLBACK
    }

    public enum SpecialFlag {
        SLIME_BOUNCE,
        HONEY_STICK,
        /** Upward column from soul sand; see version-notes.md section 3.4 (26.1 changes). */
        BUBBLE_COLUMN_UP,
        /** Downward column from magma; see version-notes.md section 3.4 (26.1 changes). */
        BUBBLE_COLUMN_DOWN,
        /** Version-specific coefficient: see VanillaBlocks' pre-26.2 and 26.2 constants. */
        BED_BOUNCE,
        /** 26.3; like a bed, but no separately verified numeric impulse is supplied here. */
        SHELF_MUSHROOM_BOUNCE,
        /** 26.2 contextual geyser; this flag alone does not specify an upward impulse. */
        SULFUR_GEYSER,
        SOUL_SAND_SLOW
    }

    public enum Uncertainty {
        UNCONFIRMED_BLOCK_PROPERTIES,
        UNCONFIRMED_MISSING_BLOCK,
        UNCONFIRMED_COLLISION_SHAPE,
        UNCONFIRMED_BLOCK_STATE,
        UNCONFIRMED_PACKED_ICE_FRICTION,
        UNCONFIRMED_SOUL_SAND_SPEED_FACTOR,
        UNCONFIRMED_HONEY_SPEED_FACTOR,
        UNCONFIRMED_HONEY_JUMP_FACTOR,
        /** No numeric impulse has been confirmed; do not substitute zero. */
        UNCONFIRMED_SULFUR_GEYSER_IMPULSE
    }

    public BlockProperties {
        requireFactor(friction, "friction");
        requireFactor(speedFactor, "speedFactor");
        requireFactor(jumpFactor, "jumpFactor");
        Objects.requireNonNull(liquid, "liquid");
        Objects.requireNonNull(collision, "collision");
        if (!Double.isFinite(collisionHeight) || collisionHeight <= 0.0 || collisionHeight > 1.0) {
            throw new IllegalArgumentException("collisionHeight must be in (0, 1]: " + collisionHeight);
        }
        if (collision == Collision.EMPTY && collisionHeight != 1.0) {
            throw new IllegalArgumentException("empty blocks have no collision height");
        }

        EnumSet<SpecialFlag> flagCopy = EnumSet.noneOf(SpecialFlag.class);
        flagCopy.addAll(Objects.requireNonNull(flags, "flags"));
        if (flagCopy.contains(SpecialFlag.BUBBLE_COLUMN_UP)
                && flagCopy.contains(SpecialFlag.BUBBLE_COLUMN_DOWN)) {
            throw new IllegalArgumentException("a bubble column cannot be both up and down");
        }
        flags = Collections.unmodifiableSet(flagCopy);

        EnumSet<Uncertainty> uncertaintyCopy = EnumSet.noneOf(Uncertainty.class);
        uncertaintyCopy.addAll(Objects.requireNonNull(uncertainties, "uncertainties"));
        if (collision == Collision.FULL_FALLBACK) {
            uncertaintyCopy.add(Uncertainty.UNCONFIRMED_COLLISION_SHAPE);
        }
        uncertainties = Collections.unmodifiableSet(uncertaintyCopy);
    }

    public boolean has(SpecialFlag flag) {
        return flags.contains(Objects.requireNonNull(flag, "flag"));
    }

    public boolean uncertain() {
        return !uncertainties.isEmpty();
    }

    private static void requireFactor(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
