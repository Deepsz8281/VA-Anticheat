package dev.vaplus.anticheat.pipeline;

import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.MovementState;
import dev.vaplus.anticheat.simulation.MovementSimulator;
import dev.vaplus.anticheat.simulation.SimulationResult;
import dev.vaplus.anticheat.testutil.Descriptors;
import dev.vaplus.anticheat.testutil.ProbeChecks;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MovementProcessor.process")
class MovementProcessorTest {

    private static final double EPS = 1e-12;
    private static final CheckDescriptor POS = Descriptors.movement("pos");
    private static final CheckDescriptor ROT = Descriptors.movement("rot");
    private static final CheckDescriptor TICK = Descriptors.movement("tick");

    private final List<MovementUpdate> simulated = new ArrayList<>();
    private final List<String> hookCalls = new ArrayList<>();
    private Harness harness;
    private PlayerData player;
    private ProbeChecks.Position position;
    private ProbeChecks.Rotation rotation;
    private ProbeChecks.Tick tick;
    private MovementProcessor processor;

    @BeforeEach
    void setUp() {
        MovementSimulator recording = (p, update, out) -> {
            simulated.add(update);
            out.complete(update.deltaX(), update.deltaY(), update.deltaZ(), 0, 0, 0, true, 1, 0, 0);
        };
        harness = TestContexts.builder()
                .simulator(recording)
                .check(POS, ProbeChecks.factory(POS, ProbeChecks.Position::new))
                .check(ROT, ProbeChecks.factory(ROT, ProbeChecks.Rotation::new))
                .check(TICK, ProbeChecks.factory(TICK, ProbeChecks.Tick::new))
                .build();
        player = harness.newPlayer();
        position = player.checks().get(ProbeChecks.Position.class);
        rotation = player.checks().get(ProbeChecks.Rotation.class);
        tick = player.checks().get(ProbeChecks.Tick.class);
        processor = new MovementProcessor(List.of());
    }

    private boolean pos(double x, double y, double z) {
        return processor.process(player, true, x, y, z, false, 0f, 0f, true, false);
    }

    private boolean posRot(double x, double y, double z, float yaw, float pitch) {
        return processor.process(player, true, x, y, z, true, yaw, pitch, true, false);
    }

    private boolean rot(float yaw, float pitch) {
        return processor.process(player, false, 0, 0, 0, true, yaw, pitch, true, false);
    }

    @Nested
    @DisplayName("position handling")
    class Positions {

        @Test
        @DisplayName("the first position is not dispatched and the simulation is SKIPPED")
        void firstPosition() {
            assertTrue(pos(1, 2, 3));

            assertTrue(position.updates.isEmpty(), "no position check on the first position");
            assertTrue(simulated.isEmpty(), "no simulation on the first position");
            assertEquals(SimulationResult.Status.SKIPPED, player.simulation().status());
            assertEquals("first-position", player.simulation().skipReason());
            assertTrue(player.movementUpdate().firstPosition());
            MovementState movement = player.movement();
            assertTrue(movement.positionKnown());
            assertEquals(1.0, movement.x());
            assertEquals(2.0, movement.y());
            assertEquals(3.0, movement.z());
            assertEquals(1, movement.positionPackets());
        }

        @Test
        @DisplayName("the second position is simulated and dispatched with correct deltas")
        void secondPosition() {
            pos(1, 2, 3);
            assertTrue(pos(2.5, 2.0, 7.0));

            assertEquals(1, position.updates.size());
            ProbeChecks.PositionSnapshot update = position.updates.get(0);
            assertEquals(1.0, update.fromX(), EPS);
            assertEquals(2.0, update.fromY(), EPS);
            assertEquals(3.0, update.fromZ(), EPS);
            assertEquals(2.5, update.toX(), EPS);
            assertEquals(7.0, update.toZ(), EPS);
            assertEquals(1.5, update.deltaX(), EPS);
            assertEquals(0.0, update.deltaY(), EPS);
            assertEquals(4.0, update.deltaZ(), EPS);
            assertFalse(update.firstPosition());
            assertFalse(update.teleport());
            assertTrue(update.positionChanged());
            assertEquals(1, update.elapsedTicks());
            assertEquals(1, simulated.size());
            assertSame(player.movementUpdate(), simulated.get(0));
            assertEquals(SimulationResult.Status.COMPLETE, update.status(),
                    "simulation ran before the checks, so checks see its result");
        }

