package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.pipeline.CoreTrackingModule;
import dev.vaplus.anticheat.pipeline.MotionTrackingModule;
import dev.vaplus.anticheat.pipeline.MovementProcessor;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.world.mirror.BlockKey;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("StepCheck (replayed position packets)")
class StepCheckTest {

    /** Vanilla jump motion: 0.42, then (v - 0.08) * 0.98 per tick. */
    private static final double[] JUMP = {0.42, 0.3332, 0.2486, 0.1670, 0.0871, 0.0090};

    private SimulationModule simulation;
    private TestContexts.Harness harness;
    private PlayerData player;
    private MovementProcessor processor;
    private StepCheck check;
    private double x = 0.5;
    private double y = 64.0;
    private double z = 0.5;

    StepCheckTest() {
        init(true);
    }

    /** A fresh rig for a legacy client (no tick-end packets). */
    private static StepCheckTest legacy() {
        StepCheckTest test = new StepCheckTest();
        test.init(false);
        return test;
    }

    private void init(boolean tickEnd) {
        x = 0.5;
        y = 64.0;
        z = 0.5;
        simulation = new SimulationModule();
        harness = TestContexts.builder()
                .module(new CoreTrackingModule(false))
                .module(new MotionTrackingModule())
                .module(simulation)
                .module(new StepChecksModule(simulation))
                .build();
        player = harness.newPlayer();
        processor = new MovementProcessor(harness.context.setup());
        if (tickEnd) {
            player.enableTickEndMode();
        }
        check = player.checks().get(StepCheck.class);
        WorldMirror world = player.component(simulation.worldMirrorKey());
        fill(world, VanillaBlocks.STONE, 63, 63);
        fill(world, VanillaBlocks.AIR, 64, 75);
        processor.process(player, true, x, y, z, true, 0f, 0f, true, false);
    }

