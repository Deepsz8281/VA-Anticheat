package dev.vaplus.anticheat.check.impl.world;

import com.github.retrooper.packetevents.protocol.world.BlockFace;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.world.BlockInteraction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NukerCheck (several blocks per tick, digging outside the crosshair)")
class NukerCheckTest {

    private TestContexts.Harness harness;
    private PlayerData player;
    private NukerCheck check;

    NukerCheckTest() {
        init(true);
    }

    private void init(boolean tickEnd) {
        SimulationModule simulation = new SimulationModule();
        harness = TestContexts.builder().module(simulation).module(new VisibilityChecksModule(simulation)).build();
        player = harness.newPlayer();
        if (tickEnd) {
            player.enableTickEndMode();
        }
        player.movement().applyPosition(0.5, 64.0, 0.5);
        player.movement().applyPosition(0.5, 64.0, 0.5);
        look(0f, 0f);
        look(0f, 0f);
        check = player.checks().get(NukerCheck.class);
    }

    private void look(float yaw, float pitch) {
        player.movement().applyRotation(yaw, pitch);
    }

    private void dig(BlockInteraction.Kind kind, int x, int y, int z) {
        player.interaction().assignDig(kind, x, y, z, BlockFace.UP, 0, harness.time.nanoTime());
        player.checks().dispatchInteraction(player.interaction());
    }

    private String firstAlert() {
        return harness.alerts.alerts().stream().filter(alert -> alert.check() == check)
                .findFirst().orElseThrow().details();
    }

    @Test
    @DisplayName("descriptor: id nuker, WORLD")
    void descriptor() {
        assertEquals("nuker", NukerCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.WORLD, NukerCheck.DESCRIPTOR.category());
    }

    @Test
    @DisplayName("ray/box slab test")
    void rayHitsBox() {
        assertTrue(NukerCheck.rayHitsBox(0.5, 65.62, 0.5, 0, 0, 1, 5, 0, 65, 2, 1, 66, 3));
        assertFalse(NukerCheck.rayHitsBox(0.5, 65.62, 0.5, 0, 0, 1, 5, 0, 65, -3, 1, 66, -2));
        assertFalse(NukerCheck.rayHitsBox(0.5, 65.62, 0.5, 0, 0, 1, 1.0, 0, 65, 2, 1, 66, 3), "beyond the reach");
    }

    @Test
    @DisplayName("mining the block under the crosshair, one per tick, never flags")
    void legitMining() {
        for (int i = 0; i < 20; i++) {
            look(0f, 0f);
            dig(BlockInteraction.Kind.DIG_START, 0, 65, 2);
            player.onClientTick();
            dig(BlockInteraction.Kind.DIG_FINISH, 0, 65, 2);
            player.onClientTick();
            look(0f, 90f);
            dig(BlockInteraction.Kind.DIG_START, 0, 63, 0);
            player.onClientTick();
        }
        assertEquals(0, check.violationCount());
        assertEquals(0.0, check.buffer());
    }

    @Test
    @DisplayName("the rotation sent later in the same tick counts (input before movement)")
    void rotationAfterDig() {
        for (int i = 0; i < 10; i++) {
            look(0f, 0f);
            dig(BlockInteraction.Kind.DIG_START, 0, 65, -3);
            look(180f, 0f);
            player.onClientTick();
        }
        assertEquals(0, check.violationCount());
    }

    @Test
    @DisplayName("digging blocks behind the player flags")
    void outsideView() {
        for (int i = 0; i < 6; i++) {
            look(0f, 0f);
            dig(BlockInteraction.Kind.DIG_START, i % 3 - 1, 64, -3);
            player.onClientTick();
        }
        assertTrue(check.violationCount() > 0);
        assertTrue(firstAlert().startsWith("view"));
    }

    @Test
    @DisplayName("several blocks in one tick flag")
    void multiplePerTick() {
        look(0f, 90f);
        for (int i = 0; i < 4; i++) {
            dig(BlockInteraction.Kind.DIG_START, 0, 63, 0);
            dig(BlockInteraction.Kind.DIG_START, 0, 62, 0);
            dig(BlockInteraction.Kind.DIG_START, 0, 61, 0);
            player.onClientTick();
        }
        assertTrue(check.violationCount() > 0);
        assertTrue(firstAlert().startsWith("multi"));
    }

    @Test
    @DisplayName("the same block twice in a tick is one block")
    void sameBlockTwice() {
        look(0f, 90f);
        for (int i = 0; i < 6; i++) {
            dig(BlockInteraction.Kind.DIG_START, 0, 63, 0);
            dig(BlockInteraction.Kind.DIG_FINISH, 0, 63, 0);
            player.onClientTick();
        }
        assertEquals(0, check.violationCount());
    }

    @Test
    @DisplayName("legacy clients: no tick boundary, multi is not judged")
    void legacyClient() {
        NukerCheckTest legacy = new NukerCheckTest();
        legacy.init(false);
        legacy.look(0f, 90f);
        for (int i = 0; i < 4; i++) {
            legacy.dig(BlockInteraction.Kind.DIG_START, 0, 63, 0);
            legacy.dig(BlockInteraction.Kind.DIG_START, 0, 62, 0);
            legacy.player.onClientTick();
        }
        assertEquals(0, legacy.check.violationCount());
    }
}
