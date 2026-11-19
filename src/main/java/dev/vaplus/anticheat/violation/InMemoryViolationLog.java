package dev.vaplus.anticheat.violation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Bounded in-memory log: the newest {@code perPlayer} records for up to {@code maxPlayers} players
 * (least recently flagged players are evicted first). Violations are rare compared to packets, so a
 * single monitor is sufficient.
 */
public final class InMemoryViolationLog implements ViolationLog {

    private final int perPlayer;
    private final LinkedHashMap<UUID, ArrayDeque<ViolationRecord>> records;

    public InMemoryViolationLog(int perPlayer, int maxPlayers) {
        if (perPlayer <= 0 || maxPlayers <= 0) {
            throw new IllegalArgumentException("limits must be positive");
        }
        this.perPlayer = perPlayer;
        // Insertion order + re-insert on append = "least recently flagged first". Queries must not
        // refresh the order, so access order is deliberately not used.
        this.records = new LinkedHashMap<>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, ArrayDeque<ViolationRecord>> eldest) {
                return size() > maxPlayers;
            }
        };
    }

    @Override
    public synchronized void append(ViolationRecord record) {
        ArrayDeque<ViolationRecord> deque = records.remove(record.playerId());
        if (deque == null) {
            deque = new ArrayDeque<>();
        }
        records.put(record.playerId(), deque);
        deque.addFirst(record);
        while (deque.size() > perPlayer) {
            deque.removeLast();
        }
    }

    @Override
    public CompletableFuture<List<ViolationRecord>> query(UUID playerId, int offset, int limit) {
        if (offset < 0 || limit < 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("offset and limit must not be negative"));
        }
        List<ViolationRecord> result = new ArrayList<>(Math.min(limit, perPlayer));
        synchronized (this) {
            ArrayDeque<ViolationRecord> deque = records.get(playerId);
            if (deque != null) {
                Iterator<ViolationRecord> iterator = deque.iterator();
                int index = 0;
                while (iterator.hasNext() && result.size() < limit) {
                    ViolationRecord record = iterator.next();
                    if (index++ >= offset) {
                        result.add(record);
                    }
                }
            }
        }
        return CompletableFuture.completedFuture(List.copyOf(result));
    }

    public synchronized int trackedPlayers() {
        return records.size();
    }
}
