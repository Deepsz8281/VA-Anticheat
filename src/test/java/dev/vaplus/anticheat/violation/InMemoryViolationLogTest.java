package dev.vaplus.anticheat.violation;

import dev.vaplus.anticheat.check.CheckCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("InMemoryViolationLog")
class InMemoryViolationLogTest {

    private static ViolationRecord record(UUID player, int n) {
        return new ViolationRecord(player, "P", "check", CheckCategory.MOVEMENT, n, "d" + n, 10, "1.21.4", n);
    }

    private static List<String> details(InMemoryViolationLog log, UUID player, int offset, int limit) {
        return log.query(player, offset, limit).join().stream().map(ViolationRecord::details).toList();
    }

    @Test
    @DisplayName("returns the newest record first")
    void newestFirst() {
        InMemoryViolationLog log = new InMemoryViolationLog(10, 10);
        UUID id = UUID.randomUUID();
        for (int i = 1; i <= 4; i++) {
            log.append(record(id, i));
        }
        assertEquals(List.of("d4", "d3", "d2", "d1"), details(log, id, 0, 10));
    }

    @Test
    @DisplayName("keeps only the newest perPlayer records")
    void perPlayerCap() {
        InMemoryViolationLog log = new InMemoryViolationLog(3, 10);
        UUID id = UUID.randomUUID();
        for (int i = 1; i <= 7; i++) {
            log.append(record(id, i));
        }
        assertEquals(List.of("d7", "d6", "d5"), details(log, id, 0, 100));
    }

    @Test
    @DisplayName("evicts the least recently flagged player when maxPlayers is exceeded")
    void lruEviction() {
        InMemoryViolationLog log = new InMemoryViolationLog(5, 2);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        log.append(record(a, 1));
        log.append(record(b, 1));
        log.append(record(a, 2)); // a is now more recent than b
        log.append(record(c, 1)); // evicts b
        assertEquals(2, log.trackedPlayers());
        assertTrue(details(log, b, 0, 10).isEmpty());
        assertEquals(List.of("d2", "d1"), details(log, a, 0, 10));
        assertEquals(List.of("d1"), details(log, c, 0, 10));
    }

    @Test
    @DisplayName("querying a player (/ac logs) does not protect them from eviction")
    void queryDoesNotRefreshOrder() {
        InMemoryViolationLog log = new InMemoryViolationLog(5, 2);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        log.append(record(a, 1));
        log.append(record(b, 1));
        details(log, a, 0, 10); // reading must not make a "recent"
        log.append(record(c, 1)); // evicts a, the least recently flagged
        assertTrue(details(log, a, 0, 10).isEmpty());
        assertEquals(List.of("d1"), details(log, b, 0, 10));
    }

    @Test
    @DisplayName("offset and limit page through the records")
    void paging() {
        InMemoryViolationLog log = new InMemoryViolationLog(20, 5);
        UUID id = UUID.randomUUID();
        for (int i = 1; i <= 5; i++) {
            log.append(record(id, i));
        }
        assertEquals(List.of("d5", "d4"), details(log, id, 0, 2));
        assertEquals(List.of("d3", "d2"), details(log, id, 2, 2));
        assertEquals(List.of("d1"), details(log, id, 4, 2));
        assertEquals(List.of("d4", "d3", "d2", "d1"), details(log, id, 1, Integer.MAX_VALUE));
        assertTrue(details(log, id, 5, 2).isEmpty());
        assertTrue(details(log, id, 100, 2).isEmpty());
        assertTrue(details(log, id, 0, 0).isEmpty());
    }

    @Test
    @DisplayName("unknown players have no records")
    void unknownPlayer() {
        InMemoryViolationLog log = new InMemoryViolationLog(5, 5);
        assertTrue(details(log, UUID.randomUUID(), 0, 10).isEmpty());
        assertEquals(0, log.trackedPlayers());
    }

    @Test
    @DisplayName("negative offset or limit fails the future instead of throwing")
    void negativeArguments() {
        InMemoryViolationLog log = new InMemoryViolationLog(5, 5);
        UUID id = UUID.randomUUID();
        log.append(record(id, 1));
        CompletableFuture<List<ViolationRecord>> negativeOffset = log.query(id, -1, 5);
        CompletableFuture<List<ViolationRecord>> negativeLimit = log.query(id, 0, -1);
        assertTrue(negativeOffset.isCompletedExceptionally());
        assertTrue(negativeLimit.isCompletedExceptionally());
        ExecutionException error = assertThrows(ExecutionException.class, negativeOffset::get);
        assertInstanceOf(IllegalArgumentException.class, error.getCause());
    }

    @Test
    @DisplayName("results are immutable snapshots")
    void immutableSnapshot() {
        InMemoryViolationLog log = new InMemoryViolationLog(5, 5);
        UUID id = UUID.randomUUID();
        log.append(record(id, 1));
        List<ViolationRecord> snapshot = log.query(id, 0, 10).join();
        log.append(record(id, 2));
        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add(record(id, 3)));
    }

    @Test
    @DisplayName("non-positive limits are rejected")
    void constructorValidation() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryViolationLog(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryViolationLog(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryViolationLog(-1, -1));
    }

    @Test
    @DisplayName("concurrent appends and queries do not corrupt the log")
    void concurrency() throws Exception {
        InMemoryViolationLog log = new InMemoryViolationLog(50, 8);
        UUID[] ids = new UUID[16];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = UUID.randomUUID();
        }
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            final int offset = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 2000; i++) {
                    UUID id = ids[(i + offset) % ids.length];
                    log.append(record(id, i));
                    log.query(id, 0, 5).join();
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        assertTrue(log.trackedPlayers() <= 8);
    }

    @Test
    @DisplayName("ViolationRecord normalises null details and client version")
    void recordNormalisation() {
        ViolationRecord record = new ViolationRecord(UUID.randomUUID(), "P", "c", CheckCategory.PACKET, 1, null, -1, null, 0);
        assertEquals("", record.details());
        assertEquals("unknown", record.clientVersion());
        assertThrows(NullPointerException.class,
                () -> new ViolationRecord(null, "P", "c", CheckCategory.PACKET, 1, "", -1, "v", 0));
    }
}
