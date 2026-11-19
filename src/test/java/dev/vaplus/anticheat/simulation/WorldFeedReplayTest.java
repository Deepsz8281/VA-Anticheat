package dev.vaplus.anticheat.simulation;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import dev.vaplus.anticheat.pipeline.WorldBlockStates;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.testutil.FakePacketEvents;
import dev.vaplus.anticheat.testutil.replay.ReplayScenario;
import dev.vaplus.anticheat.world.mirror.ArrayColumnStorage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.vaplus.anticheat.testutil.replay.VanillaMotion.carry;
import static dev.vaplus.anticheat.testutil.replay.VanillaMotion.groundAccel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end: the world comes in through the feeder (CHUNK_DATA-equivalent dense columns with real
 * PacketEvents 1.21.4 global ids, resolved by {@link WorldBlockStates}), is acknowledged through
 * markers, and the engine then simulates against it.
 */
@DisplayName("World feeder -> engine (replayed chunk packets, acks, block updates)")
class WorldFeedReplayTest {

    private static final double EPS = 1.0E-6;
    private static final double WALK = groundAccel(0.1, 0.98, 1.0, 0.6);
    private static final ClientVersion VERSION = ClientVersion.V_1_21_4;
    private static int air;
    private static int stone;
    private static int dirt;

    @BeforeAll
    static void installApi() {
        FakePacketEvents.install(ServerVersion.V_1_21_4);
        air = WrappedBlockState.getByString(VERSION, "minecraft:air", false).getGlobalId();
        stone = WrappedBlockState.getByString(VERSION, "minecraft:stone", false).getGlobalId();
        dirt = WrappedBlockState.getByString(VERSION, "minecraft:dirt", false).getGlobalId();
    }

    @AfterAll
    static void uninstallApi() {
        FakePacketEvents.uninstall();
    }

    /** Stone floor at y = 63, air from 64 to 80, everything else never sent (unknown). */
    private ArrayColumnStorage column() {
        return new ArrayColumnStorage(-64, 384).fillLayers(63, 63, stone).fillLayers(64, 80, air);
    }

    /** Chunks (-1..0, -1..0) around the origin, sent through the feeder. */
    private ReplayScenario fed() {
        ReplayScenario scenario = ReplayScenario.create(WorldBlockStates.forVersion(VERSION));
        for (int cx = -1; cx <= 0; cx++) {
            for (int cz = -1; cz <= 0; cz++) {
                scenario.loadChunk(cx, cz, column());
            }
        }
        return scenario;
    }

    private static void assertExplained(SimulationResult result) {
        assertEquals(SimulationResult.Status.COMPLETE, result.status(), () -> "skip reason: " + result.skipReason());
        assertTrue(result.offset() < EPS, () -> "offset " + result.offset());
    }

    /** One block update through the feeder, including its after-send task. */
    private static void blockChange(ReplayScenario scenario, int x, int y, int z, int stateId) {
        List<Runnable> afterSend = new ArrayList<>();
        if (scenario.simulation.worldTracker().onBlockChange(scenario.player, x, y, z, stateId)) {
            scenario.simulation.worldTracker().scheduleResolve(scenario.player, afterSend);
        }
        afterSend.forEach(Runnable::run);
    }

    @Test
    @DisplayName("chunk fill + ack, then movement: COMPLETE (not world-unknown)")
    void fedWorldCompletes() {
        ReplayScenario s = fed().acknowledgeMarkers();
        assertEquals(4, s.world().chunkCount());
        assertTrue(s.world().pending().any(), "windows settle lazily (engine/feeder), not on the ack itself");
        s.world().pending().settle(s.player.acks().lastAckedMarker());
        assertFalse(s.world().pending().any());
        s.spawn(0.5, 64.0, 0.5, 0f);
        s.settle();
        assertExplained(s.move(0, 0, 0));
        assertExplained(s.move(0, 0, WALK));
        assertExplained(s.move(0, 0, carry(WALK, 0.6) + WALK));
        assertTrue(s.prediction().completed() >= 3);
    }