        @Test
        @DisplayName("from-position of an update is the previous packet's position, over many packets")
        void chain() {
            pos(0, 0, 0);
            for (int i = 1; i <= 20; i++) {
                pos(i * 0.25, 0, -i);
            }
            assertEquals(20, position.updates.size());
            for (int i = 0; i < 20; i++) {
                ProbeChecks.PositionSnapshot update = position.updates.get(i);
                assertEquals(i * 0.25, update.fromX(), EPS);
                assertEquals(-i, update.fromZ(), EPS);
                assertEquals(0.25, update.deltaX(), EPS);
                assertEquals(-1.0, update.deltaZ(), EPS);
            }
        }

        @Test
        @DisplayName("without the InactiveSimulator the status is NOT_RUN for dispatched updates")
        void inactiveSimulatorStatus() {
            Harness inactive = TestContexts.builder().check(POS, ProbeChecks.factory(POS, ProbeChecks.Position::new)).build();
            PlayerData p = inactive.newPlayer();
            ProbeChecks.Position check = p.checks().get(ProbeChecks.Position.class);
            processor.process(p, true, 0, 0, 0, false, 0f, 0f, true, false);
            processor.process(p, true, 1, 0, 0, false, 0f, 0f, true, false);
            assertEquals(SimulationResult.Status.NOT_RUN, check.updates.get(0).status());
        }

        @Test
        @DisplayName("ground and collision flags are tracked with previous values")
        void flags() {
            processor.process(player, true, 0, 0, 0, false, 0f, 0f, true, false);
            processor.process(player, true, 0, 1, 0, false, 0f, 0f, false, true);
            MovementState movement = player.movement();
            assertFalse(movement.onGround());
            assertTrue(movement.lastOnGround());
            assertTrue(movement.horizontalCollision());
            MovementUpdate update = player.movementUpdate();
            assertTrue(update.fromGround());
            assertFalse(update.toGround());
            assertTrue(update.horizontalCollision());
        }

        @Test
        @DisplayName("a flags-only packet updates ground state but dispatches nothing and keeps the position")
        void flagsOnly() {
            pos(5, 6, 7);
            assertTrue(processor.process(player, false, 0, 0, 0, false, 0f, 0f, false, false));
            assertTrue(position.updates.isEmpty());
            assertTrue(rotation.updates.isEmpty());
            assertEquals(5.0, player.movement().x());
            assertFalse(player.movement().onGround());
            assertFalse(player.movementUpdate().positionChanged());
        }

        @Test
        @DisplayName("a position packet after a world change is treated as a first position again")
        void firstAfterWorldChange() {
            pos(1, 1, 1);
            pos(2, 1, 1);
            player.movement().advanceWorldEpoch();
            player.movement().invalidatePosition();
            assertEquals(1, player.movement().worldEpoch());
            assertFalse(player.movement().positionKnown());
            assertFalse(player.movement().safeKnown());
            pos(100, 50, 100);
            assertEquals(1, position.updates.size(), "teleport across worlds must not be judged as movement");
            assertTrue(player.movementUpdate().firstPosition());
        }
    }

    @Nested
    @DisplayName("rejection of malformed values")
    class Malformed {

        private void assertStateUntouched(double x, double y, double z, long ticks) {
            assertEquals(x, player.movement().x());
            assertEquals(y, player.movement().y());
            assertEquals(z, player.movement().z());
            assertEquals(ticks, player.clientTicks(), "rejected packets must not count as a client tick");
        }

