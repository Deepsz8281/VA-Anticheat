package dev.vaplus.anticheat.check.impl.world;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.world.BlockInteraction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BlockReachCheck (digging beyond the interaction range)")
class BlockReachCheckTest {

    private final TestContexts.Harness harness;
    private final PlayerData player;
    private final BlockReachCheck check;

    BlockReachCheckTest() {
        SimulationModule simulation = new SimulationModule();
        harness = TestContexts.builder().module(simulation).module(new VisibilityChecksModule(simulation)).build();
        player = harness.newPlayer();
        player.movement().applyPosition(0.5, 64.0, 0.5);
        player.movement().applyPosition(0.5, 64.0, 0.5);
        player.movement().applyRotation(0f, 0f);
        check = player.checks().get(BlockReachCheck.class);
    }

    private void dig(BlockInteraction.Kind kind, int x, int y, int z) {
        player.interaction().assignDig(kind, x, y, z, BlockFace.UP, 0, harness.time.nanoTime());
        player.checks().dispatchInteraction(player.interaction());
        player.onClientTick();
    }

    @Test
    @DisplayName("descriptor: id blockreach, WORLD")
    void descriptor() {
        assertEquals("blockreach", BlockReachCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.WORLD, BlockReachCheck.DESCRIPTOR.category());
    }

    @Test
    @DisplayName("blocks within 4.5 blocks of the eye never flag")
    void withinRange() {
        for (int i = 0; i < 20; i++) {
            // block (0,65,4): closest point z=4, 3.5 blocks in front of the eye
            dig(BlockInteraction.Kind.DIG_START, 0, 65, 4);
            dig(BlockInteraction.Kind.DIG_FINISH, 0, 65, 4);
            // block (0,64,5): closest point 4.5 blocks away, exactly the range
            dig(BlockInteraction.Kind.DIG_START, 0, 64, 5);
            // straight down
            dig(BlockInteraction.Kind.DIG_START, 0, 63, 0);
        }
        assertEquals(0, check.violationCount());
        assertEquals(0.0, check.buffer());
    }

    @Test
    @DisplayName("digging 5.5 blocks away flags")
    void beyondRange() {
        for (int i = 0; i < 4; i++) {
            dig(BlockInteraction.Kind.DIG_START, 0, 65, 6);
        }
        assertTrue(check.violationCount() > 0);
        assertTrue(harness.alerts.alerts().stream().anyMatch(a -> a.check() == check
                && a.details().startsWith("distance=5.50")));
    }

    @Test
    @DisplayName("creative mode gets half a block more")
    void creative() {
        player.abilities().applyGameMode(GameMode.CREATIVE);
        for (int i = 0; i < 6; i++) {
            // 4.9 blocks away: beyond survival range, within creative range
            player.movement().applyPosition(0.5, 64.0, 0.1);
            player.movement().applyPosition(0.5, 64.0, 0.1);
            dig(BlockInteraction.Kind.DIG_START, 0, 65, 5);
        }
        assertEquals(0, check.violationCount());
    }

    @Test
    @DisplayName("the eye anywhere between the previous and current position counts")
    void movingEye() {
        for (int i = 0; i < 6; i++) {
            player.movement().applyPosition(0.5, 64.0, 1.5);
            player.movement().applyPosition(0.5, 64.0, 0.5);
            // 4.6 from the current eye, 3.6 from the previous one
            dig(BlockInteraction.Kind.DIG_START, 0, 65, 5);
            player.movement().applyPosition(0.5, 64.0, 0.4);
        }
        assertEquals(0, check.violationCount());
    }
}