    @Test
    @DisplayName("without the feeder the same movement is world-unknown")
    void unfedWorldIsUnknown() {
        ReplayScenario s = ReplayScenario.create(WorldBlockStates.forVersion(VERSION));
        s.spawn(0.5, 64.0, 0.5, 0f);
        s.settle();
        assertEquals(PredictionSimulator.SKIP_WORLD_UNKNOWN, s.move(0, 0, 0).skipReason());
    }

    @Test
    @DisplayName("before the client acknowledged the chunks the engine skips with world-pending")
    void pendingChunks() {
        ReplayScenario s = fed();
        s.spawn(0.5, 64.0, 0.5, 0f);
        s.settle();
        assertEquals(PredictionSimulator.SKIP_WORLD_PENDING, s.move(0, 0, 0).skipReason());
        s.acknowledgeMarkers();
        assertExplained(s.move(0, 0, 0));
    }

    @Test
    @DisplayName("a block update in the scanned region is pending until acknowledged, then used")
    void blockUpdateWindow() {
        ReplayScenario s = fed().acknowledgeMarkers();
        s.spawn(0.5, 64.0, 0.5, 0f);
        s.settle();
        assertExplained(s.move(0, 0, 0));

        blockChange(s, 30, 63, 30, dirt);
        assertFalse(s.world().pending().any(), "outside the mirrored chunks: ignored like the client does");
        blockChange(s, 10, 63, 10, dirt);
        assertExplained(s.move(0, 0, 0));

        blockChange(s, 0, 63, 0, dirt);
        assertEquals(PredictionSimulator.SKIP_WORLD_PENDING, s.move(0, 0, 0).skipReason());
        s.acknowledgeMarkers();
        assertExplained(s.move(0, 0, 0));
        assertEquals("minecraft:dirt", s.world().getBlock(0, 63, 0).key().value());
    }

    @Test
    @DisplayName("an id PacketEvents does not know under the player stays unknown (never air)")
    void unknownIdUnderPlayer() {
        ReplayScenario s = fed().acknowledgeMarkers();
        s.spawn(0.5, 64.0, 0.5, 0f);
        s.settle();
        blockChange(s, 0, 63, 0, 999_999);
        s.acknowledgeMarkers();
        assertFalse(s.world().getBlock(0, 63, 0).known());
        assertEquals(PredictionSimulator.SKIP_WORLD_UNKNOWN, s.move(0, 0, 0).skipReason());
    }

    @Test
    @DisplayName("unload and world reset: unknown at once / pending, then unknown")
    void unloadAndReset() {
        ReplayScenario s = fed().acknowledgeMarkers();
        s.spawn(0.5, 64.0, 0.5, 0f);
        s.settle();
        s.simulation.worldTracker().onUnloadChunk(s.player, -1, 0);
        assertEquals(PredictionSimulator.SKIP_WORLD_UNKNOWN, s.move(0, 0, 0).skipReason());

        List<Runnable> afterSend = new ArrayList<>();
        s.simulation.worldTracker().onWorldReset(s.player, afterSend);
        afterSend.forEach(Runnable::run);
        assertEquals(PredictionSimulator.SKIP_WORLD_PENDING, s.move(0, 0, 0).skipReason());
        s.acknowledgeMarkers();
        assertEquals(PredictionSimulator.SKIP_WORLD_UNKNOWN, s.move(0, 0, 0).skipReason());
    }

    @Test
    @DisplayName("attribute and movement-effect windows make the engine skip; other effects do not")
    void attributeAndEffectWindows() {
        ReplayScenario s = fed().acknowledgeMarkers();
        s.spawn(0.5, 64.0, 0.5, 0f);
        s.settle();
        assertExplained(s.move(0, 0, 0));

        s.player.attributes().beginPending();
        assertEquals(PredictionSimulator.SKIP_ATTRIBUTES_PENDING, s.move(0, 0, 0).skipReason());
        s.player.attributes().endPending();

        s.player.effects().beginPending(TrackedEffect.JUMP_BOOST);
        assertEquals(PredictionSimulator.SKIP_EFFECTS_PENDING, s.move(0, 0, 0).skipReason());
        s.player.effects().endPending(TrackedEffect.JUMP_BOOST);

        s.player.effects().beginPending(TrackedEffect.HASTE);
        s.move(0, 0, 0);
        assertExplained(s.move(0, 0, 0));
        s.player.effects().endPending(TrackedEffect.HASTE);
    }
}
