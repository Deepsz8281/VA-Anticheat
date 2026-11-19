package dev.vaplus.anticheat.player.state;

import dev.vaplus.anticheat.player.state.AttributeState.AttributeSnapshot;
import dev.vaplus.anticheat.player.state.AttributeState.Modifier;
import dev.vaplus.anticheat.player.state.AttributeState.Operation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The effective-value formula asserted here is the vanilla one from the Minecraft Wiki Attribute
 * page (fetched 2026-11-13; LEAD decision phase 2 round 2):
 * {@code clamp((base + Σ ADD) * (1 + Σ MULTIPLY_BASE) * Π(1 + MULTIPLY_TOTAL_i), min, max)}.
 * The multi-modifier tests prove the difference to the superseded round-1 brief grouping
 * ({@code Π} for MULTIPLY_BASE, {@code Σ} for MULTIPLY_TOTAL).
 */
@DisplayName("AttributeState (named attribute bases and modifiers)")
class AttributeStateTest {

    private static final String UNTRACKED = "minecraft:luck";

    @Test
    @DisplayName("a fresh state carries the vanilla player defaults, including the 26.2 attributes")
    void vanillaDefaults() {
        AttributeState state = new AttributeState();
        assertEquals(0.1, state.movementSpeed(), "Wiki Attribute/Movement Speed: Player base 0.1");
        assertEquals(0.41999998688697815, state.jumpStrength(), "float 0.42 as the exact double");
        assertEquals(0.6, state.stepHeight());
        assertEquals(0.08, state.gravity());
        assertEquals(3.0, state.safeFallDistance());
        assertEquals(1.0, state.fallDamageMultiplier());
        assertEquals(1.0, state.scale());
        assertEquals(4.5, state.blockInteractionRange());
        assertEquals(3.0, state.entityInteractionRange());
        assertEquals(0.3, state.sneakingSpeed());
        assertEquals(0.0, state.movementEfficiency());
        assertEquals(0.0, state.waterMovementEfficiency());
        assertEquals(4.0, state.attackSpeed());
        assertEquals(1.0, state.airDragModifier(), "26.2 default, version-notes.md section 3.2");
        assertEquals(1.0, state.frictionModifier(), "26.2 default, version-notes.md section 3.2");
        assertEquals(0.0, state.bounciness(), "26.2 default, version-notes.md section 3.2");
        assertTrue(state.modifiers(AttributeState.MOVEMENT_SPEED).isEmpty());
    }

    @Test
    @DisplayName("bare names are canonicalised to minecraft names; untracked names are rejected")
    void nameCanonicalisation() {
        AttributeState state = new AttributeState();
        assertTrue(state.has("movement_speed"));
        assertTrue(state.has(AttributeState.MOVEMENT_SPEED));
        assertEquals(state.value(AttributeState.MOVEMENT_SPEED), state.value("movement_speed"));
        assertFalse(state.has(UNTRACKED));
        assertThrows(IllegalArgumentException.class, () -> state.value(UNTRACKED));
        assertThrows(IllegalArgumentException.class, () -> state.value("not an identifier"));
        assertThrows(NullPointerException.class, () -> state.has(null));
    }

    @Test
    @DisplayName("setBase updates the base, retains modifiers and creates new named entries")
    void setBase() {
        AttributeState state = new AttributeState();
        state.addModifier("movement_speed", new Modifier(Operation.ADD, 0.02, "test:bonus"));
        state.setBase("movement_speed", 0.15);
        assertEquals(0.15, state.base(AttributeState.MOVEMENT_SPEED));
        assertEquals(0.17, state.movementSpeed(), 1e-12, "modifiers survive a base update");

        assertFalse(state.has(UNTRACKED));
        state.setBase(UNTRACKED, 1.0);
        assertTrue(state.has(UNTRACKED), "setBase creates an entry for a new named attribute");
        assertEquals(1.0, state.value(UNTRACKED));
        assertThrows(IllegalArgumentException.class, () -> state.setBase("movement_speed", Double.NaN));
        assertThrows(NullPointerException.class, () -> state.setBase(null, 1.0));
    }

