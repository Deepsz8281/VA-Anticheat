package dev.vaplus.anticheat.player.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EffectState (amplifiers, version stamp, uncertainty windows)")
class EffectStateTest {

    @Test
    @DisplayName("apply/remove/clear change amplifiers and advance the version")
    void amplifiersAndVersion() {
        EffectState effects = new EffectState();
        long version = effects.version();
        assertFalse(effects.has(TrackedEffect.SPEED));
        assertEquals(0, effects.level(TrackedEffect.SPEED));
        effects.apply(TrackedEffect.SPEED, 1);
        assertEquals(2, effects.level(TrackedEffect.SPEED));
        effects.remove(TrackedEffect.SPEED);
        effects.clear();
        assertEquals(version + 3, effects.version());
    }

    @Test
    @DisplayName("nested windows per effect; clear() does not touch the bracket bookkeeping")
    void pendingWindows() {
        EffectState effects = new EffectState();
        effects.beginPending(TrackedEffect.SPEED);
        effects.beginPending(TrackedEffect.SPEED);
        effects.beginPending(TrackedEffect.LEVITATION);
        assertEquals(3, effects.pendingCount());
        assertTrue(effects.pending(TrackedEffect.SPEED));
        assertFalse(effects.pending(TrackedEffect.SLOWNESS));
        effects.clear();
        assertTrue(effects.anyPending());
        effects.endPending(TrackedEffect.SPEED);
        assertTrue(effects.pending(TrackedEffect.SPEED));
        effects.endPending(TrackedEffect.SPEED);
        effects.endPending(TrackedEffect.SPEED);
        assertFalse(effects.pending(TrackedEffect.SPEED), "unbalanced ends never go negative");
        effects.endPending(TrackedEffect.LEVITATION);
        assertFalse(effects.anyPending());
        assertEquals(0, effects.pendingCount());
    }

    @Test
    @DisplayName("AttributeState windows nest and survive reset()")
    void attributeWindows() {
        AttributeState attributes = new AttributeState();
        assertFalse(attributes.pending());
        attributes.beginPending();
        attributes.beginPending();
        attributes.reset();
        assertEquals(2, attributes.pendingCount());
        attributes.endPending();
        attributes.endPending();
        attributes.endPending();
        assertFalse(attributes.pending());
        assertEquals(0, attributes.pendingCount());
    }
}
