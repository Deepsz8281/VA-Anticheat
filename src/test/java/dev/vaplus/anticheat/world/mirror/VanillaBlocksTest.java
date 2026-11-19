package dev.vaplus.anticheat.world.mirror;

import dev.vaplus.anticheat.world.mirror.BlockProperties.Collision;
import dev.vaplus.anticheat.world.mirror.BlockProperties.Liquid;
import dev.vaplus.anticheat.world.mirror.BlockProperties.SpecialFlag;
import dev.vaplus.anticheat.world.mirror.BlockProperties.Uncertainty;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static dev.vaplus.anticheat.world.mirror.BlockProperties.Liquid.LAVA;
import static dev.vaplus.anticheat.world.mirror.BlockProperties.Liquid.NONE;
import static dev.vaplus.anticheat.world.mirror.BlockProperties.Liquid.WATER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every asserted coefficient is block metadata with its source named in {@link VanillaBlocks}' or
 * the asserted constant's Javadoc (version-notes.md sections 3.2/3.4, MCPK Slipperiness,
 * Minecraft Wiki Blue Ice/Usage, Soul Sand/Usage, Honey Block/Usage, Java Edition 26.2/26.3).
 */
@DisplayName("VanillaBlocks (metadata table)")
class VanillaBlocksTest {

    @Test
    @DisplayName("friction constants match their cited sources")
    void frictionConstants() {
        assertEquals(0.6, VanillaBlocks.DEFAULT_FRICTION, "Java default slipperiness, MCPK Slipperiness");
        assertEquals(0.8, VanillaBlocks.SLIME_FRICTION, "MCPK Slipperiness table");
        assertEquals(0.98, VanillaBlocks.ICE_FRICTION, "Minecraft Wiki Blue Ice/Usage");
        assertEquals(0.989, VanillaBlocks.BLUE_ICE_FRICTION, "Minecraft Wiki Blue Ice/Usage (18w16a)");
        assertEquals(0.989, VanillaBlocks.UNCONFIRMED_PACKED_ICE_FRICTION,
                "deliberate UNCONFIRMED placeholder, see the constant's Javadoc");
    }

    @Test
    @DisplayName("bounce constants follow the 26.2 change: 66% before, 75% from 26.2")
    void bounceConstants() {
        assertEquals(0.66, VanillaBlocks.BED_BOUNCE_PRE_26_2, "version-notes.md section 3.2");
        assertEquals(0.75, VanillaBlocks.BED_BOUNCE_26_2, "version-notes.md section 3.2, Wiki Java Edition 26.2");
    }

    @Test
    @DisplayName("ordinary full blocks use the default properties")
    void fullBlocks() {
        BlockProperties properties = VanillaBlocks.DEFAULT_PROPERTIES;
        assertEquals(VanillaBlocks.DEFAULT_FRICTION, properties.friction());
        assertEquals(1.0, properties.speedFactor());
        assertEquals(1.0, properties.jumpFactor());
        assertEquals(NONE, properties.liquid());
        assertFalse(properties.climbable());
        assertEquals(Collision.FULL, properties.collision());
        assertTrue(properties.flags().isEmpty());
        assertFalse(properties.uncertain());

        for (BlockKey key : new BlockKey[]{VanillaBlocks.STONE, VanillaBlocks.DIRT,
                VanillaBlocks.GRASS_BLOCK, VanillaBlocks.COBBLESTONE, VanillaBlocks.BEDROCK,
                VanillaBlocks.GLASS, VanillaBlocks.MAGMA_BLOCK}) {
            assertEquals(VanillaBlocks.DEFAULT_PROPERTIES, VanillaBlocks.properties(key), key.toString());
        }
    }

    @Test
    @DisplayName("air variants are empty and friction 0.6")
    void airs() {
        for (BlockKey key : new BlockKey[]{VanillaBlocks.AIR, VanillaBlocks.CAVE_AIR, VanillaBlocks.VOID_AIR}) {
            BlockProperties properties = VanillaBlocks.properties(key);
            assertEquals(VanillaBlocks.DEFAULT_FRICTION, properties.friction());
            assertEquals(Collision.EMPTY, properties.collision());
            assertEquals(NONE, properties.liquid());
            assertFalse(properties.uncertain());
        }
    }