        @Test
        @DisplayName("NaN and Infinity coordinates return false and leave all state unchanged")
        void nonFinitePosition() {
            pos(1, 2, 3);
            long ticks = player.clientTicks();
            long packets = player.movement().positionPackets();
            double[][] bad = {
                    {Double.NaN, 0, 0}, {0, Double.NaN, 0}, {0, 0, Double.NaN},
                    {Double.POSITIVE_INFINITY, 0, 0}, {0, Double.NEGATIVE_INFINITY, 0}, {0, 0, Double.POSITIVE_INFINITY}
            };
            for (double[] values : bad) {
                assertFalse(pos(values[0], values[1], values[2]));
            }
            assertStateUntouched(1, 2, 3, ticks);
            assertEquals(packets, player.movement().positionPackets());
            assertTrue(position.updates.isEmpty());
            assertTrue(simulated.isEmpty());
        }

        @Test
        @DisplayName("NaN and Infinity rotation return false and leave rotation unchanged")
        void nonFiniteRotation() {
            posRot(1, 2, 3, 10f, 5f);
            long ticks = player.clientTicks();
            assertFalse(rot(Float.NaN, 0f));
            assertFalse(rot(0f, Float.POSITIVE_INFINITY));
            assertFalse(posRot(9, 9, 9, Float.NEGATIVE_INFINITY, 0f));
            assertEquals(10f, player.movement().yaw());
            assertEquals(5f, player.movement().pitch());
            assertStateUntouched(1, 2, 3, ticks);
            assertTrue(rotation.updates.isEmpty());
        }

        @Test
        @DisplayName("non-finite values in a component the packet does not carry are ignored")
        void nonFiniteIgnoredWhenAbsent() {
            assertTrue(processor.process(player, false, Double.NaN, Double.NaN, Double.NaN, true, 1f, 2f, true, false));
            assertTrue(processor.process(player, true, 1, 2, 3, false, Float.NaN, Float.NaN, true, false));
            assertEquals(1.0, player.movement().x());
            assertEquals(1f, player.movement().yaw());
        }

        @Test
        @DisplayName("a rejected packet does not break later valid packets")
        void recoversAfterRejection() {
            pos(1, 0, 0);
            pos(Double.NaN, 0, 0);
            assertTrue(pos(2, 0, 0));
            assertEquals(1, position.updates.size());
            assertEquals(1.0, position.updates.get(0).deltaX(), EPS);
        }
    }

    @Nested
    @DisplayName("coordinate clamping")
    class Clamping {

        @Test
        @DisplayName("horizontal coordinates are clamped to +-3.0E7, vertical to +-2.0E7")
        void clamps() {
            pos(0, 0, 0);
            pos(5.0E7, 3.0E7, -5.0E7);
            assertEquals(3.0E7, player.movement().x());
            assertEquals(2.0E7, player.movement().y());
            assertEquals(-3.0E7, player.movement().z());
            pos(-5.0E7, -3.0E7, 5.0E7);
            assertEquals(-3.0E7, player.movement().x());
            assertEquals(-2.0E7, player.movement().y());
            assertEquals(3.0E7, player.movement().z());
        }

        @Test
        @DisplayName("the dispatched update carries the clamped target and a finite delta")
        void clampedDelta() {
            pos(0, 0, 0);
            pos(Double.MAX_VALUE, -Double.MAX_VALUE, Double.MAX_VALUE);
            ProbeChecks.PositionSnapshot update = position.updates.get(0);
            assertEquals(3.0E7, update.toX());
            assertEquals(-2.0E7, update.toY());
            assertEquals(3.0E7, update.toZ());
            assertTrue(Double.isFinite(update.deltaX()) && Double.isFinite(update.deltaY()) && Double.isFinite(update.deltaZ()));
        }

        @Test
        @DisplayName("values exactly at the limit and just inside are untouched")
        void limitsInclusive() {
            pos(0, 0, 0);
            pos(3.0E7, 2.0E7, -3.0E7);
            assertEquals(3.0E7, player.movement().x());
            assertEquals(2.0E7, player.movement().y());
            assertEquals(-3.0E7, player.movement().z());
            pos(29_999_999.5, -19_999_999.5, 0.25);
            assertEquals(29_999_999.5, player.movement().x());
            assertEquals(-19_999_999.5, player.movement().y());
            assertEquals(0.25, player.movement().z());
        }
    }

