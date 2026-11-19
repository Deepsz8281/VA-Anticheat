package dev.vaplus.anticheat.check.impl.combat;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.impl.world.NearbyPlayers;
import dev.vaplus.anticheat.check.impl.world.VisibilityChecksModule;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("InteractCheck (self, cursor, multiple hit points)")
class InteractCheckTest {

    private static final int SELF = 5;
    private static final int TARGET = 21;

    private final TestContexts.Harness harness;
    private final PlayerData player;
    private final InteractCheck check;

    InteractCheckTest() {
        SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
        VisibilityChecksModule visibility = new VisibilityChecksModule(simulation);
        harness = TestContexts.builder()
                .module(simulation)
                .module(visibility)
                .check(InteractCheck.DESCRIPTOR, p -> new InteractCheck(p, visibility.nearbyKey()))
                .build();
        player = harness.newPlayer();
        player.enableTickEndMode();
        NearbyPlayers tracked = player.component(visibility.nearbyKey());
        tracked.spawn(TARGET, 0.5, 64.0, 3.5);
        check = player.checks().get(InteractCheck.class);
    }

    private boolean flagged(String prefix) {
        return harness.alerts.alerts().stream().anyMatch(a -> a.check() == check && a.details().startsWith(prefix));
    }

    @Test
    @DisplayName("descriptor: id interact, COMBAT")
    void descriptor() {
        assertEquals("interact", InteractCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.COMBAT, InteractCheck.DESCRIPTOR.category());
    }

    @Test
    @DisplayName("self: interacting with the own entity flags (not in spectator mode)")
    void self() {
        check.onSelfCheck(TARGET, SELF);
        player.abilities().applyGameMode(GameMode.SPECTATOR);
        check.onSelfCheck(SELF, SELF);
        assertEquals(0, check.violationCount());
        player.abilities().applyGameMode(GameMode.SURVIVAL);
        check.onSelfCheck(SELF, SELF);
        assertTrue(flagged("self"));
    }

    @Test
    @DisplayName("cursor: hit points inside the player box pass; outside or non-finite flag")
    void cursor() {
        check.onInteractAt(TARGET, 0.29f, 1.79f, -0.29f);
        player.onClientTick();
        check.onInteractAt(TARGET, 0f, 0f, 0f);
        player.onClientTick();
        assertEquals(0, check.violationCount(), harness.alerts.alerts()::toString);
        check.onInteractAt(TARGET, 0.6f, 1.0f, 0f);
        assertTrue(flagged("cursor 0.6"));
        player.onClientTick();
        check.onInteractAt(TARGET, Float.NaN, 1.0f, 0f);
        assertTrue(flagged("cursor non-finite"));
    }

    @Test
    @DisplayName("multi: the same hit point twice in a tick passes; two different ones flag")
    void multi() {
        check.onInteractAt(TARGET, 0.1f, 1.0f, 0.1f);
        check.onInteractAt(TARGET, 0.1f, 1.0f, 0.1f);
        player.onClientTick();
        assertEquals(0, check.violationCount());
        check.onInteractAt(TARGET, 0.1f, 1.0f, 0.1f);
        check.onInteractAt(TARGET, 0.2f, 1.2f, 0.1f);
        assertTrue(flagged("multi"));
    }
}