    @Test
    @DisplayName("typed setters route through setBase (retaining modifiers)")
    void typedSetters() {
        AttributeState state = new AttributeState();
        state.gravity(0.06);
        assertEquals(0.06, state.gravity());
        state.bounciness(0.5);
        assertEquals(0.5, state.bounciness());
        state.airDragModifier(2.0);
        assertEquals(2.0, state.airDragModifier());
        state.frictionModifier(0.5);
        assertEquals(0.5, state.frictionModifier());
        state.jumpStrength(0.5);
        assertEquals(0.5, state.jumpStrength());
        state.stepHeight(1.25);
        assertEquals(1.25, state.stepHeight());
        state.safeFallDistance(10.0);
        assertEquals(10.0, state.safeFallDistance());
        state.fallDamageMultiplier(2.0);
        assertEquals(2.0, state.fallDamageMultiplier());
        state.scale(0.5);
        assertEquals(0.5, state.scale());
        state.blockInteractionRange(6.0);
        assertEquals(6.0, state.blockInteractionRange());
        state.entityInteractionRange(5.0);
        assertEquals(5.0, state.entityInteractionRange());
        state.sneakingSpeed(0.5);
        assertEquals(0.5, state.sneakingSpeed());
        state.movementEfficiency(1.0);
        assertEquals(1.0, state.movementEfficiency());
        state.waterMovementEfficiency(0.5);
        assertEquals(0.5, state.waterMovementEfficiency());
        state.attackSpeed(2.0);
        assertEquals(2.0, state.attackSpeed());
        assertEquals(0.1, state.movementSpeed(), "untouched attributes keep their default");
    }

    @Test
    @DisplayName("single modifiers: ADD, then MULTIPLY_BASE, then MULTIPLY_TOTAL (both groupings agree here)")
    void singleModifierFormula() {
        AttributeState state = new AttributeState();
        state.setBase("minecraft:movement_speed", 100.0);
        state.addModifier("movement_speed", new Modifier(Operation.ADD, 10.0, "a:add"));
        state.addModifier("movement_speed", new Modifier(Operation.MULTIPLY_BASE, 0.5, "a:base"));
        state.addModifier("movement_speed", new Modifier(Operation.MULTIPLY_TOTAL, 0.2, "a:total"));
        assertEquals(198.0, state.movementSpeed(), 1e-9, "(100 + 10) * 1.5 * 1.2");
    }

    @Test
    @DisplayName("vanilla: several add_multiplied_base modifiers are summed into ONE factor")
    void multiplyBaseSummed() {
        AttributeState twoBase = new AttributeState();
        twoBase.setBase("minecraft:movement_speed", 10.0);
        twoBase.addModifier("movement_speed", new Modifier(Operation.MULTIPLY_BASE, 0.5, "x"));
        twoBase.addModifier("movement_speed", new Modifier(Operation.MULTIPLY_BASE, 1.0, "y"));
        assertEquals(25.0, twoBase.movementSpeed(), 1e-9, "10 * (1 + 0.5 + 1.0) = 25");
        assertNotEquals(30.0, twoBase.movementSpeed(), 1e-9, "superseded brief grouping: 10 * 1.5 * 2.0 = 30");

        // negative base multipliers also sum: 10 * (1 - 0.5 - 0.25) = 2.5 (brief: 10 * 0.5 * 0.75 = 3.75)
        AttributeState negative = new AttributeState();
        negative.setBase("minecraft:movement_speed", 10.0);
        negative.addModifier("movement_speed", new Modifier(Operation.MULTIPLY_BASE, -0.5, "x"));
        negative.addModifier("movement_speed", new Modifier(Operation.MULTIPLY_BASE, -0.25, "y"));
        assertEquals(2.5, negative.movementSpeed(), 1e-9);
    }