    private static void fill(WorldMirror world, BlockKey block, int minY, int maxY) {
        for (int bx = -6; bx <= 6; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = -6; bz <= 6; bz++) {
                    world.setBlock(new BlockPos(bx, by, bz), block);
                }
            }
        }
    }

    /** One client tick with one position packet. */
    private void tick(double dx, double dy, boolean ground) {
        player.onClientTick();
        packet(dx, dy, ground);
    }

    /** Another position packet inside the current tick. */
    private void packet(double dx, double dy, boolean ground) {
        x += dx;
        y += dy;
        processor.process(player, true, x, y, z, true, 0f, 0f, ground, false);
    }

    private void idleTicks(int count) {
        for (int i = 0; i < count; i++) {
            tick(0.0, 0.0, true);
        }
    }

    private WorldMirror world() {
        return player.component(simulation.worldMirrorKey());
    }

    @Test
    @DisplayName("descriptor: id step, MOVEMENT")
    void descriptor() {
        assertEquals("step", StepCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.MOVEMENT, StepCheck.DESCRIPTOR.category());
    }

    @Test
    @DisplayName("slabs, stairs, 0.6 steps and a normal jump never flag")
    void legitSteps() {
        for (int i = 0; i < 20; i++) {
            tick(0.2, 0.5, true);
            tick(0.2, -0.5, true);
            tick(0.2, 0.6, true);
            tick(0.2, -0.6, true);
        }
        for (int jump = 0; jump < 5; jump++) {
            for (double dy : JUMP) {
                tick(0.2, dy, false);
            }
            for (int i = 0; i < 6; i++) {
                tick(0.2, -0.1, false);
            }
            tick(0.0, -0.2, true);
        }
        assertEquals(0, check.violationCount());
        assertEquals(0.0, check.buffer());
    }

    @Test
    @DisplayName("jumping up onto a one block ledge never flags")
    void jumpOntoBlock() {
        for (double dy : JUMP) {
            tick(0.2, dy, false);
        }
        tick(0.2, -0.0784, false);
        tick(0.2, -0.1, true);
        idleTicks(5);
        assertEquals(0, check.violationCount());
    }

    @Test
    @DisplayName("jump boost II permits a 0.62 first tick")
    void jumpBoost() {
        player.effects().apply(TrackedEffect.JUMP_BOOST, 1);
        for (int i = 0; i < 5; i++) {
            tick(0.2, 0.62, false);
            tick(0.0, -0.62, true);
        }
        assertEquals(0, check.violationCount());
    }

    @Test
    @DisplayName("a server-raised step_height attribute is honoured")
    void raisedStepHeight() {
        player.attributes().stepHeight(1.0);
        for (int i = 0; i < 6; i++) {
            tick(0.2, 1.0, true);
            tick(0.2, -1.0, true);
        }
        assertEquals(0, check.violationCount());
        for (int i = 0; i < 4; i++) {
            tick(0.2, 1.5, true);
            tick(0.2, -1.5, true);
        }
        assertTrue(check.violationCount() > 0);
    }

    @Test
    @DisplayName("1.0, 1.5 and 2.0 block steps flag")
    void bigSteps() {
        for (double height : new double[]{1.0, 1.5, 2.0}) {
            StepCheckTest test = new StepCheckTest();
            for (int i = 0; i < 4; i++) {
                test.tick(0.2, height, true);
                test.tick(0.2, -height, true);
            }
            assertTrue(test.check.violationCount() > 0, "height " + height);
            assertTrue(test.harness.alerts.alerts().get(0).details().startsWith("rise="));
        }
    }

    @Test
    @DisplayName("packet step (0.42 + 0.33 + 0.25 within one tick) flags")
    void packetStep() {
        for (int i = 0; i < 4; i++) {
            player.onClientTick();
            packet(0.1, 0.42, false);
            packet(0.1, 0.3332, false);
            packet(0.1, 0.2486, true);
            tick(0.1, -1.0, true);
        }
        assertTrue(check.violationCount() > 0);
        assertTrue(harness.alerts.alerts().get(0).details().contains("packets=2"), "flags as soon as the summed rise passes the limit");
    }

    @Test
    @DisplayName("packet step is not judged when check-packet-step is off (single packets are legal)")
    void packetStepOff() {
        StepCheckTest test = new StepCheckTest();
        test.harness.applySettings(test.player, d -> {
            var base = dev.vaplus.anticheat.check.CheckSettings.of(d.defaults(), java.util.List.of());
            return d == StepCheck.DESCRIPTOR
                    ? new dev.vaplus.anticheat.check.CheckSettings(base.enabled(), base.bufferLimit(),
                    base.bufferDecay(), base.bufferMax(), base.vlDecayPerMinute(), base.alertVl(), base.setbackVl(),
                    base.actions(), new dev.vaplus.anticheat.check.CheckOptions(
                    java.util.Map.of("check-packet-step", false)))
                    : base;
        });
        for (int i = 0; i < 4; i++) {
            test.player.onClientTick();
            test.packet(0.1, 0.42, false);
            test.packet(0.1, 0.3332, false);
            test.packet(0.1, 0.2486, true);
            test.tick(0.1, -1.0, true);
        }
        assertEquals(0, test.check.violationCount());
    }

    @Test
    @DisplayName("legacy clients: every packet is a tick, an oversized single packet flags")
    void legacyClient() {
        StepCheckTest test = legacy();
        for (int i = 0; i < 4; i++) {
            test.packet(0.2, 1.0, true);
            test.packet(0.2, -1.0, true);
        }
        assertTrue(test.check.violationCount() > 0);
    }

    @Test
    @DisplayName("slime block, bubble column and piston next to the player are not judged")
    void specialBlocks() {
        world().setBlock(new BlockPos(0, 63, 0), VanillaBlocks.SLIME_BLOCK);
        for (int i = 0; i < 6; i++) {
            tick(0.0, 1.5, true);
            tick(0.0, -1.5, true);
        }
        assertEquals(0, check.violationCount());
        assertEquals(0.0, check.buffer());

        world().setBlock(new BlockPos(0, 63, 0), VanillaBlocks.STONE);
        world().setBlock(new BlockPos(0, 64, 0), VanillaBlocks.BUBBLE_COLUMN);
        for (int i = 0; i < 6; i++) {
            tick(0.0, 1.5, true);
            tick(0.0, -1.5, true);
        }
        assertEquals(0, check.violationCount());

        world().setBlock(new BlockPos(0, 64, 0), VanillaBlocks.AIR);
        world().setBlock(new BlockPos(0, 63, 0), new BlockKey("minecraft:piston_head"));
        for (int i = 0; i < 6; i++) {
            tick(0.0, 1.5, true);
            tick(0.0, -1.5, true);
        }
        assertEquals(0, check.violationCount());
    }

    @Test
    @DisplayName("unknown world data means no judgement")
    void unknownWorld() {
        StepCheckTest test = new StepCheckTest();
        test.world().clear();
        for (int i = 0; i < 6; i++) {
            test.tick(0.0, 1.5, true);
            test.tick(0.0, -1.5, true);
        }
        assertEquals(0, test.check.violationCount());
        assertEquals(0.0, test.check.buffer());
    }

    @Test
    @DisplayName("exemptions, velocity, flight, levitation, vehicle and pending attributes are not judged")
    void exclusions() {
        player.exemptions().grant(ExemptionCause.TELEPORT, 1000);
        stepBurst();
        assertEquals(0, check.violationCount());
        player.exemptions().clear();

        player.component(simulation.velocityKey()).register(VelocityState.Kind.SET, 0, 1.0, 0, 0);
        stepBurst();
        assertEquals(0, check.violationCount());
    }

    @Test
    @DisplayName("levitation, flying and vehicles are not judged")
    void stateExclusions() {
        player.effects().apply(TrackedEffect.LEVITATION, 0);
        stepBurst();
        assertEquals(0, check.violationCount());
        player.effects().remove(TrackedEffect.LEVITATION);

        player.vehicle().mount(5);
        stepBurst();
        assertEquals(0, check.violationCount());
        player.vehicle().dismount();

        player.attributes().beginPending();
        stepBurst();
        assertEquals(0, check.violationCount());
    }

    @Test
    @DisplayName("airborne rises (not starting on the ground) are left to Fly")
    void airborneNotJudged() {
        for (int i = 0; i < 6; i++) {
            tick(0.0, 0.1, false);
            tick(0.0, 1.5, false);
            tick(0.0, -1.5, false);
        }
        assertEquals(0, check.violationCount());
    }

    @Test
    @DisplayName("a setback is requested for a step hack")
    void setback() {
        for (int i = 0; i < 4; i++) {
            tick(0.2, 1.0, true);
            tick(0.2, -1.0, true);
        }
        assertTrue(check.violationCount() > 0);
        assertTrue(player.movement().setbackRequested() || harness.setbacks != null);
    }

    private void stepBurst() {
        for (int i = 0; i < 6; i++) {
            tick(0.0, 1.5, true);
            tick(0.0, -1.5, true);
        }
    }
}
