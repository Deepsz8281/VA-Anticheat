package dev.vaplus.anticheat.storage;

import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.violation.InMemoryViolationLog;
import dev.vaplus.anticheat.violation.ViolationRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PersistentViolationLog (writer thread over a ViolationStore)")
class PersistentViolationLogTest {

    private static final Logger LOGGER = Logger.getLogger("vaplus-test");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /** In-memory store; optionally failing. Accessed from the writer thread and the test thread. */
    private static final class FakeStore implements ViolationStore {
        final List<ViolationRecord> rows = Collections.synchronizedList(new ArrayList<>());
        volatile boolean failOpen;
        volatile int failInserts;
        volatile boolean closed;
        volatile long deletedBefore = -1;

        @Override
        public void open() throws StoreException {
            if (failOpen) {
                throw new StoreException("boom");
            }
        }

        @Override
        public void insert(List<ViolationRecord> records) throws StoreException {
            if (failInserts > 0) {
                failInserts--;
                throw new StoreException("write failed");
            }
            rows.addAll(records);
        }

        @Override
        public List<ViolationRecord> recent(UUID playerId, int offset, int limit) {
            List<ViolationRecord> mine = new ArrayList<>();
            synchronized (rows) {
                for (ViolationRecord row : rows) {
                    if (row.playerId().equals(playerId)) {
                        mine.add(0, row);
                    }
                }
            }
            return mine.subList(Math.min(offset, mine.size()), Math.min(offset + limit, mine.size()));
        }

        @Override
        public Map<String, Long> countsByCheck(UUID playerId, long sinceMillis) {
            Map<String, Long> counts = new LinkedHashMap<>();
            synchronized (rows) {
                for (ViolationRecord row : rows) {
                    counts.merge(row.checkId(), 1L, Long::sum);
                }
            }
            return counts;
        }

        @Override
        public int deleteOlderThan(long cutoffMillis) {
            deletedBefore = cutoffMillis;
            return 0;
        }

        @Override
        public void close() {
            closed = true;
        }

        @Override
        public String describe() {
            return "fake";
        }
    }

    private static ViolationRecord record(int n) {
        return new ViolationRecord(PLAYER, "Steve", "speed", CheckCategory.MOVEMENT, n, "n=" + n, 50L,
                "1.21.4", 1_000L + n);
    }

    private static StorageSettings settings(int queue, int batch) {
        return new StorageSettings(StorageSettings.Type.SQLITE, "x.db", "", "", "", 30, queue, batch);
    }

    @Test
    @DisplayName("records are written in batches; reads include records older than the session cache")
    void writesAndReads() throws Exception {
        FakeStore store = new FakeStore();
        InMemoryViolationLog cache = new InMemoryViolationLog(5, 10);
        PersistentViolationLog log = new PersistentViolationLog(cache, store, settings(100, 10), LOGGER,
                System::currentTimeMillis, 5_000L);
        log.start();
        for (int i = 0; i < 25; i++) {
            log.append(record(i));
        }
        List<ViolationRecord> page = log.query(PLAYER, 0, 20).get();
        assertEquals(20, page.size(), "the cache keeps 5, the store has all 25");
        assertEquals(24.0, page.get(0).violationLevel(), "newest first");
        assertEquals(Map.of("speed", 25L), log.countsByCheck(null, 0L).get());
        assertTrue(store.deletedBefore > 0, "retention ran on startup");
        log.close();
        assertTrue(store.closed);
        assertEquals(PersistentViolationLog.State.CLOSED, log.state());
        assertEquals(25L, log.stats().written());
    }

    @Test
    @DisplayName("a store that cannot open degrades to the session cache")
    void failedOpen() throws Exception {
        FakeStore store = new FakeStore();
        store.failOpen = true;
        InMemoryViolationLog cache = new InMemoryViolationLog(50, 10);
        PersistentViolationLog log = new PersistentViolationLog(cache, store, settings(100, 10), LOGGER,
                System::currentTimeMillis, 5_000L);
        log.start();
        log.append(record(1));
        assertEquals(1, log.query(PLAYER, 0, 10).get().size());
        for (int i = 0; i < 50 && log.state() == PersistentViolationLog.State.STARTING; i++) {
            Thread.sleep(10);
        }
        assertEquals(PersistentViolationLog.State.FAILED, log.state());
        log.append(record(2));
        assertEquals(2, log.query(PLAYER, 0, 10).get().size());
        log.close();
    }

    @Test
    @DisplayName("failed writes are retried, then discarded; close flushes the rest")
    void retries() throws Exception {
        FakeStore store = new FakeStore();
        store.failInserts = 1;
        PersistentViolationLog log = new PersistentViolationLog(new InMemoryViolationLog(50, 10), store,
                settings(100, 10), LOGGER, System::currentTimeMillis, 5_000L);
        log.start();
        for (int i = 0; i < 10; i++) {
            log.append(record(i));
        }
        log.close();
        assertEquals(10, store.rows.size(), "the batch was retried and written on close");
        assertEquals(0L, log.stats().discarded());
    }

    @Test
    @DisplayName("a full queue drops the oldest record instead of blocking the packet thread")
    void boundedQueue() {
        FakeStore store = new FakeStore();
        PersistentViolationLog log = new PersistentViolationLog(new InMemoryViolationLog(50, 10), store,
                settings(16, 16), LOGGER, System::currentTimeMillis, 5_000L);
        // not started: nothing drains the queue
        for (int i = 0; i < 40; i++) {
            log.append(record(i));
        }
        assertEquals(16, log.stats().queued());
        assertEquals(24L, log.stats().dropped());
        log.close();
    }

    @Test
    @DisplayName("memory type and missing drivers return the session cache itself")
    void factoryFallbacks() {
        InMemoryViolationLog cache = new InMemoryViolationLog(5, 5);
        StorageSettings memory = new StorageSettings(StorageSettings.Type.MEMORY, "x.db", "", "", "", 0, 100, 10);
        assertSame(cache, ViolationStorage.create(memory, Path.of("."), cache, LOGGER, name -> null));
        assertSame(cache, ViolationStorage.create(StorageSettings.defaults(), Path.of("."), cache, LOGGER,
                name -> null));
    }
}
