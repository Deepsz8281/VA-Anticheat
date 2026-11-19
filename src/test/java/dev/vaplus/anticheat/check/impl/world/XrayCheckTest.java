package dev.vaplus.anticheat.check.impl.world;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.world.BlockInteraction;
import dev.vaplus.anticheat.world.mirror.BlockKey;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("XrayCheck (enclosed valuable ores)")
class XrayCheckTest {

    private static final BlockKey ORE = new BlockKey("minecraft:diamond_ore");
    private static final int[][] NEIGHBOURS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private record Fixture(Harness harness, PlayerData player, WorldMirror world, XrayCheck check) {
    }

    private static Fixture fixture() {
        SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
        Harness harness = TestContexts.builder()
                .module(simulation)
                .module(new VisibilityChecksModule(simulation))
                .build();
        PlayerData player = harness.newPlayer();
        return new Fixture(harness, player, player.component(simulation.worldMirrorKey()),
                player.checks().get(XrayCheck.class));
    }

    private static void set(WorldMirror world, int x, int y, int z, BlockKey key) {
        world.setBlock(new BlockPos(x, y, z), key);
    }

    /** Ore whose six neighbours are all stone: invisible without X-Ray. */
    private static void hiddenOre(WorldMirror world, int x, int y, int z) {
        for (int[] offset : NEIGHBOURS) {
            set(world, x + offset[0], y + offset[1], z + offset[2], VanillaBlocks.STONE);
        }
        set(world, x, y, z, ORE);
    }

    private static void startDigging(PlayerData player, int x, int y, int z) {
        player.interaction().assignDig(BlockInteraction.Kind.DIG_START, x, y, z, BlockFace.UP, 0, 0L);
        player.checks().dispatchInteraction(player.interaction());
    }

    private static CheckSettings withOptions(CheckDefaults defaults, Map<String, ?> options) {
        return new CheckSettings(defaults.enabled(), defaults.bufferLimit(), defaults.bufferDecay(),
                defaults.bufferMax(), defaults.vlDecayPerMinute(), defaults.alertVl(), defaults.setbackVl(),
                List.of(), new CheckOptions(options));
    }

    @Test
    @DisplayName("descriptor is the world-category xray check registered by the visibility module")
    void descriptor() {
        Fixture fixture = fixture();
        assertNotNull(fixture.check());
        assertEquals("xray", XrayCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.WORLD, XrayCheck.DESCRIPTOR.category());
        assertTrue(XrayCheck.DESCRIPTOR.experimental());
        assertEquals(12, XrayCheck.DEFAULT_VALUABLE.size());
        assertTrue(XrayCheck.DEFAULT_VALUABLE.contains("minecraft:diamond_ore"));
    }

    @Test
    @DisplayName("three enclosed ore finds flag; two only fill the buffer")
    void enclosedOresFlag() {
        Fixture fixture = fixture();
        hiddenOre(fixture.world(), 10, 10, 10);
        hiddenOre(fixture.world(), 14, 10, 10);
        hiddenOre(fixture.world(), 18, 10, 10);

        startDigging(fixture.player(), 10, 10, 10);
        startDigging(fixture.player(), 14, 10, 10);
        assertEquals(0, fixture.check().violationCount());
        assertEquals(2.0, fixture.check().buffer(), 1e-9);

        startDigging(fixture.player(), 18, 10, 10);
        assertEquals(1, fixture.check().violationCount());
        assertEquals(0.0, fixture.check().buffer(), 1e-9, "a flag starts a fresh window");
        String details = fixture.harness().alerts.alerts().get(0).details();
        assertTrue(details.contains("ore=minecraft:diamond_ore"), details);
        assertTrue(details.contains("hidden=yes"), details);
    }

    @Test
    @DisplayName("an ore with an open neighbour is ignored")
    void exposedOreIsIgnored() {
        Fixture fixture = fixture();
        for (int[] offset : NEIGHBOURS) {
            set(fixture.world(), 10 + offset[0], 10 + offset[1], 10 + offset[2], VanillaBlocks.STONE);
        }
        set(fixture.world(), 10, 10, 10, ORE);
        set(fixture.world(), 11, 10, 10, VanillaBlocks.AIR);

        for (int i = 0; i < 10; i++) {
            startDigging(fixture.player(), 10, 10, 10);
        }
        assertEquals(0.0, fixture.check().buffer());
        assertEquals(0, fixture.check().violationCount());
    }

