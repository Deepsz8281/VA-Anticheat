package dev.vaplus.anticheat.violation;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Setback confirmation window (plan item 9): a single violation spike must not rubber-band a player.
 *
 * <p>Per (player, check) the last {@value #ENTRIES} violation timestamps are kept. A setback is
 * only requested when at least {@code setback.confirmations} of them fall inside
 * {@code setback.window} milliseconds. With {@code confirmations = 1} the old behaviour (every
 * violation above the check's setback VL) is preserved.
 *
 * <p>Only reached from {@link ViolationDispatcher#apply}, i.e. once per violation — never from the
 * legitimate hot path. Bounded: when more than {@value #MAX_KEYS} player/check pairs have flagged,
 * the history is dropped as a whole (confirmation restarts, nothing becomes unsafe).
 */
final class SetbackConfirmations {

    static final int ENTRIES = 8;
    static final int MAX_KEYS = 10_000;

    private record Key(UUID player, int slot) {
    }

    private static final class Ring {
        private final long[] stamps = new long[ENTRIES];
        private int index;
        private int filled;

        void add(long stamp) {
            stamps[index] = stamp;
            index = (index + 1) % ENTRIES;
            if (filled < ENTRIES) {
                filled++;
            }
        }

        int count(long now, long window) {
            int count = 0;
            for (int i = 0; i < filled; i++) {
                if (now - stamps[i] <= window) {
                    count++;
                }
            }
            return count;
        }
    }

    private final Map<Key, Ring> rings = new HashMap<>();

    /** Records this violation and answers whether a setback is confirmed. */
    boolean confirmed(UUID player, int slot, long nowNanos, long windowNanos, int required) {
        if (required <= 1) {
            return true;
        }
        if (windowNanos <= 0) {
            return false;
        }
        if (rings.size() > MAX_KEYS) {
            rings.clear();
        }
        Ring ring = rings.computeIfAbsent(new Key(player, slot), key -> new Ring());
        ring.add(nowNanos);
        return ring.count(nowNanos, windowNanos) >= required;
    }

    void clear() {
        rings.clear();
    }

    int tracked() {
        return rings.size();
    }
}
