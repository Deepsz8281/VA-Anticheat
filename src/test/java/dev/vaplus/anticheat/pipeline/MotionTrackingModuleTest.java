package dev.vaplus.anticheat.pipeline;

import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.module.ModuleSetup;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.MotionHistory;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MotionTrackingModule (per-client-tick motion snapshots)")
class MotionTrackingModuleTest {

    private static Harness harnessWith(MotionTrackingModule module) {
        return TestContexts.builder().module(module).build();
    }

    @Test
    @DisplayName("register wires the component and exactly one tick listener into the setup")
    void wiring() {
        MotionTrackingModule module = new MotionTrackingModule();
        Harness harness = harnessWith(module);

        ModuleSetup setup = harness.context.setup();
        assertEquals(1, setup.components().size());
        ComponentKey<?> key = setup.components().get(0);
        assertEquals("motion-history", key.name());
        assertEquals(MotionHistory.class, key.type());
        assertEquals(1, setup.tickListeners().size());
        assertSame(key, module.historyKey());
    }

    @Test
    @DisplayName("historyKey() fails clearly before registration")
    void unregisteredKey() {
        MotionTrackingModule module = new MotionTrackingModule();
        assertThrows(IllegalStateException.class, module::historyKey);
    }

    @Test
    @DisplayName("each player gets its own MotionHistory, created before its checks")
    void perPlayerComponents() {
        MotionTrackingModule module = new MotionTrackingModule();
        Harness harness = harnessWith(module);
        PlayerData one = harness.newPlayer(UUID.randomUUID(), "A");
        PlayerData two = harness.newPlayer(UUID.randomUUID(), "BB");

        MotionHistory historyOne = one.component(module.historyKey());
        MotionHistory historyTwo = two.component(module.historyKey());
        assertNotSame(historyOne, historyTwo);
        assertEquals(0, historyOne.size());
        assertEquals(0, historyTwo.size());
    }

    @Test
    @DisplayName("end to end: N client ticks push N snapshots with the right ticks")
    void oneSnapshotPerTick() {
        MotionTrackingModule module = new MotionTrackingModule();
        Harness harness = harnessWith(module);
        PlayerData player = harness.newPlayer();
        MotionHistory history = player.component(module.historyKey());

        for (int i = 0; i < 5; i++) {
            player.onClientTick();
        }

        assertEquals(5, history.size());
        assertEquals(5, player.clientTicks());
        for (long tick = 1; tick <= 5; tick++) {
            assertTrue(history.isPresent(tick), "tick " + tick);
        }
        assertFalse(history.isPresent(0));
        assertFalse(history.isPresent(6));

        List<MotionHistory.TickSnapshot> seen = new ArrayList<>();
        history.forEachReversed(seen::add);
        for (int i = 0; i < 5; i++) {
            assertEquals(5 - i, seen.get(i).clientTick(), "newest first");
        }
    }

    @Test
    @DisplayName("snapshots carry the movement and pose state of their tick")
    void snapshotFields() {
        MotionTrackingModule module = new MotionTrackingModule();
        Harness harness = harnessWith(module);
        PlayerData player = harness.newPlayer();
        MotionHistory history = player.component(module.historyKey());

        // Tick 1: no position known yet -> zero delta, flags false.
        player.onClientTick();
        MotionHistory.TickSnapshot first = newest(history);
        assertEquals(1, first.clientTick());
        assertEquals(0.0, first.dx());
        assertEquals(0.0, first.dy());
        assertEquals(0.0, first.dz());
        assertFalse(first.onGround());
        assertFalse(first.sprinting());
        assertFalse(first.sneaking());
        assertFalse(first.jumping());
        assertFalse(first.gliding());
        assertEquals(0, first.worldEpoch());

        // Tick 2: a position packet and flags arrive before the tick listener runs.
        player.movement().applyPosition(10.5, 70.0, 3.25);
        player.movement().applyFlags(true, false);
        player.pose().sprinting(true);
        player.pose().sneaking(true);
        // Raw input keys (jump = true); jumping() in the snapshot requires inputKnown.
        player.pose().applyInput(true, false, false, false, true, false, false, 2);
        player.onClientTick();
        MotionHistory.TickSnapshot second = newest(history);
        assertEquals(2, second.clientTick());
        assertEquals(10.5, second.dx());
        assertEquals(70.0, second.dy());
        assertEquals(3.25, second.dz());
        assertTrue(second.onGround());
        assertTrue(second.sprinting());
        assertTrue(second.sneaking());
        assertTrue(second.jumping());
        assertFalse(second.gliding());
    }

    @Test
    @DisplayName("jumping stays false until a player-input packet was seen")
    void jumpingNeedsInput() {
        MotionTrackingModule module = new MotionTrackingModule();
        Harness harness = harnessWith(module);
        PlayerData player = harness.newPlayer();
        player.onClientTick();
        assertFalse(newest(player.component(module.historyKey())).jumping(),
                "no player-input packet yet (pre-1.21.2 client) must not count as jumping");

        player.pose().applyInput(true, false, false, false, true, false, false, player.clientTicks());
        player.onClientTick();
        assertTrue(newest(player.component(module.historyKey())).jumping());
    }
    @Test
    @DisplayName("gliding and the world epoch are recorded; the ring overwrites beyond 32 ticks")
    void glidingEpochAndRingEndToEnd() {
        MotionTrackingModule module = new MotionTrackingModule();
        Harness harness = harnessWith(module);
        PlayerData player = harness.newPlayer();
        MotionHistory history = player.component(module.historyKey());

        player.pose().gliding(true);
        player.movement().advanceWorldEpoch(); // server side, e.g. respawn packet sent
        int lastTick = MotionHistory.CAPACITY + 8;
        for (int i = 0; i < lastTick; i++) {
            player.onClientTick();
        }

        assertEquals(MotionHistory.CAPACITY, history.size());
        MotionHistory.TickSnapshot newest = newest(history);
        assertEquals(lastTick, newest.clientTick());
        assertTrue(newest.gliding());
        assertEquals(1, newest.worldEpoch());
        assertFalse(history.isPresent(8), "oldest 8 entries were overwritten");
        assertTrue(history.isPresent(9));
    }

    @Test
    @DisplayName("the package-level snapshot helper agrees with what the listener pushed")
    void snapshotHelperMatchesListener() {
        MotionTrackingModule module = new MotionTrackingModule();
        Harness harness = harnessWith(module);
        PlayerData player = harness.newPlayer();
        player.movement().applyPosition(4.0, 64.0, -2.0);
        player.onClientTick();

        MotionHistory.TickSnapshot pushed = newest(player.component(module.historyKey()));
        MotionHistory.TickSnapshot direct = MotionTrackingModule.snapshot(player);
        assertEquals(pushed.clientTick(), direct.clientTick());
        assertEquals(pushed.dx(), direct.dx());
        assertEquals(pushed.dy(), direct.dy());
        assertEquals(pushed.dz(), direct.dz());
        assertEquals(pushed.onGround(), direct.onGround());
    }

    /** The newest snapshot (the one the last tick pushed). */
    private static MotionHistory.TickSnapshot newest(MotionHistory history) {
        List<MotionHistory.TickSnapshot> seen = new ArrayList<>();
        history.forEachReversed(seen::add);
        assertFalse(seen.isEmpty());
        return seen.get(0);
    }
}