    @Test
    @DisplayName("vanilla: every add_multiplied_total modifier is its own (1 + amount) factor")
    void multiplyTotalProduct() {
        AttributeState twoTotal = new AttributeState();
        twoTotal.setBase("minecraft:movement_speed", 10.0);
        twoTotal.addModifier("movement_speed", new Modifier(Operation.MULTIPLY_TOTAL, 0.2, "x"));
        twoTotal.addModifier("movement_speed", new Modifier(Operation.MULTIPLY_TOTAL, 0.3, "y"));
        assertEquals(15.6, twoTotal.movementSpeed(), 1e-9, "10 * 1.2 * 1.3 = 15.6");
        assertNotEquals(15.0, twoTotal.movementSpeed(), 1e-9, "superseded brief grouping: 10 * (1 + 0.5) = 15");

        // Speed II + sprint style stack: 0.1 * (1 + 0.4) * (1 + 0.3) = 0.182 (brief: 0.1 * 1.7 = 0.17)
        AttributeState speed = new AttributeState();
        speed.addModifier("movement_speed", new Modifier(Operation.MULTIPLY_TOTAL, 0.4, "test:effect"));
        speed.addModifier("movement_speed", new Modifier(Operation.MULTIPLY_TOTAL, 0.3, "test:sprint"));
        assertEquals(0.182, speed.movementSpeed(), 1e-12);
    }

    @Test
    @DisplayName("vanilla: full mix of all three operations with several modifiers each")
    void mixedMultiModifier() {
        AttributeState state = new AttributeState();
        state.setBase("minecraft:attack_speed", 4.0);
        state.addModifier("attack_speed", new Modifier(Operation.ADD, 1.0, "a1"));
        state.addModifier("attack_speed", new Modifier(Operation.ADD, -2.0, "a2"));
        state.addModifier("attack_speed", new Modifier(Operation.MULTIPLY_BASE, 0.5, "b1"));
        state.addModifier("attack_speed", new Modifier(Operation.MULTIPLY_BASE, 0.25, "b2"));
        state.addModifier("attack_speed", new Modifier(Operation.MULTIPLY_TOTAL, 1.0, "t1"));
        state.addModifier("attack_speed", new Modifier(Operation.MULTIPLY_TOTAL, -0.5, "t2"));
        // (4 + 1 - 2) * (1 + 0.75) * (2.0 * 0.5) = 3 * 1.75 * 1 = 5.25 ; brief: 3 * 1.875 * 1.5 = 8.4375
        assertEquals(5.25, state.attackSpeed(), 1e-12);
    }

    @Test
    @DisplayName("the final value is clamped to the Wiki range of the attribute; NaN maps to the minimum")
    void rangeClamp() {
        AttributeState state = new AttributeState();
        state.gravity(5.0);
        assertEquals(1.0, state.gravity(), "Wiki: gravity max 1");
        state.gravity(-5.0);
        assertEquals(-1.0, state.gravity(), "Wiki: gravity min -1");
        state.scale(0.0);
        assertEquals(0.0625, state.scale(), "Wiki: scale min 0.0625");
        state.stepHeight(0.6);
        state.addModifier("step_height", new Modifier(Operation.MULTIPLY_TOTAL, 100.0, "big"));
        assertEquals(10.0, state.stepHeight(), "clamped after the modifiers, Wiki max 10");
        assertEquals(0.6, state.base("step_height"), "the base itself is not clamped");
        state.movementSpeed(-0.5);
        assertEquals(0.0, state.movementSpeed(), "Wiki: movement_speed min 0");
        state.airDragModifier(4096.0);
        assertEquals(2048.0, state.airDragModifier(), "Wiki: air_drag_modifier max 2048");
        state.frictionModifier(-1.0);
        assertEquals(0.0, state.frictionModifier(), "Wiki: friction_modifier min 0");

        assertEquals(0.0, AttributeState.minValue("movement_speed"));
        assertEquals(1024.0, AttributeState.maxValue(AttributeState.MOVEMENT_SPEED));
        assertEquals(Double.NEGATIVE_INFINITY, AttributeState.minValue(UNTRACKED), "no known range");
        state.setBase(UNTRACKED, 1e9);
        assertEquals(1e9, state.value(UNTRACKED), "names without a known range are not clamped");
    }

