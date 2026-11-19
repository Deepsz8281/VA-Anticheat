package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.potion.PotionTypes;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.EffectState;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.FakePacketEvents;
import dev.vaplus.anticheat.testutil.RecordingTransport;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EffectTracker (ENTITY_EFFECT / REMOVE_ENTITY_EFFECT of the own entity)")
class EffectTrackerTest {

    private PlayerData player;
    private RecordingTransport transport;
    private EffectTracker tracker;
    private SimulationModule module;
    private int acknowledged;

    @BeforeEach
    void setUp() {
        module = new SimulationModule();
        Harness harness = TestContexts.builder().module(module).build();
        player = harness.newPlayer();
        transport = harness.lastTransport();
        tracker = (EffectTracker) harness.context.setup().outboundTrackers().stream()
                .filter(t -> t instanceof EffectTracker).findFirst().orElseThrow();
    }

    private void pongNext() {
        player.acks().onPong(transport.markerIds().get(acknowledged++));
    }

    private boolean apply(TrackedEffect effect, int amplifier) {
        List<Runnable> afterSend = new ArrayList<>();
        boolean changed = tracker.onEffect(player, afterSend, effect, amplifier);
        afterSend.forEach(Runnable::run);
        return changed;
    }

    private boolean remove(TrackedEffect effect) {
        List<Runnable> afterSend = new ArrayList<>();
        boolean changed = tracker.onRemove(player, afterSend, effect);
        afterSend.forEach(Runnable::run);
        return changed;
    }

    @Test
    @DisplayName("bracketed apply: absent before the first ack, pending in the window, active after the second")
    void applyBracket() {
        EffectState effects = player.effects();
        assertTrue(apply(TrackedEffect.SPEED, 1));
        assertFalse(effects.has(TrackedEffect.SPEED));
        assertFalse(effects.pending(TrackedEffect.SPEED));
        pongNext();
        assertTrue(effects.pending(TrackedEffect.SPEED));
        assertFalse(effects.has(TrackedEffect.SPEED));
        pongNext();
        assertFalse(effects.anyPending());
        assertEquals(1, effects.amplifier(TrackedEffect.SPEED));
    }

    @Test
    @DisplayName("bracketed removal; re-sends with the same amplifier (beacon refresh) open no window")
    void refreshAndRemove() {
        EffectState effects = player.effects();
        apply(TrackedEffect.JUMP_BOOST, 0);
        pongNext();
        pongNext();
        assertFalse(apply(TrackedEffect.JUMP_BOOST, 0), "same amplifier: no window");
        pongNext();
        assertFalse(effects.pending(TrackedEffect.JUMP_BOOST));
        pongNext();
        assertTrue(apply(TrackedEffect.JUMP_BOOST, 2), "amplifier change");
        pongNext();
        pongNext();
        assertEquals(2, effects.amplifier(TrackedEffect.JUMP_BOOST));

        assertTrue(remove(TrackedEffect.JUMP_BOOST));
        pongNext();
        assertTrue(effects.pending(TrackedEffect.JUMP_BOOST));
        assertTrue(effects.has(TrackedEffect.JUMP_BOOST), "still active in the window");
        pongNext();
        assertFalse(effects.has(TrackedEffect.JUMP_BOOST));
        assertFalse(remove(TrackedEffect.JUMP_BOOST), "removing an effect that was not sent: no window");
    }

    @Test
    @DisplayName("negative amplifiers are clamped like EffectState.apply; join clears effects and the sent view")
    void joinAndClamp() {
        apply(TrackedEffect.SLOWNESS, -3);
        pongNext();
        pongNext();
        assertEquals(0, player.effects().amplifier(TrackedEffect.SLOWNESS));
        tracker.onJoin(player);
        assertFalse(player.effects().has(TrackedEffect.SLOWNESS));
        assertEquals(-1, player.component(module.sentEffectsKey()).amplifier(TrackedEffect.SLOWNESS));
    }

    @Test
    @DisplayName("effects map by registry name; untracked effects are ignored")
    void names() {
        assertEquals(TrackedEffect.JUMP_BOOST, EffectTracker.effectOf("minecraft:jump_boost"));
        assertEquals(TrackedEffect.SLOW_FALLING, EffectTracker.effectOf("slow_falling"));
        assertEquals(TrackedEffect.DOLPHINS_GRACE, EffectTracker.effectOf("minecraft:dolphins_grace"));
        assertNull(EffectTracker.effectOf("minecraft:regeneration"));
        assertNull(EffectTracker.effectOf("othermod:speed"));
        assertNull(EffectTracker.effectOf((String) null));
        FakePacketEvents.install(ServerVersion.V_1_21_4);
        try {
            assertEquals(TrackedEffect.SPEED, EffectTracker.effectOf(PotionTypes.SPEED));
            assertEquals(TrackedEffect.LEVITATION, EffectTracker.effectOf(PotionTypes.LEVITATION));
            assertEquals(TrackedEffect.DARKNESS, EffectTracker.effectOf(PotionTypes.DARKNESS));
            assertNull(EffectTracker.effectOf(PotionTypes.REGENERATION));
        } finally {
            FakePacketEvents.uninstall();
        }
    }
}
