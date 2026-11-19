package dev.vaplus.anticheat.testutil.replay.scenarios;

import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.pipeline.CoreTrackingModule;
import dev.vaplus.anticheat.pipeline.MotionTrackingModule;
import dev.vaplus.anticheat.pipeline.MovementProcessor;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.simulation.SimulationResult;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.testutil.replay.ReplayScenario;
import dev.vaplus.anticheat.world.mirror.BlockKey;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.ArrayList;
import java.util.List;

/**
 * Replay driver for check regression tests. Same wiring and replay path as {@link ReplayScenario}
 * (core tracking → motion tracking → simulation, packets fed as primitives through
 * {@link MovementProcessor#process}) plus a check module installed after the simulation, so the
 * checks run inside the real pipeline ({@code CheckBundle.dispatchPosition} after the simulator).
 *
 * <p>Differences to {@link ReplayScenario} (which cannot host extra modules and always sends
 * {@code onGround = false}): the client ground flag is part of every {@link Frame}, and every
 * frame's {@link SimulationResult} status is recorded for assertions.
 */
public final class CheckReplay {

    public final Harness harness;
    public final PlayerData player;
    public final SimulationModule simulation;
    public final MovementProcessor processor;
    private final List<SimulationResult.Status> statuses = new ArrayList<>();
    private double x;
    private double y;
    private double z;
    private float yaw;

    private CheckReplay(AnticheatModule checks) {
        simulation = new SimulationModule();
        harness = TestContexts.builder()
                .module(new CoreTrackingModule(false))
                .module(new MotionTrackingModule())
                .module(simulation)
                .module(checks)
                .build();
        player = harness.newPlayer();
        processor = new MovementProcessor(harness.context.setup());
    }

    public static CheckReplay create(AnticheatModule checks) {
        return new CheckReplay(checks);
    }

    public WorldMirror world() {
        return player.component(simulation.worldMirrorKey());
    }

    /** Same layout as {@link ReplayScenario#platform}: floor layer plus 16 layers of known air. */
    public CheckReplay platform(BlockKey block, int floorY, int radius) {
        fill(block, -radius, radius, floorY, floorY, -radius, radius);
        fill(VanillaBlocks.AIR, -radius, radius, floorY + 1, floorY + 16, -radius, radius);
        return this;
    }

    public CheckReplay fill(BlockKey block, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        WorldMirror world = world();
        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    world.setBlock(new BlockPos(bx, by, bz), block);
                }
            }
        }
        return this;
    }

    /** First position after join (SKIPPED first-position). */
    public CheckReplay spawn(double x, double y, double z, float yaw, boolean onGround) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        send(onGround);
        return this;
    }

    /** One idle tick in place; seeds the engine (SKIPPED no-motion-seed). */
    public CheckReplay settle(boolean onGround) {
        return play(new Frame(0.0, 0.0, 0.0, onGround));
    }

    public CheckReplay play(Frame frame) {
        x += frame.dx();
        y += frame.dy();
        z += frame.dz();
        send(frame.onGround());
        return this;
    }

    public CheckReplay play(List<Frame> frames) {
        for (Frame frame : frames) {
            play(frame);
        }
        return this;
    }

    public <T extends Check> T check(Class<T> type) {
        return player.checks().get(type);
    }

    /** Simulation status of every packet sent so far, including spawn and settle. */
    public List<SimulationResult.Status> statuses() {
        return List.copyOf(statuses);
    }

    public long completeCount() {
        return statuses.stream().filter(status -> status == SimulationResult.Status.COMPLETE).count();
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    private void send(boolean onGround) {
        processor.process(player, true, x, y, z, true, yaw, 0f, onGround, false);
        statuses.add(player.simulation().status());
    }
}
