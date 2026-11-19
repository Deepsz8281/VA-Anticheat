package dev.vaplus.anticheat.check.impl.combat;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.module.ModuleContext;
import dev.vaplus.anticheat.pipeline.CoreTrackingModule;
import dev.vaplus.anticheat.pipeline.MotionTrackingModule;
import dev.vaplus.anticheat.pipeline.MovementProcessor;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.world.BlockInteraction;
import dev.vaplus.anticheat.world.mirror.BlockKey;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SilentRotationCheck (replayed movement, rotations and item use)")
class SilentRotationCheckTest {

    private static final double SPRINT = 0.28;

    private final SimulationModule simulation = new SimulationModule();
    private final TestContexts.Harness harness;
    private final PlayerData player;
    private final MovementProcessor processor;
    private final SilentRotationCheck check;
    private double x = 0.5;
    private final double y = 64.0;
    private double z = 0.5;
    private float yaw;
    private float pitch;

    SilentRotationCheckTest() {
        harness = TestContexts.builder()
                .module(new CoreTrackingModule(false))
                .module(new MotionTrackingModule())
                .module(simulation)
                .module(new AnticheatModule() {
                    @Override
                    public String name() {
                        return "silentrotation-only";
                    }

                    @Override
                    public void register(ModuleContext context) {
                        var world = simulation.worldMirrorKey();
                        var velocity = simulation.velocityKey();
                        context.registerCheck(SilentRotationCheck.DESCRIPTOR,
                                p -> new SilentRotationCheck(p, world, velocity));
                    }
                })
                .build();
        player = harness.newPlayer();
        processor = new MovementProcessor(harness.context.setup());
        player.enableTickEndMode();
        check = player.checks().get(SilentRotationCheck.class);
        fill(VanillaBlocks.STONE, 63, 63);
        fill(VanillaBlocks.AIR, 64, 70);
        processor.process(player, true, x, y, z, true, 0f, 0f, true, false);
    }

    private void fill(BlockKey block, int minY, int maxY) {
        WorldMirror world = player.component(simulation.worldMirrorKey());
        for (int bx = -40; bx <= 40; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = -40; bz <= 40; bz++) {
                    world.setBlock(new BlockPos(bx, by, bz), block);
                }
            }
        }
    }

    /** One tick holding W only, moving by (dx, dz) and sending the given yaw. */
    private void run(double dx, double dz, float sentYaw) {
        player.onClientTick();
        player.pose().applyInput(true, false, false, false, false, false, true, player.clientTicks());
        x += dx;
        z += dz;
        yaw = sentYaw;
        processor.process(player, true, x, y, z, true, yaw, pitch, true, false);
    }

    private void look(float toYaw, float toPitch) {
        player.onClientTick();
        yaw = toYaw;
        pitch = toPitch;
        processor.process(player, false, x, y, z, true, yaw, pitch, true, false);
    }

    private void useItem() {
        BlockInteraction use = new BlockInteraction();
        use.assignUse(InteractionHand.MAIN_HAND, 1, System.nanoTime());
        player.checks().dispatchInteraction(use);
    }

    @Test
    @DisplayName("descriptor: id silentrotation, COMBAT, experimental")
    void descriptor() {
        assertEquals("silentrotation", SilentRotationCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.COMBAT, SilentRotationCheck.DESCRIPTOR.category());
        assertTrue(SilentRotationCheck.DESCRIPTOR.experimental());
    }

    @Test
    @DisplayName("running forward along the sent yaw never flags")
    void legitRunning() {
        // yaw -90 faces +x
        for (int i = 0; i < 60; i++) {
            run(SPRINT, 0.0, -90f);
        }
        assertEquals(0, check.violationCount());
        assertEquals(0.0, check.buffer());
    }

    @Test
    @DisplayName("moving 90 degrees off a steady sent yaw with W only flags")
    void movingOffTheSentYaw() {
        // camera faces +x, the packets claim +z (yaw 0)
        for (int i = 0; i < 60; i++) {
            run(SPRINT, 0.0, 0f);
        }
        assertTrue(check.violationCount() > 0);
        assertTrue(harness.alerts.alerts().get(0).details().startsWith("move"));
    }

    @Test
    @DisplayName("strafing (W + A) is not judged")
    void strafing() {
        for (int i = 0; i < 60; i++) {
            player.onClientTick();
            player.pose().applyInput(true, false, true, false, false, false, true, player.clientTicks());
            x += SPRINT;
            processor.process(player, true, x, y, z, true, 0f, 0f, true, false);
        }
        assertEquals(0, check.violationCount());
    }

    @Test
    @DisplayName("ice under the feet is not judged")
    void ice() {
        fill(VanillaBlocks.ICE, 63, 63);
        for (int i = 0; i < 60; i++) {
            run(SPRINT, 0.0, 0f);
        }
        assertEquals(0, check.violationCount());
    }

    @Test
    @DisplayName("snap away, use an item, snap back within 2 ticks flags")
    void snapUseReturn() {
        for (int i = 0; i < 4; i++) {
            look(120f, 10f);
            useItem();
            look(0.5f, 0f);
            for (int t = 0; t < 10; t++) {
                look(0.5f, 0f);
            }
        }
        assertTrue(check.violationCount() > 0);
        assertTrue(harness.alerts.alerts().get(0).details().contains("action=use_item"));
    }

    @Test
    @DisplayName("a human flick (slow return) and a snap without item use never flag")
    void humanFlicks() {
        for (int i = 0; i < 6; i++) {
            look(120f, 10f);
            useItem();
            look(90f, 8f);
            look(60f, 5f);
            look(30f, 2f);
            look(0f, 0f);
            for (int t = 0; t < 10; t++) {
                look(0f, 0f);
            }
            look(120f, 10f);
            look(0f, 0f);
        }
        assertEquals(0, check.violationCount());
    }
}
