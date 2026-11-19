package dev.vaplus.anticheat.world.mirror;

import dev.vaplus.anticheat.world.mirror.BlockProperties.Collision;
import dev.vaplus.anticheat.world.mirror.BlockProperties.Liquid;
import dev.vaplus.anticheat.world.mirror.BlockProperties.SpecialFlag;
import dev.vaplus.anticheat.world.mirror.BlockProperties.Uncertainty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BlockProperties (immutable physics metadata)")
class BlockPropertiesTest {

    private static BlockProperties properties(double friction, Collision collision,
                                              Set<SpecialFlag> flags, Set<Uncertainty> uncertainties) {
        return new BlockProperties(friction, 1.0, 1.0, Liquid.NONE, false, collision, flags, uncertainties);
    }

    @Test
    @DisplayName("flag and uncertainty sets are defensively copied and returned unmodifiable")
    void setCopying() {
        Set<SpecialFlag> flags = new HashSet<>();
        flags.add(SpecialFlag.SLIME_BOUNCE);
        Set<Uncertainty> uncertainties = new HashSet<>();
        BlockProperties properties = properties(0.8, Collision.FULL, flags, uncertainties);

        // mutating the input sets afterwards must not change the record
        flags.add(SpecialFlag.HONEY_STICK);
        uncertainties.add(Uncertainty.UNCONFIRMED_BLOCK_PROPERTIES);
        assertEquals(Set.of(SpecialFlag.SLIME_BOUNCE), properties.flags());
        assertTrue(properties.uncertainties().isEmpty());

        assertThrows(UnsupportedOperationException.class, () -> properties.flags().add(SpecialFlag.HONEY_STICK));
        assertThrows(UnsupportedOperationException.class, () -> properties.uncertainties().add(Uncertainty.UNCONFIRMED_BLOCK_STATE));
    }

    @Test
    @DisplayName("a bubble column cannot be both up and down")
    void bubbleColumnExclusivity() {
        assertThrows(IllegalArgumentException.class, () -> new BlockProperties(0.6, 1.0, 1.0,
                Liquid.WATER, false, Collision.EMPTY,
                Set.of(SpecialFlag.BUBBLE_COLUMN_UP, SpecialFlag.BUBBLE_COLUMN_DOWN), Set.of()));
    }

    @Test
    @DisplayName("FULL_FALLBACK automatically carries the collision-shape uncertainty")
    void fullFallbackUncertainty() {
        BlockProperties fallback = properties(0.6, Collision.FULL_FALLBACK, Set.of(), Set.of());
        assertTrue(fallback.uncertain());
        assertTrue(fallback.uncertainties().contains(Uncertainty.UNCONFIRMED_COLLISION_SHAPE),
                "consumers must honour the uncertainty of the placeholder collision");

        BlockProperties full = properties(0.6, Collision.FULL, Set.of(), Set.of());
        assertFalse(full.uncertain(), "an explicit FULL with no uncertainties is certain");
    }

    @Test
    @DisplayName("factors must be finite and non-negative; nulls are rejected")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> properties(Double.NaN, Collision.FULL, Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> properties(Double.POSITIVE_INFINITY, Collision.FULL, Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> properties(-0.01, Collision.FULL, Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new BlockProperties(0.6, Double.NaN, 1.0,
                Liquid.NONE, false, Collision.FULL, Set.of(), Set.of()), "speedFactor");
        assertThrows(IllegalArgumentException.class, () -> new BlockProperties(0.6, 1.0, -1.0,
                Liquid.NONE, false, Collision.FULL, Set.of(), Set.of()), "jumpFactor");
        assertThrows(NullPointerException.class, () -> new BlockProperties(0.6, 1.0, 1.0,
                null, false, Collision.FULL, Set.of(), Set.of()), "liquid");
        assertThrows(NullPointerException.class, () -> new BlockProperties(0.6, 1.0, 1.0,
                Liquid.NONE, false, null, Set.of(), Set.of()), "collision");
        assertThrows(NullPointerException.class, () -> new BlockProperties(0.6, 1.0, 1.0,
                Liquid.NONE, false, Collision.FULL, null, Set.of()), "flags");
        assertThrows(NullPointerException.class, () -> new BlockProperties(0.6, 1.0, 1.0,
                Liquid.NONE, false, Collision.FULL, Set.of(), null), "uncertainties");
    }

    @Test
    @DisplayName("has() and uncertain() read the copied sets")
    void hasAndUncertain() {
        BlockProperties slime = properties(0.8, Collision.FULL, Set.of(SpecialFlag.SLIME_BOUNCE), Set.of());
        assertTrue(slime.has(SpecialFlag.SLIME_BOUNCE));
        assertFalse(slime.has(SpecialFlag.BED_BOUNCE));
        assertFalse(slime.uncertain());

        BlockProperties unknownLike = properties(0.6, Collision.FULL_FALLBACK,
                Set.of(), Set.of(Uncertainty.UNCONFIRMED_MISSING_BLOCK));
        assertTrue(unknownLike.uncertain());
        assertThrows(NullPointerException.class, () -> unknownLike.has(null));
    }

    @Test
    @DisplayName("climbable and liquid fields are carried through")
    void liquidAndClimbable() {
        BlockProperties water = new BlockProperties(0.6, 1.0, 1.0, Liquid.WATER, false,
                Collision.EMPTY, Set.of(), Set.of());
        assertEquals(Liquid.WATER, water.liquid());
        assertFalse(water.climbable());
        BlockProperties ladder = new BlockProperties(0.6, 1.0, 1.0, Liquid.NONE, true,
                Collision.FULL_FALLBACK, Set.of(), Set.of());
        assertTrue(ladder.climbable());
    }
}
