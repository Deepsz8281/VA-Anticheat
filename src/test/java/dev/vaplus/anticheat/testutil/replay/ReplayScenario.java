package dev.vaplus.anticheat.testutil.replay;

import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.pipeline.CoreTrackingModule;
import dev.vaplus.anticheat.pipeline.MotionTrackingModule;
import dev.vaplus.anticheat.pipeline.MovementProcessor;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.simulation.PredictionState;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.simulation.SimulationResult;
import dev.vaplus.anticheat.testutil.RecordingTransport;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.world.mirror.BlockKey;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import dev.vaplus.anticheat.world.mirror.ColumnStorage;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.ArrayList;
import java.util.List;

/**
 * Replay harness: a fully wired context (core tracking, motion tracking, simulation module) and one
 * player whose movement packets are fed through {@link MovementProcessor#process} as primitives,
 * exactly like recorded movement would be — no server, no PacketEvents events.
 *
 * <pre>{@code
 * ReplayScenario s = ReplayScenario.create().platform(VanillaBlocks.STONE, 63, 4);
 * s.spawn(0.5, 64, 0.5, 0f);   // first position (SKIPPED first-position)
 * s.settle();                  // one idle tick seeds the engine (SKIPPED no-motion-seed)
 * SimulationResult r = s.move(0, 0, 0.098);
 * }</pre>
 * Coordinates are absolute; {@link #move} takes deltas and keeps the yaw. The client-declared
 * on-ground flag is passed through but the engine derives ground contact from the world mirror.
 */
public final class ReplayScenario {

    public final Harness harness;
    public final PlayerData player;
    public final SimulationModule simulation;
    public final MovementProcessor processor;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private int acknowledgedMarkers;
    private final RecordingTransport transport;

    private ReplayScenario() {
        this(new SimulationModule());
    }

    private ReplayScenario(SimulationModule simulation) {
        this(simulation, null);
    }

    private ReplayScenario(SimulationModule simulation,
                           java.util.function.Function<SimulationModule, AnticheatModule> extra) {
        this.simulation = simulation;
        TestContexts.Builder builder = TestContexts.builder()
                .module(new CoreTrackingModule(false))
                .module(new MotionTrackingModule())
                .module(simulation);
        if (extra != null) {
            builder.module(extra.apply(simulation));
        }
        harness = builder.build();
        player = harness.newPlayer();
        transport = harness.lastTransport();
        processor = new MovementProcessor(harness.context.setup());
    }

    public static ReplayScenario create() {
        return new ReplayScenario();
    }

    /** Scenario with one extra module (e.g. checks) built from the scenario's simulation module. */
    public static ReplayScenario withModule(java.util.function.Function<SimulationModule, AnticheatModule> extra) {
        return new ReplayScenario(new SimulationModule(), extra);
    }

    /**
     * Scenario whose world mirror resolves dense column state ids with {@code blockStates}
     * (world-feeder replays: {@link #loadChunk}).
     */
    public static ReplayScenario create(BlockStateResolver blockStates) {
        return new ReplayScenario(new SimulationModule(blockStates));
    }

    /**
     * Replays a CHUNK_DATA packet through the world feeder ({@code WorldTracker#onChunkData}) and
     * runs its after-send tasks. The chunk stays in its uncertainty window ({@code world-pending})
     * until {@link #acknowledgeMarkers()}.
     */
    public ReplayScenario loadChunk(int chunkX, int chunkZ, ColumnStorage column) {
        List<Runnable> afterSend = new ArrayList<>();
        simulation.worldTracker().onChunkData(player, afterSend, chunkX, chunkZ, column);
        afterSend.forEach(Runnable::run);
        return this;
    }

    /**
     * Sends one marker (as the heartbeat would) and lets the client acknowledge every marker sent so
     * far, in order.
     */
    public ReplayScenario acknowledgeMarkers() {
        player.acks().sendMarker();
        List<Integer> sent = transport.markerIds();
        while (acknowledgedMarkers < sent.size()) {
            player.acks().onPong(sent.get(acknowledgedMarkers++));
        }
        return this;
    }

    public WorldMirror world() {
        return player.component(simulation.worldMirrorKey());
    }

    public VelocityState velocity() {
        return player.component(simulation.velocityKey());
    }

    public PredictionState prediction() {
        return player.component(simulation.predictionKey());
    }

    public SimulationResult result() {
        return player.simulation();
    }

    /**
     * Square platform of {@code block} at layer {@code floorY} over {@code [-radius, radius]} in x
     * and z, with known air from {@code floorY + 1} up to {@code floorY + 16}.
     */
    public ReplayScenario platform(BlockKey block, int floorY, int radius) {
        fill(block, -radius, radius, floorY, floorY, -radius, radius);
        fill(VanillaBlocks.AIR, -radius, radius, floorY + 1, floorY + 16, -radius, radius);
        return this;
    }

    /** Sets every block of the inclusive box. */
    public ReplayScenario fill(BlockKey block, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
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

    /** First position + rotation after join: always SKIPPED (first-position). */
    public SimulationResult spawn(double x, double y, double z, float yaw) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = 0f;
        processor.process(player, true, x, y, z, true, yaw, pitch, false, false);
        return result();
    }

    /** One idle tick in place; gives the engine its first motion seed. */
    public SimulationResult settle() {
        return move(0.0, 0.0, 0.0);
    }

    /**
     * Switches to 1.21.2+ tick-end mode (call before {@link #spawn}): client ticks are then counted
     * from replayed tick-end packets and one position packet may cover several ticks.
     */
    public ReplayScenario tickEndMode() {
        player.enableTickEndMode();
        return this;
    }

    /** One position+rotation packet with an explicit client ground claim (legacy one-tick mode). */
    public SimulationResult move(double dx, double dy, double dz, boolean onGround) {
        x += dx;
        y += dy;
        z += dz;
        processor.process(player, true, x, y, z, true, yaw, pitch, onGround, false);
        return result();
    }

    /** One position+rotation packet covering one client tick. */
    public SimulationResult move(double dx, double dy, double dz) {
        if (player.tickEndMode()) {
            return moveTicks(1, dx, dy, dz);
        }
        x += dx;
        y += dy;
        z += dz;
        processor.process(player, true, x, y, z, true, yaw, pitch, false, false);
        return result();
    }

    /**
     * One position packet covering {@code ticks} client ticks (tick-end mode only): the tick-end
     * packets of the previous ticks are replayed as {@link PlayerData#onClientTick()} calls first.
     */
    public SimulationResult moveTicks(int ticks, double dx, double dy, double dz) {
        if (!player.tickEndMode()) {
            throw new IllegalStateException("call tickEndMode() before spawn()");
        }
        for (int i = 0; i < ticks; i++) {
            player.onClientTick();
        }
        x += dx;
        y += dy;
        z += dz;
        processor.process(player, true, x, y, z, true, yaw, pitch, false, false);
        return result();
    }

    public ReplayScenario yaw(float yaw) {
        this.yaw = yaw;
        return this;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }
}