    @Test
    @DisplayName("slime block is sticky: friction 0.8 and the bounce flag")
    void slime() {
        BlockProperties properties = VanillaBlocks.properties(VanillaBlocks.SLIME_BLOCK);
        assertEquals(VanillaBlocks.SLIME_FRICTION, properties.friction());
        assertEquals(Collision.FULL, properties.collision());
        assertTrue(properties.has(SpecialFlag.SLIME_BOUNCE));
        assertFalse(properties.uncertain());
    }

    @Test
    @DisplayName("ice variants: ice and frosted ice 0.98; blue ice 0.989; packed ice stays unconfirmed")
    void iceVariants() {
        BlockProperties ice = VanillaBlocks.properties(VanillaBlocks.ICE);
        assertEquals(VanillaBlocks.ICE_FRICTION, ice.friction());
        assertEquals(Collision.FULL, ice.collision());
        assertFalse(ice.uncertain());
        assertEquals(ice, VanillaBlocks.properties(VanillaBlocks.FROSTED_ICE));

        BlockProperties blueIce = VanillaBlocks.properties(VanillaBlocks.BLUE_ICE);
        assertEquals(VanillaBlocks.BLUE_ICE_FRICTION, blueIce.friction());
        assertFalse(blueIce.uncertain(), "blue ice 0.989 is a confirmed value");

        BlockProperties packedIce = VanillaBlocks.properties(VanillaBlocks.PACKED_ICE);
        assertEquals(VanillaBlocks.UNCONFIRMED_PACKED_ICE_FRICTION, packedIce.friction());
        assertTrue(packedIce.uncertainties().contains(Uncertainty.UNCONFIRMED_PACKED_ICE_FRICTION),
                "the engine must not mistake the brief placeholder for a confirmed value");
    }

    @Test
    @DisplayName("soul sand and honey block carry slowdown with explicitly unconfirmed coefficients")
    void slowdownBlocks() {
        BlockProperties soulSand = VanillaBlocks.properties(VanillaBlocks.SOUL_SAND);
        assertEquals(VanillaBlocks.UNCONFIRMED_SOUL_SAND_SPEED_FACTOR, soulSand.speedFactor());
        assertTrue(soulSand.has(SpecialFlag.SOUL_SAND_SLOW));
        assertTrue(soulSand.uncertainties().contains(Uncertainty.UNCONFIRMED_SOUL_SAND_SPEED_FACTOR));
        assertEquals(Collision.FULL_FALLBACK, soulSand.collision());

        BlockProperties honey = VanillaBlocks.properties(VanillaBlocks.HONEY_BLOCK);
        assertEquals(VanillaBlocks.UNCONFIRMED_HONEY_SPEED_FACTOR, honey.speedFactor());
        assertEquals(VanillaBlocks.UNCONFIRMED_HONEY_JUMP_FACTOR, honey.jumpFactor());
        assertTrue(honey.has(SpecialFlag.HONEY_STICK));
        assertTrue(honey.uncertainties().contains(Uncertainty.UNCONFIRMED_HONEY_SPEED_FACTOR));
        assertTrue(honey.uncertainties().contains(Uncertainty.UNCONFIRMED_HONEY_JUMP_FACTOR));
        assertEquals(Collision.FULL_FALLBACK, honey.collision());
    }

    @Test
    @DisplayName("liquids: water and lava are liquids with empty collision")
    void liquids() {
        BlockProperties water = VanillaBlocks.properties(VanillaBlocks.WATER_BLOCK);
        assertEquals(WATER, water.liquid());
        assertEquals(Collision.EMPTY, water.collision());
        BlockProperties lava = VanillaBlocks.properties(VanillaBlocks.LAVA_BLOCK);
        assertEquals(LAVA, lava.liquid());
        assertEquals(Collision.EMPTY, lava.collision());
    }

    @Test
    @DisplayName("ladder and vine are climbable")
    void climbables() {
        assertTrue(VanillaBlocks.properties(VanillaBlocks.LADDER).climbable());
        assertTrue(VanillaBlocks.properties(VanillaBlocks.VINE).climbable());
        assertFalse(VanillaBlocks.properties(VanillaBlocks.STONE).climbable());
    }

