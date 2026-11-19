package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.module.ModuleContext;
import dev.vaplus.anticheat.pipeline.CoreTrackingModule;
import dev.vaplus.anticheat.pipeline.MotionTrackingModule;
import dev.vaplus.anticheat.pipeline.MovementProcessor;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.world.mirror.BlockKey;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HighJumpCheck (replayed position packets)")
class HighJumpCheckTest {

    private final SimulationModule simulation = new SimulationModule();
    private final TestContexts.Harness harness;
    private final PlayerData player;
    private final MovementProcessor processor;
    private final HighJumpCheck check;
    private double x = 0.5;
    private double y = 64.0;
    private final double z = 0.5;

    HighJumpCheckTest() {
        harness = TestContexts.builder()
                .module(new CoreTrackingModule(false))
                .module(new MotionTrackingModule())
                .module(simulation)
                .module(new AnticheatModule() {
                    @Override
                    public String name() {
                        return "highjump-only";
                    }

                    @Override
                    public void register(ModuleContext context) {
                        var world = simulation.worldMirrorKey();
                        var velocity = simulation.velocityKey();
                        context.registerCheck(HighJumpCheck.DESCRIPTOR, p -> new HighJumpCheck(p, world, velocity));
                    }
                })
                .build();
        player = harness.newPlayer();
        processor = new MovementProcessor(harness.context.setup());
        player.enableTickEndMode();
        check = player.checks().get(HighJumpCheck.class);
        fill(VanillaBlocks.STONE, 63, 63);
        fill(VanillaBlocks.AIR, 64, 80);
        processor.process(player, true, x, y, z, true, 0f, 0f, true, false);
    }

    private void fill(BlockKey block, int minY, int maxY) {
        WorldMirror world = world();
        for (int bx = -6; bx <= 30; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = -6; bz <= 6; bz++) {
                    world.setBlock(new BlockPos(bx, by, bz), block);
                }
            }
        }
    }

    private WorldMirror world() {
        return player.component(simulation.worldMirrorKey());
    }

    private void tick(double dx, double dy, boolean ground) {
        player.onClientTick();
        x += dx;
        y += dy;
        processor.process(player, true, x, y, z, true, 0f, 0f, ground, false);
    }

    /** Vanilla vertical motion of a jump with the given start velocity, until back on the start level. */
    private static List<Double> jump(double velocity) {
        List<Double> deltas = new ArrayList<>();
        double v = velocity;
        double height = 0.0;
        while (deltas.size() < 200) {
            if (height + v <= 0.0) {
                deltas.add(-height);
                break;
            }
            deltas.add(v);
            height += v;
            v = (v - 0.08) * 0.98;
        }
        return deltas;
    }

    private void doJump(double velocity) {
        List<Double> deltas = jump(velocity);
        for (int i = 0; i < deltas.size(); i++) {
            tick(0.1, deltas.get(i), i == deltas.size() - 1);
        }
        tick(0.1, 0.0, true);
    }

    @Test
    @DisplayName("descriptor: id highjump, MOVEMENT")
    void descriptor() {
        assertEquals("highjump", HighJumpCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.MOVEMENT, HighJumpCheck.DESCRIPTOR.category());
    }

    @Test
    @DisplayName("the default apex is the vanilla jump height (about 1.25 blocks)")
    void apex() {
        double apex = HighJumpCheck.apex(0.42, 0.08);
        assertTrue(apex > 1.24 && apex < 1.26, "apex " + apex);
        assertEquals(Double.POSITIVE_INFINITY, HighJumpCheck.apex(0.42, 0.0));
        assertEquals(0.0, HighJumpCheck.apex(0.0, 0.08));
    }

    @Test
    @DisplayName("vanilla jumps never flag")
    void legitJumps() {
        for (int i = 0; i < 10; i++) {
            doJump(0.42);
        }
        assertEquals(0, check.violationCount());
        assertEquals(0.0, check.buffer());
    }

    @Test
    @DisplayName("jump boost II jumps are honoured")
    void jumpBoost() {
        player.effects().apply(TrackedEffect.JUMP_BOOST, 1);
        for (int i = 0; i < 6; i++) {
            doJump(0.62);
        }
        assertEquals(0, check.violationCount());
    }

    @Test
    @DisplayName("a raised jump velocity flags")
    void highJump() {
        for (int i = 0; i < 4; i++) {
            doJump(0.6);
        }
        assertTrue(check.violationCount() > 0);
        assertTrue(harness.alerts.alerts().get(0).details().startsWith("rise="));
    }

    @Test
    @DisplayName("an air jump on top of a normal jump flags")
    void airJump() {
        for (int i = 0; i < 4; i++) {
            List<Double> first = jump(0.42);
            for (int t = 0; t < 6; t++) {
                tick(0.1, first.get(t), false);
            }
            List<Double> second = jump(0.42);
            for (int t = 0; t < second.size(); t++) {
                boolean last = t == second.size() - 1;
                tick(0.1, last ? second.get(t) - 1.2519 : second.get(t), last);
            }
            tick(0.1, 0.0, true);
        }
        assertTrue(check.violationCount() > 0);
    }

    @Test
    @DisplayName("a slime block under the start is not judged")
    void slime() {
        world().setBlock(new BlockPos(0, 63, 0), VanillaBlocks.SLIME_BLOCK);
        for (int i = 0; i < 4; i++) {
            x = 0.5;
            List<Double> deltas = jump(0.6);
            for (int t = 0; t < deltas.size(); t++) {
                tick(0.0, deltas.get(t), t == deltas.size() - 1);
            }
            tick(0.0, 0.0, true);
        }
        assertEquals(0, check.violationCount());
    }

    @Test
    @DisplayName("creative players (flight allowed) are not judged")
    void flying() {
        player.abilities().applyGameMode(com.github.retrooper.packetevents.protocol.player.GameMode.CREATIVE);
        for (int i = 0; i < 4; i++) {
            doJump(0.6);
        }
        assertEquals(0, check.violationCount());
    }
}
