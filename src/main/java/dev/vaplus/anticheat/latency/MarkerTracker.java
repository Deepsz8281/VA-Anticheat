package dev.vaplus.anticheat.latency;

import dev.vaplus.anticheat.player.PacketTransport;
import dev.vaplus.anticheat.util.TimeSource;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Ping/pong based {@link AckTracker}; original implementation of the acknowledgement-marker concept.
 *
 * <p>Wire ids live in the lower quarter of the signed-short range ({@code -32768 … -16385}): ids
 * must fit a short so ViaBackwards can map pings onto window confirmations for pre-1.17 clients
 * (requires its {@code handle-pings-as-inv-acknowledgements} option), and the fixed sub-range keeps
 * them apart from most other plugins' pings. The 14-bit sequence wraps every 16384 sent markers, so
 * a wire id identifies exactly one marker among the currently outstanding ones — but it is
 * <em>reused</em>: a recorded (stale) pong from a previous rotation aliases the pending marker with
 * the same low bits, and the ids are not authenticated, so pending ids are guessable in principle.
 * The ack timeout bounds how long any marker stays pending and thus the exposure window.
 *
 * <p>Pending markers and scheduled tasks live in primitive arrays so the per-tick heartbeat is
 * allocation-free. Owner-thread confined, except the volatile RTT/pending snapshots read by commands.
 */
public final class MarkerTracker implements AckTracker {

    private static final int SEQUENCE_BITS = 14;
    private static final int SEQUENCE_MASK = (1 << SEQUENCE_BITS) - 1;
    private static final int ID_BASE = Short.MIN_VALUE;
    /** Must stay below 2^SEQUENCE_BITS so a wire id identifies exactly one pending marker. */
    static final int MAX_PENDING = 1 << (SEQUENCE_BITS - 1);
    /** Hard cap on scheduled tasks; beyond it the oldest task is committed early (degraded, not unbounded). */
    static final int MAX_TASKS = 1 << 17;
    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final PacketTransport transport;
    private final TimeSource time;
    private final Logger logger;

    private long nextSequence = 1L;
    private long lastSent;
    private long lastAcked;
    private long lastAckedSentAt;

    private long[] pendingSequence = new long[64];
    private long[] pendingSentAt = new long[64];
    private int pendingHead;
    private int pendingSize;

    /** Sorted by marker; live entries are {@code [taskHead, taskEnd)}. */
    private long[] taskSequence = new long[16];
    private Runnable[] tasks = new Runnable[16];
    private int taskHead;
    private int taskEnd;

    private long anomalyCount;
    private volatile long rttNanos = -1L;
    private volatile long smoothedRttNanos = -1L;
    private volatile int pendingSnapshot;

    public MarkerTracker(PacketTransport transport, TimeSource time, Logger logger) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.time = Objects.requireNonNull(time, "time");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Whether a ping/pong id lies in this tracker's id range. */
    public static boolean isMarkerId(int id) {
        return id >= ID_BASE && id <= ID_BASE + SEQUENCE_MASK;
    }

    public static int toWireId(long sequence) {
        return ID_BASE + (int) (sequence & SEQUENCE_MASK);
    }

    @Override
    public long sendMarker() {
        long sequence = nextSequence;
        if (!transport.sendMarker(toWireId(sequence))) {
            // Nothing could be sent (e.g. configuration phase). Hand out the handle the next real
            // marker will get: work attached to it resolves only once later packets were processed.
            return sequence;
        }
        nextSequence++;
        enqueuePending(sequence, time.nanoTime());
        lastSent = sequence;
        pendingSnapshot = pendingSize;
        return sequence;
    }