    @Nested
    @DisplayName("rotation handling")
    class Rotations {

        @Test
        @DisplayName("the first rotation is not dispatched, later ones are")
        void firstRotationSkipped() {
            assertTrue(rot(10f, 5f));
            assertTrue(rotation.updates.isEmpty());
            assertTrue(player.movement().rotationKnown());
            assertTrue(rot(25f, -3f));
            assertEquals(1, rotation.updates.size());
            ProbeChecks.RotationSnapshot update = rotation.updates.get(0);
            assertEquals(10f, update.fromYaw());
            assertEquals(5f, update.fromPitch());
            assertEquals(25f, update.toYaw());
            assertEquals(-3f, update.toPitch());
            assertFalse(update.teleport());
        }

        @Test
        @DisplayName("combined position+rotation: first packet skips both, second dispatches both")
        void combined() {
            posRot(1, 2, 3, 10f, 5f);
            assertTrue(position.updates.isEmpty());
            assertTrue(rotation.updates.isEmpty());
            posRot(2, 2, 3, 20f, 6f);
            assertEquals(1, position.updates.size());
            assertEquals(1, rotation.updates.size());
            assertEquals(10f, rotation.updates.get(0).fromYaw());
            assertTrue(position.updates.get(0).rotationChanged());
        }

        @Test
        @DisplayName("a rotation arriving after a position-only first packet is still its own first rotation")
        void rotationFirstAfterPosition() {
            pos(1, 2, 3);
            rot(40f, 0f);
            assertTrue(rotation.updates.isEmpty());
            rot(41f, 0f);
            assertEquals(1, rotation.updates.size());
        }

        @Test
        @DisplayName("rotation-only packets do not move the position or touch position checks")
        void rotationOnly() {
            pos(1, 2, 3);
            pos(2, 2, 3);
            int updates = position.updates.size();
            rot(1f, 1f);
            rot(2f, 2f);
            assertEquals(updates, position.updates.size());
            assertEquals(2.0, player.movement().x());
        }

        @Test
        @DisplayName("yaw is stored unwrapped, as sent by the client")
        void unwrappedYaw() {
            rot(350f, 0f);
            rot(370f, 0f);
            assertEquals(370f, player.movement().yaw());
            assertEquals(350f, rotation.updates.get(0).fromYaw());
            assertEquals(370f, rotation.updates.get(0).toYaw());
        }

        @Test
        @DisplayName("rotation update is flagged as teleport when a hook marks the position as teleport confirmation")
        void teleportRotation() {
            MovementProcessor withHook = new MovementProcessor(List.of((p, update) -> update.markTeleport()));
            withHook.process(player, true, 0, 0, 0, true, 0f, 0f, true, false);
            withHook.process(player, true, 5, 0, 0, true, 90f, 0f, true, false);
            assertEquals(1, rotation.updates.size());
            assertTrue(rotation.updates.get(0).teleport());
        }
    }

    @Nested
    @DisplayName("hooks and teleports")
    class Hooks {

        @Test
        @DisplayName("hooks run for every position packet (including the first) but not for rotation-only packets")
        void hookInvocation() {
            MovementProcessor withHook = new MovementProcessor(List.of(
                    (p, update) -> hookCalls.add("a:" + update.toX()),
                    (p, update) -> hookCalls.add("b:" + update.toX())));
            withHook.process(player, true, 1, 0, 0, false, 0f, 0f, true, false);
            withHook.process(player, false, 0, 0, 0, true, 5f, 5f, true, false);
            withHook.process(player, true, 2, 0, 0, false, 0f, 0f, true, false);
            assertEquals(List.of("a:1.0", "b:1.0", "a:2.0", "b:2.0"), hookCalls);
        }