    @Test
    @DisplayName("an ore with a non-mirrored neighbour is ignored (that neighbour could be air)")
    void unknownNeighbourIsIgnored() {
        Fixture fixture = fixture();
        // five stone neighbours; the sixth position is never mirrored and reads UNKNOWN
        for (int i = 0; i < 5; i++) {
            int[] offset = NEIGHBOURS[i];
            set(fixture.world(), 10 + offset[0], 10 + offset[1], 10 + offset[2], VanillaBlocks.STONE);
        }
        set(fixture.world(), 10, 10, 10, ORE);
        startDigging(fixture.player(), 10, 10, 10);
        assertEquals(0.0, fixture.check().buffer());
        assertEquals(0, fixture.check().violationCount());
    }

    @Test
    @DisplayName("ordinary blocks are not valuable")
    void stoneIsNotValuable() {
        Fixture fixture = fixture();
        hiddenOre(fixture.world(), 10, 10, 10);
        for (int[] offset : NEIGHBOURS) {
            set(fixture.world(), 20 + offset[0], 10 + offset[1], 10 + offset[2], VanillaBlocks.STONE);
        }
        set(fixture.world(), 20, 10, 10, VanillaBlocks.STONE);
        for (int i = 0; i < 10; i++) {
            startDigging(fixture.player(), 20, 10, 10);
        }
        assertEquals(0.0, fixture.check().buffer());
    }

    @Test
    @DisplayName("only-hidden: false counts exposed ores as well")
    void onlyHiddenCanBeDisabled() {
        Fixture fixture = fixture();
        fixture.harness().applySettings(fixture.player(),
                descriptor -> withOptions(descriptor.defaults(), Map.of("only-hidden", false)));
        set(fixture.world(), 10, 10, 10, ORE);
        set(fixture.world(), 14, 10, 10, ORE);
        set(fixture.world(), 18, 10, 10, ORE);

        hiddenOre(fixture.world(), 10, 10, 10);
        hiddenOre(fixture.world(), 14, 10, 10);
        hiddenOre(fixture.world(), 18, 10, 10);
        startDigging(fixture.player(), 10, 10, 10);
        startDigging(fixture.player(), 14, 10, 10);
        startDigging(fixture.player(), 18, 10, 10);
        assertEquals(1, fixture.check().violationCount());
        assertTrue(fixture.harness().alerts.alerts().get(0).details().contains("hidden=no"));
    }

    @Test
    @DisplayName("repeated reports of the same block count once")
    void sameBlockCountsOnce() {
        Fixture fixture = fixture();
        hiddenOre(fixture.world(), 10, 10, 10);
        for (int i = 0; i < 5; i++) {
            startDigging(fixture.player(), 10, 10, 10);
        }
        assertEquals(1.0, fixture.check().buffer(), 1e-9);
    }

    @Test
    @DisplayName("creative players are exempt through the world category")
    void creativeIsExempt() {
        Fixture fixture = fixture();
        fixture.player().abilities().applyGameMode(GameMode.CREATIVE);
        hiddenOre(fixture.world(), 10, 10, 10);
        hiddenOre(fixture.world(), 14, 10, 10);
        hiddenOre(fixture.world(), 18, 10, 10);
        startDigging(fixture.player(), 10, 10, 10);
        startDigging(fixture.player(), 14, 10, 10);
        startDigging(fixture.player(), 18, 10, 10);
        assertEquals(0.0, fixture.check().buffer());
        assertEquals(0, fixture.check().violationCount());
    }

    @Test
    @DisplayName("configured ids are normalized; an empty list restores the defaults")
    void configuredIds() {
        assertEquals(List.of("minecraft:emerald_ore"),
                List.copyOf(XrayCheck.valuableBlocks(List.of("emerald_ore"))));
        assertEquals(List.of("minecraft:emerald_ore"),
                List.copyOf(XrayCheck.valuableBlocks(List.of("  MINECRAFT:Emerald_Ore "))));
        assertEquals(XrayCheck.DEFAULT_VALUABLE, XrayCheck.valuableBlocks(List.of()));
    }
}
