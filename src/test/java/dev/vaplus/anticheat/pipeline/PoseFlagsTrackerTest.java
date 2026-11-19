package dev.vaplus.anticheat.pipeline;

import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.PoseState;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PoseFlagsTracker (own gliding / swimming flags)")
class PoseFlagsTrackerTest {

    private static final class Fixture {
        final TestContexts.Harness harness;
        final PlayerData player;
        final PoseFlagsTracker tracker;

        Fixture() {
            SimulationModule simulation = new SimulationModule();
            harness = TestContexts.builder().module(simulation).build();
            player = harness.newPlayer();
            player.component(simulation.velocityKey()).selfEntityId(7);
            tracker = new PoseFlagsTracker(simulation.velocityKey());
        }

        void flags(int value) {
            List<Runnable> tasks = new ArrayList<>();
            tracker.onFlags(player, tasks, value);
            tasks.forEach(Runnable::run);
        }

        void acknowledge() {
            for (int id : harness.lastTransport().markerIds()) {
                player.acks().onPong(id);
            }
        }
    }

    @Test
    @DisplayName("the simulation module installs the tracker as outbound tracker and tick listener")
    void installed() {
        TestContexts.Harness harness = TestContexts.builder().module(new SimulationModule()).build();
        assertTrue(harness.context.setup().outboundTrackers().stream().anyMatch(t -> t instanceof PoseFlagsTracker));
    }

    @Test
    @DisplayName("gliding on applies at once; off only after the client acknowledged it, then the request clears")
    void glideOnOff() {
        Fixture f = new Fixture();
        PoseState pose = f.player.pose();
        pose.requestGlide();
        f.flags(PoseFlagsTracker.GLIDING_BIT);
        assertTrue(pose.gliding());
        f.flags(0);
        assertTrue(pose.gliding(), "still gliding until acknowledged");
        f.acknowledge();
        assertFalse(pose.gliding());
        assertFalse(pose.glideRequested(), "a finished flight no longer blocks the movement engine");
    }

    @Test
    @DisplayName("a glide request after the 'off' packet stays pending")
    void newerRequestKept() {
        Fixture f = new Fixture();
        PoseState pose = f.player.pose();
        f.flags(PoseFlagsTracker.GLIDING_BIT);
        f.flags(0);
        pose.requestGlide();
        f.acknowledge();
        assertFalse(pose.gliding());
        assertTrue(pose.glideRequested());
    }

    @Test
    @DisplayName("an unanswered glide request (no elytra) expires")
    void requestExpires() {
        Fixture f = new Fixture();
        PoseState pose = f.player.pose();
        pose.requestGlide();
        for (int i = 0; i < PoseFlagsTracker.GLIDE_REQUEST_TICKS; i++) {
            f.tracker.onClientTick(f.player);
        }
        assertTrue(pose.glideRequested());
        f.tracker.onClientTick(f.player);
        assertFalse(pose.glideRequested());
    }

    @Test
    @DisplayName("swimming flag follows the same rule")
    void swimming() {
        Fixture f = new Fixture();
        f.flags(PoseFlagsTracker.SWIMMING_BIT);
        assertTrue(f.player.pose().swimming());
        f.flags(0);
        assertTrue(f.player.pose().swimming());
        f.acknowledge();
        assertFalse(f.player.pose().swimming());
    }
}
