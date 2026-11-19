package dev.vaplus.anticheat.world.mirror;

import dev.vaplus.anticheat.world.mirror.BlockProperties.Uncertainty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BlockSnapshot (immutable query result)")
class BlockSnapshotTest {

    @Test
    @DisplayName("UNKNOWN marks missing feeder data: unknown key, missing-block uncertainty, not known")
    void unknownConstant() {
        assertEquals(BlockKey.UNKNOWN, BlockSnapshot.UNKNOWN.key());
        assertFalse(BlockSnapshot.UNKNOWN.known());
        assertTrue(BlockSnapshot.UNKNOWN.uncertain(),
                "missing data is uncertain, never silently treated as modelled air");
        assertTrue(BlockSnapshot.UNKNOWN.properties().uncertainties().contains(Uncertainty.UNCONFIRMED_MISSING_BLOCK));
    }

    @Test
    @DisplayName("a known snapshot with certain properties is not uncertain")
    void knownCertain() {
        BlockSnapshot snapshot = new BlockSnapshot(VanillaBlocks.STONE, VanillaBlocks.DEFAULT_PROPERTIES, true);
        assertTrue(snapshot.known());
        assertFalse(snapshot.uncertain());
        assertEquals(VanillaBlocks.STONE, snapshot.key());
    }

    @Test
    @DisplayName("known but unmodelled properties and unknown flag each make the snapshot uncertain")
    void uncertaintySources() {
        BlockSnapshot uncertainProperties = new BlockSnapshot(VanillaBlocks.SLIME_BLOCK,
                VanillaBlocks.UNKNOWN_PROPERTIES, true);
        assertTrue(uncertainProperties.uncertain(), "uncertain properties alone make the snapshot uncertain");

        BlockSnapshot notKnown = new BlockSnapshot(VanillaBlocks.STONE, VanillaBlocks.DEFAULT_PROPERTIES, false);
        assertTrue(notKnown.uncertain(), "unknown provenance alone makes the snapshot uncertain");
    }

    @Test
    @DisplayName("nulls are rejected")
    void validation() {
        assertThrows(NullPointerException.class, () -> new BlockSnapshot(null, VanillaBlocks.DEFAULT_PROPERTIES, true));
        assertThrows(NullPointerException.class, () -> new BlockSnapshot(VanillaBlocks.STONE, null, true));
    }
}
