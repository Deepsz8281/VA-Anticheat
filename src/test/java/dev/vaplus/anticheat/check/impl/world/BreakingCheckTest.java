package dev.vaplus.anticheat.check.impl.world;

import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.world.BlockInteraction;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BreakingCheck (air/liquid, faces, swings, hidden faces, aborts)")
class BreakingCheckTest {

    private final SimulationModule simulation = new SimulationModule();
    private final TestContexts.Harness harness = TestContexts.builder()
            .module(simulation)
            .check(BreakingCheck.DESCRIPTOR, p -> new BreakingCheck(p, simulation.worldMirrorKey()))
            .build();
    private final PlayerData player = harness.newPlayer();
    private final BreakingCheck check = player.checks().get(BreakingCheck.class);
    private int sequence;

    BreakingCheckTest() {
        player.enableTickEndMode();
        player.movement().applyPosition(0.5, 64.0, 0.5);
        player.movement().applyPosition(0.5, 64.0, 0.5);
        WorldMirror world = player.component(simulation.worldMirrorKey());
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                world.setBlock(new BlockPos(x, 63, z), VanillaBlocks.STONE);
                for (int y = 64; y <= 67; y++) {
                    world.setBlock(new BlockPos(x, y, z), VanillaBlocks.AIR);
                }
            }
        }
        world.setBlock(new BlockPos(0, 64, 2), VanillaBlocks.STONE);
    }

    private void dig(BlockInteraction.Kind kind, int x, int y, int z, BlockFace face) {
        BlockInteraction interaction = new BlockInteraction();
        interaction.assignDig(kind, x, y, z, face, kind == BlockInteraction.Kind.DIG_CANCEL ? 0 : ++sequence, 0L);
        player.checks().dispatchInteraction(interaction);
    }

    private void swingAndTick() {
        check.onSwing();
        player.onClientTick();
    }

    private boolean flagged(String prefix) {
        return harness.alerts.alerts().stream().anyMatch(a -> a.check() == check && a.details().startsWith(prefix));
    }

    @Test
    @DisplayName("descriptor: id breaking, WORLD")
    void descriptor() {
        assertEquals("breaking", BreakingCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.WORLD, BreakingCheck.DESCRIPTOR.category());
    }

    @Test
    @DisplayName("digging visible stone with swings never flags")
    void legit() {
        for (int i = 0; i < 10; i++) {
            dig(BlockInteraction.Kind.DIG_START, 0, 64, 2, BlockFace.NORTH);
            swingAndTick();
            dig(BlockInteraction.Kind.DIG_CANCEL, 0, 64, 2, BlockFace.DOWN);
            swingAndTick();
            dig(BlockInteraction.Kind.DIG_START, 0, 63, 0, BlockFace.UP);
            swingAndTick();
        }
        assertEquals(0, check.violationCount(), harness.alerts.alerts()::toString);
    }

    @Test
    @DisplayName("air-liquid: digging air flags")
    void air() {
        dig(BlockInteraction.Kind.DIG_START, 1, 65, 1, BlockFace.UP);
        swingAndTick();
        assertTrue(flagged("air-liquid"), harness.alerts.alerts()::toString);
    }

    @Test
    @DisplayName("face: a raw face id outside 0..5 flags")
    void faceId() {
        check.onFaceId(3, DiggingAction.START_DIGGING);
        assertEquals(0, check.violationCount());
        check.onFaceId(9, DiggingAction.START_DIGGING);
        assertTrue(flagged("face id=9"));
    }

    @Test
    @DisplayName("no-swing: digging without swinging flags")
    void noSwing() {
        dig(BlockInteraction.Kind.DIG_START, 0, 64, 2, BlockFace.NORTH);
        player.onClientTick();
        dig(BlockInteraction.Kind.DIG_START, 0, 64, 2, BlockFace.NORTH);
        player.onClientTick();
        assertTrue(flagged("no-swing"), harness.alerts.alerts()::toString);
    }

    @Test
    @DisplayName("position: digging the far (SOUTH) face of a block in front flags")
    void hiddenFace() {
        dig(BlockInteraction.Kind.DIG_START, 0, 64, 2, BlockFace.SOUTH);
        swingAndTick();
        assertTrue(flagged("position face=SOUTH"), harness.alerts.alerts()::toString);
    }

    @Test
    @DisplayName("cancel-face: an abort with a face that is not followed by a start on it flags")
    void cancelFace() {
        dig(BlockInteraction.Kind.DIG_START, 0, 64, 2, BlockFace.NORTH);
        dig(BlockInteraction.Kind.DIG_CANCEL, 0, 64, 2, BlockFace.UP);
        dig(BlockInteraction.Kind.DIG_START, 0, 63, 0, BlockFace.UP);
        swingAndTick();
        assertEquals(0, check.violationCount(), harness.alerts.alerts()::toString);
        dig(BlockInteraction.Kind.DIG_CANCEL, 0, 63, 0, BlockFace.WEST);
        dig(BlockInteraction.Kind.DIG_START, 0, 64, 2, BlockFace.NORTH);
        swingAndTick();
        assertTrue(flagged("cancel-face"), harness.alerts.alerts()::toString);
    }
}
