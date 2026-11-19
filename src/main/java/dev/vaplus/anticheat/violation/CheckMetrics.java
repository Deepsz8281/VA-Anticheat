package dev.vaplus.anticheat.violation;

import dev.vaplus.anticheat.check.CheckRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-check flag counters and the number of distinct players behind them.
 *
 * <p>Tuning a false-positive rate needs both numbers: "40 flags" from one player is a different
 * signal than "40 flags from 39 players". Flags are recorded on owner threads, so the counters are
 * lock-free; the distinct-player set is only touched when a violation already happened (never on
 * the legitimate hot path).
 */
public final class CheckMetrics {

    /** One check's counters. */
    public record Entry(String checkId, long flags, int players) {
        /** Flags per distinct player; 0 when nothing was recorded. */
        public double flagsPerPlayer() {
            return players == 0 ? 0.0 : (double) flags / players;
        }
    }

    private final int slots;
    private final LongAdder[] flags;
    private final Set<UUID>[] players;

    @SuppressWarnings("unchecked")
    public CheckMetrics(int slots) {
        this.slots = Math.max(0, slots);
        this.flags = new LongAdder[this.slots];
        this.players = new Set[this.slots];
        for (int i = 0; i < this.slots; i++) {
            flags[i] = new LongAdder();
        }
    }

    /** Records one violation of {@code slot} for {@code player}. Safe from any thread. */
    public void record(int slot, UUID player) {
        if (slot < 0 || slot >= slots || player == null) {
            return;
        }
        flags[slot].increment();
        Set<UUID> set = players[slot];
        if (set == null) {
            synchronized (this) {
                set = players[slot];
                if (set == null) {
                    set = ConcurrentHashMap.newKeySet();
                    players[slot] = set;
                }
            }
        }
        // Bounded per check: a busy server must not accumulate every uuid that ever flagged.
        if (set.size() < 2_000) {
            set.add(player);
        }
    }

    public long flags(int slot) {
        return slot < 0 || slot >= slots ? 0L : flags[slot].sum();
    }

    public int players(int slot) {
        if (slot < 0 || slot >= slots) {
            return 0;
        }
        Set<UUID> set = players[slot];
        return set == null ? 0 : set.size();
    }

    /** All checks with at least one flag, most flagged first. */
    public List<Entry> entries(CheckRegistry registry) {
        List<Entry> entries = new ArrayList<>();
        int count = Math.min(slots, registry.size());
        for (int slot = 0; slot < count; slot++) {
            long total = flags(slot);
            if (total > 0) {
                entries.add(new Entry(registry.descriptor(slot).id(), total, players(slot)));
            }
        }
        entries.sort(Comparator.comparingLong(Entry::flags).reversed());
        return List.copyOf(entries);
    }

    public void reset() {
        for (int i = 0; i < slots; i++) {
            flags[i].reset();
            Set<UUID> set = players[i];
            if (set != null) {
                set.clear();
            }
        }
    }

    @Override
    public String toString() {
        long total = 0L;
        for (int i = 0; i < slots; i++) {
            total += flags[i].sum();
        }
        return "CheckMetrics[slots=" + slots + ", flags=" + total + "]";
    }
}