    @Test
    @DisplayName("version() advances on mutation; hasModifier/valueWithout support engine-side caching")
    void versionAndExclusion() {
        AttributeState state = new AttributeState();
        long v0 = state.version();
        state.addModifier("movement_speed", new Modifier(Operation.MULTIPLY_TOTAL, 0.3, "minecraft:sprinting"));
        long v1 = state.version();
        assertTrue(v1 > v0);
        assertEquals(v1, state.version(), "reads do not advance the stamp");
        state.addModifier("movement_speed", new Modifier(Operation.MULTIPLY_TOTAL, 0.2, "test:effect"));

        assertTrue(state.hasModifier("movement_speed", "minecraft:sprinting"));
        assertFalse(state.hasModifier("movement_speed", "test:none"));
        assertFalse(state.hasModifier(UNTRACKED, "minecraft:sprinting"));
        assertEquals(0.1 * 1.3 * 1.2, state.movementSpeed(), 1e-12);
        assertEquals(0.1 * 1.2, state.valueWithout("movement_speed", "minecraft:sprinting"), 1e-12);
        assertEquals(state.movementSpeed(), state.valueWithout("movement_speed", "test:none"));
        state.reset();
        assertTrue(state.version() > v1);
    }

    @Test
    @DisplayName("modifier recomputation is deterministic in modifier-id order")
    void modifierOrder() {
        AttributeState orderedFirst = new AttributeState();
        AttributeState orderedSecond = new AttributeState();
        for (AttributeState target : new AttributeState[]{orderedFirst, orderedSecond}) {
            target.setBase("minecraft:movement_speed", 1.0);
        }
        // the same two ADD modifiers, added in different orders
        orderedFirst.addModifier("movement_speed", new Modifier(Operation.ADD, 0.1, "a"));
        orderedFirst.addModifier("movement_speed", new Modifier(Operation.ADD, 0.2, "b"));
        orderedSecond.addModifier("movement_speed", new Modifier(Operation.ADD, 0.2, "b"));
        orderedSecond.addModifier("movement_speed", new Modifier(Operation.ADD, 0.1, "a"));
        assertEquals(orderedFirst.movementSpeed(), orderedSecond.movementSpeed(),
                "id-ordered evaluation makes the result order independent");
    }

    @Test
    @DisplayName("addModifier replaces a repeated id instead of stacking; ids are scoped per attribute")
    void addModifierReplace() {
        AttributeState state = new AttributeState();
        state.addModifier("movement_speed", new Modifier(Operation.ADD, 0.1, "test:mod"));
        state.addModifier("movement_speed", new Modifier(Operation.ADD, 0.3, "test:mod"));
        assertEquals(0.4, state.movementSpeed(), 1e-12, "replaced, not stacked");

        // the same id on another attribute is independent
        state.addModifier("gravity", new Modifier(Operation.ADD, 0.01, "test:mod"));
        assertEquals(0.09, state.gravity(), 1e-12);
        assertEquals(0.4, state.movementSpeed(), 1e-12);
    }

    @Test
    @DisplayName("removeModifier reports false for untracked names or absent ids and never creates entries")
    void removeModifier() {
        AttributeState state = new AttributeState();
        assertFalse(state.removeModifier(UNTRACKED, "test:mod"));
        assertFalse(state.has(UNTRACKED), "a failed removal must not create an entry");
        state.addModifier("movement_speed", new Modifier(Operation.ADD, 0.1, "test:mod"));
        assertFalse(state.removeModifier("movement_speed", "test:other"));
        assertEquals(0.2, state.movementSpeed(), 1e-12);
        assertTrue(state.removeModifier("movement_speed", "test:mod"));
        assertEquals(0.1, state.movementSpeed(), 1e-12);
        assertFalse(state.removeModifier("movement_speed", "test:mod"));
        assertThrows(NullPointerException.class, () -> state.removeModifier("movement_speed", null));
        assertThrows(IllegalArgumentException.class, () -> state.removeModifier("movement_speed", " "));
    }

    @Test
    @DisplayName("clear(name) keeps the base, clear() drops every modifier list")
    void clearModifiers() {
        AttributeState state = new AttributeState();
        state.addModifier("movement_speed", new Modifier(Operation.ADD, 0.1, "test:mod"));
        state.addModifier("gravity", new Modifier(Operation.MULTIPLY_TOTAL, 0.5, "test:mod"));

        state.clear("movement_speed");
        assertEquals(0.1, state.movementSpeed(), 1e-12, "base retained");
        assertEquals(0.12, state.gravity(), 1e-12, "the other attribute is untouched");

        state.clear();
        assertEquals(0.08, state.gravity(), 1e-12);
        assertTrue(state.modifiers("movement_speed").isEmpty());
        assertTrue(state.modifiers("gravity").isEmpty());
        assertEquals(0.1, state.base("movement_speed"), "bases and named entries survive clear()");
        state.clear(UNTRACKED);
        assertFalse(state.has(UNTRACKED), "clearing an untracked name is a no-op");
    }

