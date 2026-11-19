package dev.vaplus.anticheat.violation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ActionThreshold")
class ActionThresholdTest {

    private static final List<ViolationAction> ACTIONS = List.of(new ViolationAction.Kick(null));

    private static ActionThreshold once(double vl) {
        return new ActionThreshold(vl, 0.0, ACTIONS);
    }

    private static ActionThreshold repeating(double vl, double every) {
        return new ActionThreshold(vl, every, ACTIONS);
    }

    @Test
    @DisplayName("without repeat it fires exactly when the level crosses vl")
    void firesOnCrossing() {
        ActionThreshold threshold = once(10);
        assertTrue(threshold.triggers(9.0, 10.0));
        assertTrue(threshold.triggers(9.9, 10.0));
        assertTrue(threshold.triggers(0.0, 10.5));
        assertFalse(threshold.triggers(8.0, 9.99));
        assertFalse(threshold.triggers(0.0, 0.0));
    }

    @Test
    @DisplayName("without repeat it never fires again above the threshold")
    void noRefire() {
        ActionThreshold threshold = once(10);
        assertFalse(threshold.triggers(10.0, 11.0));
        assertFalse(threshold.triggers(10.5, 500.0));
    }

    @Test
    @DisplayName("big jumps over the threshold fire once")
    void bigJump() {
        assertTrue(once(10).triggers(0.0, 1000.0));
        assertTrue(repeating(10, 5).triggers(0.0, 1000.0));
    }

    @Test
    @DisplayName("a level that does not rise never triggers")
    void noRise() {
        ActionThreshold threshold = repeating(10, 5);
        assertFalse(threshold.triggers(12.0, 12.0));
        assertFalse(threshold.triggers(20.0, 12.0));
        assertFalse(once(10).triggers(10.0, 10.0));
    }

    @Test
    @DisplayName("repeat fires on each step boundary above vl")
    void repeatSteps() {
        ActionThreshold threshold = repeating(10, 5);
        assertTrue(threshold.triggers(9.0, 10.0), "initial");
        assertFalse(threshold.triggers(10.0, 14.9), "inside step 0");
        assertTrue(threshold.triggers(10.0, 15.0), "step 1 boundary");
        assertTrue(threshold.triggers(14.9, 15.0));
        assertFalse(threshold.triggers(15.0, 19.99));
        assertTrue(threshold.triggers(19.99, 20.0));
        assertTrue(threshold.triggers(10.0, 30.0), "jumping several steps fires once");
    }

    @Test
    @DisplayName("repeat counts a full sequence of unit violations exactly once per step")
    void sequenceOfWeightOne() {
        ActionThreshold threshold = repeating(3, 2);
        List<Integer> fired = new ArrayList<>();
        double level = 0;
        for (int i = 1; i <= 12; i++) {
            double next = level + 1.0;
            if (threshold.triggers(level, next)) {
                fired.add(i);
            }
            level = next;
        }
        assertEquals(List.of(3, 5, 7, 9, 11), fired);
    }

    @Test
    @DisplayName("fractional repeat steps")
    void fractionalRepeat() {
        ActionThreshold threshold = repeating(1.0, 0.5);
        assertTrue(threshold.triggers(0.9, 1.0));
        assertFalse(threshold.triggers(1.0, 1.4));
        assertTrue(threshold.triggers(1.4, 1.5));
    }

    @Test
    @DisplayName("constructor validation")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> new ActionThreshold(0.0, 0.0, ACTIONS));
        assertThrows(IllegalArgumentException.class, () -> new ActionThreshold(-1.0, 0.0, ACTIONS));
        assertThrows(IllegalArgumentException.class, () -> new ActionThreshold(Double.NaN, 0.0, ACTIONS));
        assertThrows(IllegalArgumentException.class, () -> new ActionThreshold(1.0, -0.1, ACTIONS));
        assertThrows(NullPointerException.class, () -> new ActionThreshold(1.0, 0.0, null));
    }

    @Test
    @DisplayName("actions are copied defensively")
    void defensiveCopy() {
        List<ViolationAction> mutable = new ArrayList<>(ACTIONS);
        ActionThreshold threshold = new ActionThreshold(1, 0, mutable);
        mutable.clear();
        assertEquals(1, threshold.actions().size());
        assertThrows(UnsupportedOperationException.class, () -> threshold.actions().clear());
    }

    @Test
    @DisplayName("accumulated floating-point error does not skip an exact threshold (10 x 0.1 reaches 1.0)")
    void floatingPointAccumulation() {
        ActionThreshold threshold = new ActionThreshold(1.0, 0.0, List.of(new ViolationAction.Kick(null)));
        double level = 0.0;
        int firedAt = -1;
        for (int i = 1; i <= 11 && firedAt < 0; i++) {
            double before = level;
            level += 0.1;
            if (threshold.triggers(before, level)) {
                firedAt = i;
            }
        }
        assertEquals(10, firedAt);
        assertTrue(ActionThreshold.reached(0.9999999999999999, 1.0));
        assertFalse(ActionThreshold.reached(0.99, 1.0));
    }
}
