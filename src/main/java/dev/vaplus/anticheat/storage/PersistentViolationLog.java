package dev.vaplus.anticheat.storage;

import dev.vaplus.anticheat.violation.InMemoryViolationLog;
import dev.vaplus.anticheat.violation.ViolationLog;
import dev.vaplus.anticheat.violation.ViolationRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@link ViolationLog} that keeps the {@link InMemoryViolationLog} as the fast cache of the current
 * session and persists every record through a {@link ViolationStore} on one dedicated writer thread.
 *
 * <ul>
 *   <li>{@link #append} (packet/tick threads) only touches the cache and a bounded queue; when the queue
 *       is full the oldest pending record is dropped and counted. It never blocks.</li>
 *   <li>The writer thread opens the store, writes batches of up to {@code batchSize} records per
 *       transaction (every {@value #FLUSH_INTERVAL_MILLIS} ms, or as soon as a batch is full), applies
 *       retention on startup and once per day, and serves reads. Because reads run on the same thread
 *       after a flush, a query sees every record appended before it (except dropped ones).</li>
 *   <li>A failing database never breaks the plugin: a failed open degrades to cache-only with one
 *       warning, failed batches are retried {@value #MAX_ATTEMPTS} times and then discarded, failed
 *       reads fall back to the cache. Warnings are rate limited.</li>
 * </ul>
 */
public final class PersistentViolationLog implements ViolationLog {

    static final long FLUSH_INTERVAL_MILLIS = 1_000L;
    static final long RETENTION_INTERVAL_MILLIS = TimeUnit.DAYS.toMillis(1);
    static final int MAX_ATTEMPTS = 3;
    static final int MAX_PENDING_READS = 64;
    static final long WARN_INTERVAL_MILLIS = 60_000L;
    static final long DEFAULT_CLOSE_TIMEOUT_MILLIS = 5_000L;

    /** Lifecycle of the backend. */
    public enum State {
        /** Store is being opened; appends are queued. */
        STARTING,
        /** Store is open and written to. */
        ACTIVE,
        /** Store could not be opened; cache only. */
        FAILED,
        /** {@link #close()} was called. */
        CLOSED
    }

    /** Counters for diagnostics. */
    public record Stats(State state, int queued, long written, long dropped, long discarded) {
    }

    private final InMemoryViolationLog cache;
    private final ViolationStore store;
    private final ArrayBlockingQueue<ViolationRecord> queue;
    private final int batchSize;
    private final int retentionDays;
    private final Logger logger;
    private final LongSupplier clock;
    private final long closeTimeoutMillis;
    private final ScheduledThreadPoolExecutor executor;
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private final AtomicInteger pendingReads = new AtomicInteger();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder written = new LongAdder();
    private final LongAdder discarded = new LongAdder();
    private final Object lifecycle = new Object();
    private volatile State state = State.STARTING;
    private volatile boolean closing;
    private volatile boolean storeClosed;

    // Writer thread only.
    private List<ViolationRecord> retryBatch;
    private int attempts;
    private boolean failing;
    private long lastWarnMillis = Long.MIN_VALUE;

    PersistentViolationLog(InMemoryViolationLog cache, ViolationStore store, StorageSettings settings, Logger logger,
                           LongSupplier clock, long closeTimeoutMillis) {
        this.cache = cache;
        this.store = store;
        this.queue = new ArrayBlockingQueue<>(settings.queueSize());
        this.batchSize = settings.batchSize();
        this.retentionDays = settings.retentionDays();
        this.logger = logger;
        this.clock = clock;
        this.closeTimeoutMillis = closeTimeoutMillis;
        this.executor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "VAPlus-ViolationStorage");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    /** Creates the log and starts opening the store on the writer thread; returns immediately. */
    public static PersistentViolationLog start(InMemoryViolationLog cache, ViolationStore store,
                                               StorageSettings settings, Logger logger) {
        PersistentViolationLog log = new PersistentViolationLog(cache, store, settings, logger,
                System::currentTimeMillis, DEFAULT_CLOSE_TIMEOUT_MILLIS);
        log.start();
        return log;
    }

    void start() {
        executor.execute(guarded(this::openStore));
        executor.scheduleWithFixedDelay(guarded(this::flush), FLUSH_INTERVAL_MILLIS, FLUSH_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS);
        if (retentionDays > 0) {
            executor.scheduleWithFixedDelay(guarded(this::applyRetention), RETENTION_INTERVAL_MILLIS,
                    RETENTION_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    // ---------------------------------------------------------------- producer side (any thread)

    @Override
    public void append(ViolationRecord record) {
        cache.append(record);
        if (closing || state == State.FAILED) {
            return;
        }
        while (!queue.offer(record)) {
            if (queue.poll() != null) {
                dropped.increment();
            }
        }
        if (queue.size() >= batchSize && flushScheduled.compareAndSet(false, true)) {
            try {
                executor.execute(guarded(this::flush));
            } catch (RejectedExecutionException shuttingDown) {
                flushScheduled.set(false);
            }
        }
    }

    @Override
    public CompletableFuture<List<ViolationRecord>> query(UUID playerId, int offset, int limit) {
        if (offset < 0 || limit < 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("offset and limit must not be negative"));
        }
        List<ViolationRecord> cached = cache.query(playerId, offset, limit).join();
        // The cache holds the newest records of this session, so a full page from it is exact.
        if (cached.size() == limit || !readable()) {
            return CompletableFuture.completedFuture(cached);
        }
        if (pendingReads.incrementAndGet() > MAX_PENDING_READS) {
            pendingReads.decrementAndGet();
            return CompletableFuture.completedFuture(cached);
        }
        CompletableFuture<List<ViolationRecord>> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    flush();
                    if (state != State.ACTIVE) {
                        future.complete(cached);
                        return;
                    }
                    future.complete(List.copyOf(store.recent(playerId, offset, limit)));
                } catch (ViolationStore.StoreException | RuntimeException exception) {
                    warn("Violation storage read failed, showing this session's records only: "
                            + exception.getMessage(), null);
                    future.complete(cached);
                } finally {
                    pendingReads.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            pendingReads.decrementAndGet();
            future.complete(cached);
        }
        return future;
    }

    /**
     * Number of stored records per check id since {@code sinceMillis}, most frequent first. Runs on the
     * writer thread; fails when the store is not available (memory fallback has no global counts).
     *
     * @param playerId a player, or {@code null} for all players
     */
    public CompletableFuture<Map<String, Long>> countsByCheck(UUID playerId, long sinceMillis) {
        if (!readable()) {
            return CompletableFuture.failedFuture(new IllegalStateException("violation storage is not available"));
        }
        if (pendingReads.incrementAndGet() > MAX_PENDING_READS) {
            pendingReads.decrementAndGet();
            return CompletableFuture.failedFuture(new IllegalStateException("violation storage is busy"));
        }
        CompletableFuture<Map<String, Long>> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    flush();
                    if (state != State.ACTIVE) {
                        future.completeExceptionally(new IllegalStateException("violation storage is not available"));
                        return;
                    }
                    future.complete(Map.copyOf(store.countsByCheck(playerId, sinceMillis)));
                } catch (ViolationStore.StoreException | RuntimeException exception) {
                    future.completeExceptionally(exception);
                } finally {
                    pendingReads.decrementAndGet();
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            pendingReads.decrementAndGet();
            future.completeExceptionally(new IllegalStateException("violation storage is closed"));
        }
        return future;
    }

    public Stats stats() {
        return new Stats(state, queue.size(), written.sum(), dropped.sum(), discarded.sum());
    }

    public State state() {
        return state;
    }

    private boolean readable() {
        State current = state;
        return !closing && (current == State.ACTIVE || current == State.STARTING);
    }

    // ---------------------------------------------------------------- writer thread

    private void openStore() {
        try {
            store.open();
            state = State.ACTIVE;
            logger.info("Violation storage: " + store.describe() + " (retention "
                    + (retentionDays > 0 ? retentionDays + " days" : "unlimited") + ")");
            applyRetention();
            flush();
        } catch (ViolationStore.StoreException | RuntimeException exception) {
            state = State.FAILED;
            queue.clear();
            closeStore();
            logger.warning("Violation storage " + store.describe() + " is unavailable (" + exception.getMessage()
                    + "); violations are kept in memory only this session.");
        }
    }

    private void applyRetention() {
        if (retentionDays <= 0 || state != State.ACTIVE) {
            return;
        }
        long cutoff = clock.getAsLong() - TimeUnit.DAYS.toMillis(retentionDays);
        try {
            int deleted = store.deleteOlderThan(cutoff);
            if (deleted > 0) {
                logger.info("Violation storage: deleted " + deleted + " records older than " + retentionDays + " days");
            }
        } catch (ViolationStore.StoreException exception) {
            warn("Violation storage retention failed: " + exception.getMessage(), null);
        }
    }

    /** Writes queued records; stops at the first failure (retried next interval). */
    void flush() {
        flushScheduled.set(false);
        if (state != State.ACTIVE) {
            return;
        }
        // Bounded per call so producers that outpace the database cannot pin the thread forever.
        int rounds = (queue.remainingCapacity() + queue.size()) / batchSize + 2;
        for (int round = 0; round < rounds; round++) {
            List<ViolationRecord> batch = retryBatch;
            if (batch == null) {
                batch = new ArrayList<>(Math.min(batchSize, queue.size()));
                queue.drainTo(batch, batchSize);
                if (batch.isEmpty()) {
                    return;
                }
            }
            try {
                store.insert(batch);
                written.add(batch.size());
                retryBatch = null;
                attempts = 0;
                if (failing) {
                    failing = false;
                    logger.info("Violation storage recovered; writing again.");
                }
            } catch (ViolationStore.StoreException | RuntimeException exception) {
                failing = true;
                attempts++;
                if (attempts >= MAX_ATTEMPTS) {
                    discarded.add(batch.size());
                    retryBatch = null;
                    attempts = 0;
                    warn("Violation storage write failed " + MAX_ATTEMPTS + " times, discarded " + batch.size()
                            + " records (still shown from memory this session): " + exception.getMessage(), null);
                } else {
                    retryBatch = batch;
                    warn("Violation storage write failed, will retry: " + exception.getMessage(), null);
                }
                return;
            }
        }
    }

    private void drainForClose() {
        // Each failing round consumes one attempt, so this ends after a bounded number of rounds.
        long guard = (long) queue.size() * MAX_ATTEMPTS + MAX_ATTEMPTS + 2;
        while (state == State.ACTIVE && (retryBatch != null || !queue.isEmpty()) && guard-- > 0) {
            flush();
        }
    }

    private void closeStore() {
        synchronized (lifecycle) {
            if (!storeClosed) {
                storeClosed = true;
                store.close();
            }
        }
    }

    private void warn(String message, Throwable cause) {
        long now = clock.getAsLong();
        if (lastWarnMillis != Long.MIN_VALUE && now - lastWarnMillis < WARN_INTERVAL_MILLIS) {
            return;
        }
        lastWarnMillis = now;
        logger.log(Level.WARNING, message, cause);
    }

    private Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable unexpected) {
                logger.log(Level.SEVERE, "Unexpected error in the violation storage thread", unexpected);
            }
        };
    }

    // ---------------------------------------------------------------- shutdown

    /**
     * Stops accepting writes, flushes what is queued and closes the store. Waits at most the close
     * timeout (plus one second for an interrupted write); records still queued after that are lost and
     * reported.
     */
    @Override
    public void close() {
        synchronized (lifecycle) {
            if (closing) {
                return;
            }
            closing = true;
        }
        try {
            executor.execute(guarded(() -> {
                drainForClose();
                closeStore();
            }));
        } catch (RejectedExecutionException alreadyStopped) {
            // nothing to flush on
        }
        executor.shutdown();
        boolean finished = awaitQuietly(closeTimeoutMillis);
        if (!finished) {
            executor.shutdownNow();
            awaitQuietly(1_000L);
        }
        int unsaved = queue.size();
        closeStore();
        State previous = state;
        state = State.CLOSED;
        if (!finished) {
            logger.warning("Violation storage did not finish within " + closeTimeoutMillis + " ms; about "
                    + unsaved + " queued violations were not saved.");
        }
        long droppedTotal = dropped.sum();
        long discardedTotal = discarded.sum();
        if (previous == State.ACTIVE && (droppedTotal > 0 || discardedTotal > 0)) {
            logger.warning("Violation storage this session: " + droppedTotal + " records dropped (queue full), "
                    + discardedTotal + " discarded after failed writes.");
        }
        queue.clear();
    }

    private boolean awaitQuietly(long millis) {
        try {
            return executor.awaitTermination(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return executor.isTerminated();
        }
    }
}