    @Test
    @DisplayName("setAttribute replaces a whole packet entry: base, modifiers, later duplicate ids win")
    void setAttribute() {
        AttributeState state = new AttributeState();
        state.addModifier("movement_speed", new Modifier(Operation.ADD, 99.0, "old"));
        List<Modifier> packetModifiers = new ArrayList<>();
        packetModifiers.add(new Modifier(Operation.ADD, 0.01, "dup"));
        packetModifiers.add(new Modifier(Operation.ADD, 0.02, "dup"));
        state.setAttribute("movement_speed", 0.1, packetModifiers);
        assertEquals(0.12, state.movementSpeed(), 1e-12, "old modifier gone; the later dup replaces the earlier");

        packetModifiers.add(new Modifier(Operation.ADD, 5.0, "late"));
        assertEquals(0.12, state.movementSpeed(), 1e-12, "the input list is copied, not retained");

        // a whole new entry drops the previous modifiers
        state.setAttribute("movement_speed", 0.2, List.of());
        assertEquals(0.2, state.movementSpeed(), 1e-12);
        assertThrows(NullPointerException.class, () -> state.setAttribute("movement_speed", 0.1, null));
        assertThrows(NullPointerException.class, () -> state.setAttribute("movement_speed", 0.1,
                new ArrayList<>() {{ add(null); }}));
    }

    @Test
    @DisplayName("modifiers(name) returns a detached list sorted by id")
    void modifiersSorted() {
        AttributeState state = new AttributeState();
        state.addModifier("movement_speed", new Modifier(Operation.ADD, 0.1, "zebra"));
        state.addModifier("movement_speed", new Modifier(Operation.ADD, 0.2, "alpha"));
        List<Modifier> modifiers = state.modifiers("movement_speed");
        assertEquals(List.of("alpha", "zebra"), List.of(modifiers.get(0).id(), modifiers.get(1).id()));
        assertThrows(UnsupportedOperationException.class, () -> modifiers.add(
                new Modifier(Operation.ADD, 1.0, "new")), "detached and immutable");
        assertThrows(IllegalArgumentException.class, () -> state.modifiers(UNTRACKED));
    }

    @Test
    @DisplayName("attribute(name) returns base, modifiers and effective value at read time")
    void singleSnapshot() {
        AttributeState state = new AttributeState();
        state.addModifier("movement_speed", new Modifier(Operation.ADD, 0.05, "test:mod"));
        AttributeSnapshot snapshot = state.attribute("movement_speed");
        assertEquals(0.1, snapshot.base());
        assertEquals(0.15, snapshot.value(), 1e-12);
        assertEquals(1, snapshot.modifiers().size());

        state.setBase("movement_speed", 0.2);
        assertEquals(0.15, snapshot.value(), 1e-12, "the detached snapshot ignores later changes");
        assertThrows(IllegalArgumentException.class, () -> state.attribute(UNTRACKED));
    }