    @Test
    @DisplayName("the bare bubble column entry stays direction-neutral and uncertain; feeders choose up or down")
    void bubbleColumns() {
        BlockProperties bare = VanillaBlocks.properties(VanillaBlocks.BUBBLE_COLUMN);
        assertFalse(bare.has(SpecialFlag.BUBBLE_COLUMN_UP));
        assertFalse(bare.has(SpecialFlag.BUBBLE_COLUMN_DOWN));
        assertEquals(WATER, bare.liquid());
        assertTrue(bare.uncertainties().contains(Uncertainty.UNCONFIRMED_BLOCK_STATE),
                "the state (direction, drag) is missing until a state-aware feeder overrides");

        BlockProperties up = VanillaBlocks.BUBBLE_COLUMN_UP_PROPERTIES;
        assertTrue(up.has(SpecialFlag.BUBBLE_COLUMN_UP));
        assertFalse(up.uncertain(), "version-notes.md section 3.4 confirms the 26.1 column setup");
        BlockProperties down = VanillaBlocks.BUBBLE_COLUMN_DOWN_PROPERTIES;
        assertTrue(down.has(SpecialFlag.BUBBLE_COLUMN_DOWN));
        assertFalse(down.uncertain());
    }

    @Test
    @DisplayName("the sulfur geyser override is contextual and its impulse stays unconfirmed")
    void sulfurGeyser() {
        BlockProperties geyser = VanillaBlocks.SULFUR_GEYSER_PROPERTIES;
        assertTrue(geyser.has(SpecialFlag.SULFUR_GEYSER));
        assertTrue(geyser.uncertainties().contains(Uncertainty.UNCONFIRMED_SULFUR_GEYSER_IMPULSE),
                "no numeric impulse is confirmed; the engine must not guess one");
        // plain magma stays an ordinary full block without the flag
        assertFalse(VanillaBlocks.properties(VanillaBlocks.MAGMA_BLOCK).has(SpecialFlag.SULFUR_GEYSER));
    }

    @Test
    @DisplayName("all sixteen bed colours bounce and keep a fallback collision")
    void beds() {
        BlockProperties bed = VanillaBlocks.properties(new BlockKey("minecraft:red_bed"));
        assertTrue(bed.has(SpecialFlag.BED_BOUNCE));
        assertEquals(Collision.FULL_FALLBACK, bed.collision());
        for (String color : new String[]{"white", "orange", "magenta", "light_blue", "yellow", "lime",
                "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"}) {
            assertTrue(VanillaBlocks.properties(new BlockKey("minecraft:" + color + "_bed")).has(SpecialFlag.BED_BOUNCE),
                    color + "_bed");
        }
    }

    @Test
    @DisplayName("the 26.3 shelf mushroom is bouncy; its impulse is not separately confirmed")
    void shelfMushroom() {
        BlockProperties properties = VanillaBlocks.properties(VanillaBlocks.SHELF_MUSHROOM);
        assertTrue(properties.has(SpecialFlag.SHELF_MUSHROOM_BOUNCE));
        assertEquals(Collision.FULL_FALLBACK, properties.collision());
    }

    @Test
    @DisplayName("unlisted keys keep their identity but receive the uncertain FULL fallback")
    void unlistedKeys() {
        BlockKey unlisted = new BlockKey("minecraft:cobweb");
        BlockProperties properties = VanillaBlocks.properties(unlisted);
        assertEquals(VanillaBlocks.UNKNOWN_PROPERTIES, properties);
        assertEquals(Collision.FULL_FALLBACK, properties.collision());
        assertTrue(properties.uncertainties().contains(Uncertainty.UNCONFIRMED_BLOCK_PROPERTIES));
    }

    @Test
    @DisplayName("the table is immutable and holds every declared key exactly once")
    void table() {
        Map<BlockKey, BlockProperties> table = VanillaBlocks.table();
        assertThrows(UnsupportedOperationException.class, () -> table.put(VanillaBlocks.STONE,
                VanillaBlocks.DEFAULT_PROPERTIES));
        assertEquals(VanillaBlocks.DEFAULT_PROPERTIES, table.get(VanillaBlocks.STONE));
        assertTrue(table.containsKey(VanillaBlocks.AIR));
        assertTrue(table.containsKey(VanillaBlocks.SHELF_MUSHROOM));
    }
}
