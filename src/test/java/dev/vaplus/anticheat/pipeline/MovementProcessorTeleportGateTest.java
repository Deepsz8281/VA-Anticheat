package dev.vaplus.anticheat.pipeline;

import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.movement.SetbackHandler.SetbackTarget;
import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.module.ModuleContext;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.MovementSimulator;
import dev.vaplus.anticheat.simulation.SimulationResult;
import dev.vaplus.anticheat.testutil.Descriptors;
import dev.vaplus.anticheat.testutil.ProbeChecks;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MovementProcessor teleport gate (H5)")
class MovementProcessorTeleportGateTest {

    private static final CheckDescriptor POS = Descriptors.movement("pos");
    private static final CheckDescriptor ROT = Descriptors.movement("rot");

    private final List<Object> simulated = new ArrayList<>();
    private final AtomicInteger hookCalls = new AtomicInteger();
    private Harness harness;
    private PlayerData player;
    private TeleportQueue queue;
    private ProbeChecks.Position position;
    private ProbeChecks.Rotation rotation;
    private MovementProcessor processor;

    @BeforeEach
    void setUp() {
        MovementSimulator recording = (p, update, out) -> {
            simulated.add(update.toX());
            out.complete(update.deltaX(), update.deltaY(), update.deltaZ(), 0, 0, 0, true, 1, 0, 0);
        };
        AnticheatModule hookModule = new AnticheatModule() {
            @Override
            public String name() {
                return "test-hook";
            }

            @Override
            public void register(ModuleContext context) {
                context.addMovementHook((p, update) -> hookCalls.incrementAndGet());
            }
        };
        harness = TestContexts.builder()
                .simulator(recording)
                .check(POS, ProbeChecks.factory(POS, ProbeChecks.Position::new))
                .check(ROT, ProbeChecks.factory(ROT, ProbeChecks.Rotation::new))
                .module(new CoreTrackingModule(false))
                .module(hookModule)
                .build();
        player = harness.newPlayer();
        queue = TeleportQueue.resolve(player);
        position = player.checks().get(ProbeChecks.Position.class);
        rotation = player.checks().get(ProbeChecks.Rotation.class);
        processor = new MovementProcessor(harness.context.setup());
    }

    private boolean pos(double x, double y, double z) {
        return processor.process(player, true, x, y, z, false, 0f, 0f, true, false);
    }

    private void pendingSync() {
        queue.register(42, new SetbackTarget(1, 64, 1, 0f, 0f), 0, 0, player.clientTicks(),
                TeleportQueue.SERVER_SYNC);
    }

    @Test
    @DisplayName("without a pending sync the pipeline behaves exactly as before")
    void noPending() {
        assertTrue(pos(1, 64, 1));
        assertTrue(pos(2, 64, 2));

        assertEquals(1, simulated.size());
        assertEquals(1, position.updates.size());
        assertEquals(2, hookCalls.get(), "hooks run for both position packets");
        assertEquals(SimulationResult.Status.COMPLETE, player.simulation().status());
    }

    @Test
    @DisplayName("a pending sync suppresses hooks, simulation, position and rotation checks")
    void gateBlocks() {
        pos(1, 64, 1);
        double safeX = player.movement().safeX();
        int hooksBefore = hookCalls.get();
        pendingSync();

        assertTrue(processor.process(player, true, 100, 64, 100, true, 90f, 10f, true, false));

        assertTrue(position.updates.isEmpty(), "no position check while awaiting the confirm");
        assertTrue(rotation.updates.isEmpty(), "no rotation check for the teleport's forced look");
        assertTrue(simulated.isEmpty(), "no simulation while awaiting the confirm");
        assertEquals(hooksBefore, hookCalls.get(), "the gate runs before the hooks");
        assertEquals(SimulationResult.Status.SKIPPED, player.simulation().status());
        assertEquals("awaiting-teleport", player.simulation().skipReason());
        assertEquals(safeX, player.movement().safeX(), "the unconfirmed position must not become safe");
        assertEquals(100.0, player.movement().x(), "the reported position is still tracked");
        assertTrue(player.movement().positionKnown());
    }

    @Test
    @DisplayName("a confirmation lifts the gate and the next movement is judged again")
    void confirmLiftsGate() {
        pos(1, 64, 1);
        pendingSync();
        pos(50, 64, 50);
        assertTrue(position.updates.isEmpty());

        assertEquals(TeleportQueue.ConfirmResult.CONFIRMED, queue.confirm(42));

        pos(51, 64, 50);
        assertEquals(1, simulated.size());
        assertEquals(1, position.updates.size());
        assertEquals(SimulationResult.Status.COMPLETE, position.updates.get(0).status());
    }

    @Test
    @DisplayName("after the tracker-confirmed teleport the destination counts as the first position")
    void confirmedTeleportIsFirstPosition() {
        pos(1, 64, 1);
        TeleportConfirmTracker tracker = (TeleportConfirmTracker) harness.context.setup().inboundTrackers().get(0);
        pendingSync();
        pos(50, 64, 50);

        tracker.onConfirm(player, 42);
        pos(51, 64, 50);

        assertTrue(position.updates.isEmpty(), "the teleport destination is not a movement delta");
        assertEquals(SimulationResult.Status.SKIPPED, player.simulation().status());
        assertEquals("first-position", player.simulation().skipReason());
        assertTrue(simulated.isEmpty());
    }

    @Test
    @DisplayName("an unconfirmed sync expires after MAX_AGE_TICKS and the gate opens again")
    void expiryOpensGate() {
        pos(1, 64, 1);
        pendingSync();
        for (int i = 0; i < TeleportQueue.MAX_AGE_TICKS; i++) {
            player.onClientTick();
        }
        pos(2, 64, 2);
        assertTrue(position.updates.isEmpty(), "still inside the confirmation window");

        player.onClientTick();
        pos(3, 64, 2);
        assertEquals(1, position.updates.size());
        assertEquals(1, queue.expired());
        assertFalse(queue.hasPending());
    }

    @Test
    @DisplayName("a processor built without the queue (legacy constructor) does not gate")
    void legacyConstructorDoesNotGate() {
        MovementProcessor legacy = new MovementProcessor(List.of());
        legacy.process(player, true, 1, 64, 1, false, 0f, 0f, true, false);
        pendingSync();
        legacy.process(player, true, 5, 64, 5, false, 0f, 0f, true, false);
        assertEquals(1, position.updates.size());
        assertEquals(1, simulated.size());
    }

    @Test
    @DisplayName("latency markers keep working while the gate holds")
    void markersUntouched() {
        pos(1, 64, 1);
        pendingSync();
        pos(5, 64, 5);

        player.scheduleHeartbeat();
        assertEquals(1, harness.lastTransport().markerIds().size());
        assertEquals(1, player.acks().pendingCount());
    }
}