        @Test
        @DisplayName("a teleport confirmation skips the simulation but still reaches position checks flagged as teleport")
        void teleportSkipsSimulation() {
            MovementProcessor withHook = new MovementProcessor(List.of((p, update) -> update.markTeleport()));
            withHook.process(player, true, 0, 0, 0, false, 0f, 0f, true, false);
            withHook.process(player, true, 100, 64, 100, false, 0f, 0f, true, false);
            assertTrue(simulated.isEmpty(), "no simulation of a teleport");
            assertEquals(1, position.updates.size());
            assertTrue(position.updates.get(0).teleport());
            assertEquals(SimulationResult.Status.SKIPPED, position.updates.get(0).status());
            assertEquals("teleport", position.updates.get(0).skipReason());
        }

        @Test
        @DisplayName("the teleport flag does not leak into the next packet")
        void teleportFlagResets() {
            boolean[] mark = {false, true, false};
            int[] call = {0};
            MovementProcessor withHook = new MovementProcessor(List.of((p, update) -> {
                if (mark[call[0]++]) {
                    update.markTeleport();
                }
            }));
            withHook.process(player, true, 0, 0, 0, false, 0f, 0f, true, false);
            withHook.process(player, true, 10, 0, 0, false, 0f, 0f, true, false);
            withHook.process(player, true, 10.1, 0, 0, false, 0f, 0f, true, false);
            assertTrue(position.updates.get(0).teleport());
            assertFalse(position.updates.get(1).teleport());
            assertEquals(1, simulated.size());
        }
    }

    @Nested
    @DisplayName("client tick accounting")
    class Ticks {

        @Test
        @DisplayName("without tick-end mode every accepted packet counts one client tick")
        void legacyTicks() {
            assertFalse(player.tickEndMode());
            pos(0, 0, 0);
            pos(1, 0, 0);
            rot(1f, 1f);
            processor.process(player, false, 0, 0, 0, false, 0f, 0f, true, false);
            assertEquals(4, player.clientTicks());
            assertEquals(4, tick.ticks);
            assertEquals(4, harness.profiler.report().clientTicks());
        }

        @Test
        @DisplayName("without tick-end mode elapsedTicks is always 1")
        void legacyElapsed() {
            pos(0, 0, 0);
            for (int i = 1; i <= 5; i++) {
                pos(i, 0, 0);
                rot(i, 0);
            }
            for (ProbeChecks.PositionSnapshot update : position.updates) {
                assertEquals(1, update.elapsedTicks());
            }
        }

        @Test
        @DisplayName("with tick-end mode the processor does not tick; elapsedTicks counts ticks since the last position")
        void tickEndElapsed() {
            player.enableTickEndMode();
            assertTrue(player.tickEndMode());
            pos(0, 0, 0);
            assertEquals(0, player.clientTicks(), "processing a packet must not tick in tick-end mode");
            assertEquals(0, tick.ticks);

            player.onClientTick();
            player.onClientTick();
            player.onClientTick();
            pos(1, 0, 0);
            assertEquals(3, position.updates.get(0).elapsedTicks());

            player.onClientTick();
            pos(2, 0, 0);
            assertEquals(1, position.updates.get(1).elapsedTicks());

            pos(3, 0, 0);
            assertEquals(1, position.updates.get(2).elapsedTicks(), "two positions in one tick are clamped to 1");
            assertEquals(4, player.clientTicks());
            assertEquals(4, tick.ticks);
        }

        @Test
        @DisplayName("rotation-only packets do not reset the tick counter used for elapsedTicks")
        void rotationDoesNotResetElapsed() {
            player.enableTickEndMode();
            pos(0, 0, 0);
            player.onClientTick();
            rot(1f, 1f);
            player.onClientTick();
            rot(2f, 2f);
            player.onClientTick();
            pos(1, 0, 0);
            assertEquals(3, position.updates.get(0).elapsedTicks());
        }

        @Test
        @DisplayName("enabling tick-end mode mid-session stops the per-packet ticks")
        void switchMode() {
            pos(0, 0, 0);
            pos(1, 0, 0);
            assertEquals(2, player.clientTicks());
            player.enableTickEndMode();
            pos(2, 0, 0);
            pos(3, 0, 0);
            assertEquals(2, player.clientTicks());
        }
    }

    @Nested
    @DisplayName("safe (setback) position")
    class SafePosition {

