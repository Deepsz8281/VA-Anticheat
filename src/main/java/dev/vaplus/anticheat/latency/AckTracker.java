package dev.vaplus.anticheat.latency;

import java.util.List;

/**
 * Latency compensation transport. A <em>marker</em> is a ping the client must echo in order; once
 * it is echoed we know the client processed every packet sent before it.
 *
 * <p>Markers are identified by a monotonically increasing {@code long} handle. All methods except
 * the RTT getters must be called on the player's owner thread.
 *
 * <p>Two-marker bracketing ({@link #bracket}) is how server state changes are compensated: when the
 * first marker is acknowledged the client <em>may</em> already apply the change, after the second
 * marker it definitely has. Between the two acknowledgements both states must be tolerated.
 */
public interface AckTracker {

    /**
     * Sends a marker now and returns its handle. If the connection cannot carry markers at the
     * moment, nothing is sent and the newest existing marker is returned instead.
     */
    long sendMarker();

    /** Runs {@code task} on the owner thread once {@code marker} is acknowledged (immediately if it already is). */
    void whenAcked(long marker, Runnable task);

    boolean isAcked(long marker);

    long lastSentMarker();

    long lastAckedMarker();

    /** Markers sent but not yet acknowledged. */
    int pendingCount();

    /** Age of the oldest unacknowledged marker, 0 if none is pending. */
    long oldestPendingAgeNanos();

    /** Last measured round-trip time in milliseconds, -1 before the first measurement. Any thread. */
    long rttMillis();

    /** Smoothed round-trip time in milliseconds, -1 before the first measurement. Any thread. */
    long smoothedRttMillis();

    /** Echoes the client sent for unknown, duplicate or out-of-order markers. */
    long anomalies();

    /**
     * Brackets an outbound packet with two markers.
     *
     * @param tasksAfterSend the packet event's after-send task list
     *                       ({@code PacketSendEvent#getTasksAfterSend()}); the second marker is sent from there
     * @param onFirstAck     runs when the marker sent before the packet is acknowledged; may be {@code null}
     * @param onSecondAck    runs when the marker sent after the packet is acknowledged; may be {@code null}
     */
    void bracket(List<Runnable> tasksAfterSend, Runnable onFirstAck, Runnable onSecondAck);
}