    @Test
    @DisplayName("snapshot() covers every attribute in insertion order and is deep-immutable")
    void fullSnapshot() {
        AttributeState state = new AttributeState();
        state.addModifier("gravity", new Modifier(Operation.ADD, 0.01, "test:mod"));
        Map<String, AttributeSnapshot> snapshot = state.snapshot();
        assertTrue(snapshot.containsKey(AttributeState.MOVEMENT_SPEED));
        assertTrue(snapshot.containsKey(AttributeState.BOUNCINESS));
        assertTrue(snapshot.containsKey(AttributeState.AIR_DRAG_MODIFIER));

        state.addModifier("gravity", new Modifier(Operation.ADD, 0.5, "test:late"));
        assertEquals(0.09, snapshot.get("minecraft:gravity").value(), 1e-12,
                "the snapshot keeps the value it was taken at (0.08 + 0.01), ignoring later changes");
        assertEquals(0.59, state.gravity(), 1e-12, "the live state sees the late modifier (within range)");
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("minecraft:luck",
                new AttributeSnapshot(1.0, List.of(), 1.0)));
    }

    @Test
    @DisplayName("reset returns to vanilla defaults, dropping modifiers and non-default named attributes")
    void reset() {
        AttributeState state = new AttributeState();
        state.movementSpeed(0.5);
        state.addModifier("gravity", new Modifier(Operation.ADD, 0.01, "test:mod"));
        state.setBase(UNTRACKED, 1.0);

        state.reset();
        assertEquals(0.1, state.movementSpeed());
        assertEquals(0.08, state.gravity());
        assertTrue(state.modifiers("gravity").isEmpty());
        assertFalse(state.has(UNTRACKED), "non-default named attributes are dropped");
        assertEquals(0.41999998688697815, state.jumpStrength(), "defaults fully restored");
    }

    @Test
    @DisplayName("bounciness is range-clamped to [0, 1], after all modifiers")
    void bouncinessClamp() {
        AttributeState state = new AttributeState();
        state.bounciness(2.0);
        assertEquals(1.0, state.bounciness(), "Wiki Attribute/Bounciness: max 1");
        state.bounciness(-5.0);
        assertEquals(0.0, state.bounciness(), "Wiki Attribute/Bounciness: min 0");
        state.bounciness(0.75);
        assertEquals(0.75, state.bounciness());

        // clamping also applies after modifiers, including overflow to NaN (clamped to 0)
        state.bounciness(0.0);
        state.addModifier(AttributeState.BOUNCINESS, new Modifier(Operation.MULTIPLY_BASE, Double.MAX_VALUE, "huge"));
        assertEquals(0.0, state.bounciness(), "0 * huge factor stays at the minimum");
        state.clear(AttributeState.BOUNCINESS);

        state.bounciness(0.5);
        state.addModifier(AttributeState.BOUNCINESS, new Modifier(Operation.ADD, 1.0, "over"));
        assertEquals(1.0, state.bounciness(), "modifier overflow clamps to the maximum");
        state.addModifier(AttributeState.BOUNCINESS, new Modifier(Operation.ADD, -3.0, "under"));
        assertEquals(0.0, state.bounciness(), "modifier underflow clamps to the minimum");

        // every tracked attribute is clamped to its Wiki range now, see rangeClamp()
        AttributeState other = new AttributeState();
        other.gravity(0.5);
        assertEquals(0.5, other.gravity(), "in-range values are untouched");
    }

    @Test
    @DisplayName("Modifier validates operation, amount and id")
    void modifierValidation() {
        assertThrows(NullPointerException.class, () -> new Modifier(null, 1.0, "id"));
        assertThrows(IllegalArgumentException.class, () -> new Modifier(Operation.ADD, Double.NaN, "id"));
        assertThrows(IllegalArgumentException.class, () -> new Modifier(Operation.ADD, Double.POSITIVE_INFINITY, "id"));
        assertThrows(NullPointerException.class, () -> new Modifier(Operation.ADD, 1.0, null));
        assertThrows(IllegalArgumentException.class, () -> new Modifier(Operation.ADD, 1.0, " "));
        assertEquals(new Modifier(Operation.ADD, 1.0, "a"), new Modifier(Operation.ADD, 1.0, "a"));
        assertNotEquals(new Modifier(Operation.ADD, 1.0, "a"), new Modifier(Operation.MULTIPLY_TOTAL, 1.0, "a"));
    }

    @Test
    @DisplayName("AttributeSnapshot validates its base and copies its modifier list")
    void snapshotValidation() {
        assertThrows(IllegalArgumentException.class, () -> new AttributeSnapshot(Double.NaN, List.of(), 1.0));
        assertThrows(NullPointerException.class, () -> new AttributeSnapshot(1.0, null, 1.0));
        List<Modifier> modifiers = new ArrayList<>();
        modifiers.add(new Modifier(Operation.ADD, 1.0, "a"));
        AttributeSnapshot snapshot = new AttributeSnapshot(1.0, modifiers, 2.0);
        modifiers.add(new Modifier(Operation.ADD, 2.0, "b"));
        assertEquals(1, snapshot.modifiers().size(), "input list copied");
    }
}