    /**
     * Handles an echoed id.
     *
     * <p>The id is matched only against currently pending markers, so duplicate, stale or never-sent
     * ids are ignored and a client cannot force-acknowledge markers it has never received. Two
     * caveats: after a full 16384-marker rotation a <em>replayed</em> pong aliases the currently
     * pending marker with the same low bits (acknowledged as if it were genuine), and the ids are
     * not authenticated, so a pending id is guessable in principle. The ack timeout bounds both.
     *
     * @return {@code true} if the id belongs to our marker range (the caller should then cancel the
     *         packet so the server and other plugins never see it)
     */
    public boolean onPong(int id) {
        if (!isMarkerId(id)) {
            return false;
        }
        int low = id - ID_BASE;
        int position = -1;
        for (int i = 0; i < pendingSize; i++) {
            if ((pendingSequence[(pendingHead + i) & (pendingSequence.length - 1)] & SEQUENCE_MASK) == low) {
                position = i;
                break;
            }
        }
        if (position < 0) {
            // Duplicate, stale or never-sent id that matches no pending marker. Ignoring it keeps a
            // client from force-acknowledging markers it has not received yet. (It does NOT protect
            // against replayed pongs after a full 16384-marker wire-id rotation: those alias the
            // pending marker with the same low bits — see the class javadoc.)
            anomalyCount++;
            return true;
        }
        long now = time.nanoTime();
        for (int i = 0; i <= position; i++) {
            // Re-read the arrays every iteration: an acknowledgement task may send a marker and grow them.
            int index = pendingHead;
            long sequence = pendingSequence[index];
            long sentAt = pendingSentAt[index];
            pendingHead = (pendingHead + 1) & (pendingSequence.length - 1);
            pendingSize--;
            lastAckedSentAt = sentAt;
            if (i < position) {
                // Skipped marker: vanilla clients answer strictly in order, so this indicates a
                // modified client. The later echo still proves the earlier packets were processed.
                anomalyCount++;
                acknowledge(sequence, -1L);
            } else {
                acknowledge(sequence, now - sentAt);
            }
        }
        pendingSnapshot = pendingSize;
        return true;
    }

    @Override
    public void whenAcked(long marker, Runnable task) {
        Objects.requireNonNull(task, "task");
        if (marker <= lastAcked) {
            runSafely(task);
            return;
        }
        if (taskEnd == tasks.length) {
            makeRoom();
        }
        // Keep the live range sorted by marker (stable for equal markers). Appends are the common case.
        int insertAt = taskEnd;
        while (insertAt > taskHead && taskSequence[insertAt - 1] > marker) {
            insertAt--;
        }
        if (insertAt < taskEnd) {
            System.arraycopy(taskSequence, insertAt, taskSequence, insertAt + 1, taskEnd - insertAt);
            System.arraycopy(tasks, insertAt, tasks, insertAt + 1, taskEnd - insertAt);
        }
        taskSequence[insertAt] = marker;
        tasks[insertAt] = task;
        taskEnd++;
    }

    @Override
    public void bracket(List<Runnable> tasksAfterSend, Runnable onFirstAck, Runnable onSecondAck) {
        long before = sendMarker();
        if (onFirstAck != null) {
            whenAcked(before, onFirstAck);
        }
        tasksAfterSend.add(() -> {
            long after = sendMarker();
            if (onSecondAck != null) {
                whenAcked(after, onSecondAck);
            }
        });
    }

    @Override
    public boolean isAcked(long marker) {
        return marker <= lastAcked;
    }

    @Override
    public long lastSentMarker() {
        return lastSent;
    }

    @Override
    public long lastAckedMarker() {
        return lastAcked;
    }

    /**
     * Send time ({@code TimeSource#nanoTime}) of the newest acknowledged marker, 0 before the first
     * acknowledgement. The client provably existed at this instant or later when it echoed the
     * marker, so this is a lower bound of the client's own clock (used by the timer check).
     * Owner thread.
     */
    public long lastAckedSentAtNanos() {
        return lastAckedSentAt;
    }

    @Override
    public int pendingCount() {
        return pendingSnapshot;
    }

    /** Scheduled acknowledgement tasks (owner thread). */
    public int scheduledTasks() {
        return taskEnd - taskHead;
    }