        @Test
        @DisplayName("the safe position follows accepted positions, including the first one")
        void followsPosition() {
            pos(1, 2, 3);
            assertTrue(player.movement().safeKnown());
            assertEquals(1.0, player.movement().safeX());
            pos(4, 5, 6);
            assertEquals(4.0, player.movement().safeX());
            assertEquals(5.0, player.movement().safeY());
            assertEquals(6.0, player.movement().safeZ());
        }

        @Test
        @DisplayName("a check that triggers a setback prevents the safe position from advancing; the next clean packet advances it")
        void notUpdatedWhenSetbackRequested() {
            pos(1, 0, 0);
            pos(2, 0, 0);
            assertEquals(2.0, player.movement().safeX());

            position.onUpdate = update -> {
                if (update.toX() > 50) {
                    position.flag("too far");
                }
            };
            pos(99, 0, 0);

            assertEquals(1, harness.setbacks.requests().size(), "default movement settings request a setback at VL 1");
            assertFalse(player.movement().setbackRequested(), "the request is consumed by finishChecks");
            assertEquals(2.0, player.movement().safeX(), "safe position must stay at the last good one");
            assertEquals(99.0, player.movement().x(), "the reported position is still tracked");

            pos(3, 0, 0);
            assertEquals(3.0, player.movement().safeX(), "the next clean packet advances the safe position again");
        }

        @Test
        @DisplayName("a setback requested before the position packet (tick or packet check) also blocks the safe position once")
        void requestBeforePositionBlocks() {
            pos(1, 0, 0);
            pos(2, 0, 0);
            harness.setbacks.request(player, position);
            pos(3, 0, 0);
            assertEquals(2.0, player.movement().safeX());
            pos(4, 0, 0);
            assertEquals(4.0, player.movement().safeX());
        }

        @Test
        @DisplayName("a setback requested during a rotation-only packet is carried over to the next position")
        void requestOnRotationCarriesOver() {
            pos(1, 0, 0);
            harness.setbacks.request(player, position);
            rot(5f, 5f);
            assertTrue(player.movement().setbackRequested());
            pos(2, 0, 0);
            assertEquals(1.0, player.movement().safeX());
            assertFalse(player.movement().setbackRequested());
        }

        @Test
        @DisplayName("a world change drops a pending setback request together with the safe position")
        void worldChangeClearsRequest() {
            pos(1, 0, 0);
            harness.setbacks.request(player, position);
            player.movement().advanceWorldEpoch();
            player.movement().invalidatePosition();
            pos(50, 0, 0);
            assertEquals(50.0, player.movement().safeX());
        }

        @Test
        @DisplayName("a violation from a non-setback-capable check does not block the safe position")
        void noSetbackNoBlock() {
            CheckDescriptor combat = Descriptors.combat("combatish");
            Harness h = TestContexts.builder().check(combat, ProbeChecks.factory(combat, ProbeChecks.Position::new)).build();
            PlayerData p = h.newPlayer();
            ProbeChecks.Position check = p.checks().get(ProbeChecks.Position.class);
            check.onUpdate = update -> check.flag("x");
            processor.process(p, true, 1, 0, 0, false, 0f, 0f, true, false);
            processor.process(p, true, 2, 0, 0, false, 0f, 0f, true, false);
            assertEquals(2.0, p.movement().safeX());
            assertTrue(h.setbacks.requests().isEmpty());
        }

        @Test
        @DisplayName("rotation-only packets leave the safe position alone")
        void rotationOnlyKeepsSafe() {
            pos(1, 0, 0);
            rot(10f, 0f);
            assertEquals(1.0, player.movement().safeX());
            assertNotEquals(0.0, player.movement().safeX());
        }

        @Test
        @DisplayName("invalidatePosition forgets the safe position until the next position arrives")
        void invalidate() {
            pos(1, 0, 0);
            player.movement().invalidatePosition();
            assertFalse(player.movement().safeKnown());
            pos(500, 0, 0);
            assertTrue(player.movement().safeKnown());
            assertEquals(500.0, player.movement().safeX());
        }
    }
}