    @Override
    public long oldestPendingAgeNanos() {
        if (pendingSize == 0) {
            return 0L;
        }
        return time.nanoTime() - pendingSentAt[pendingHead];
    }

    @Override
    public long rttMillis() {
        long value = rttNanos;
        return value < 0 ? -1L : value / NANOS_PER_MILLI;
    }

    @Override
    public long smoothedRttMillis() {
        long value = smoothedRttNanos;
        return value < 0 ? -1L : value / NANOS_PER_MILLI;
    }

    @Override
    public long anomalies() {
        return anomalyCount;
    }

    private void acknowledge(long sequence, long rtt) {
        lastAcked = sequence;
        if (rtt >= 0) {
            rttNanos = rtt;
            long smoothed = smoothedRttNanos;
            // EWMA with alpha 1/8, the classic TCP smoothing factor.
            smoothedRttNanos = smoothed < 0 ? rtt : smoothed + (rtt - smoothed) / 8;
        }
        // Fields are re-read every iteration because a task may schedule further tasks.
        while (taskHead < taskEnd && taskSequence[taskHead] <= sequence) {
            Runnable task = tasks[taskHead];
            tasks[taskHead] = null;
            taskHead++;
            runSafely(task);
        }
        if (taskHead == taskEnd) {
            taskHead = 0;
            taskEnd = 0;
        }
    }

    /** Called when the task array is full at its end: compact, grow, or as last resort commit early. */
    private void makeRoom() {
        int live = taskEnd - taskHead;
        if (taskHead > 0) {
            System.arraycopy(taskSequence, taskHead, taskSequence, 0, live);
            System.arraycopy(tasks, taskHead, tasks, 0, live);
            Arrays.fill(tasks, live, taskEnd, null);
            taskHead = 0;
            taskEnd = live;
            return;
        }
        if (tasks.length < MAX_TASKS) {
            taskSequence = Arrays.copyOf(taskSequence, tasks.length * 2);
            tasks = Arrays.copyOf(tasks, tasks.length * 2);
            return;
        }
        // The client has left a huge backlog unanswered; the ack timeout disconnects it soon. Commit
        // the oldest change now instead of growing without bound.
        anomalyCount++;
        Runnable oldest = tasks[0];
        System.arraycopy(taskSequence, 1, taskSequence, 0, live - 1);
        System.arraycopy(tasks, 1, tasks, 0, live - 1);
        tasks[live - 1] = null;
        taskEnd = live - 1;
        runSafely(oldest);
    }

    private void enqueuePending(long sequence, long sentAt) {
        if (pendingSize == pendingSequence.length) {
            if (pendingSequence.length >= MAX_PENDING) {
                // Client has not answered for a very long time; drop the oldest marker. The ack
                // timeout disconnects such clients long before this matters.
                pendingHead = (pendingHead + 1) & (pendingSequence.length - 1);
                pendingSize--;
                anomalyCount++;
            } else {
                growPending();
            }
        }
        int tail = (pendingHead + pendingSize) & (pendingSequence.length - 1);
        pendingSequence[tail] = sequence;
        pendingSentAt[tail] = sentAt;
        pendingSize++;
    }

    private void growPending() {
        int capacity = pendingSequence.length;
        long[] sequences = new long[capacity * 2];
        long[] sentAt = new long[capacity * 2];
        for (int i = 0; i < pendingSize; i++) {
            int index = (pendingHead + i) & (capacity - 1);
            sequences[i] = pendingSequence[index];
            sentAt[i] = pendingSentAt[index];
        }
        pendingSequence = sequences;
        pendingSentAt = sentAt;
        pendingHead = 0;
    }

    private void runSafely(Runnable task) {
        try {
            task.run();
        } catch (RuntimeException exception) {
            logger.log(Level.WARNING, "Acknowledgement task failed", exception);
        }
    }
}
