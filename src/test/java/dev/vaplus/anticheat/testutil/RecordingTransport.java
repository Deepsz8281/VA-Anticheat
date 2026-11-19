package dev.vaplus.anticheat.testutil;

import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import dev.vaplus.anticheat.player.PacketTransport;

import java.util.ArrayList;
import java.util.List;

/** Fake connection: records everything sent and runs owner-thread tasks inline. */
public final class RecordingTransport implements PacketTransport {

    private final List<Integer> markerIds = new ArrayList<>();
    private final List<PacketWrapper<?>> sent = new ArrayList<>();
    private int ownerThreadTasks;
    private int closeCount;
    private boolean acceptingMarkers = true;

    @Override
    public boolean sendMarker(int markerId) {
        if (!acceptingMarkers) {
            return false;
        }
        markerIds.add(markerId);
        return true;
    }

    /** Simulates a connection that cannot carry play packets (configuration phase). */
    public void acceptMarkers(boolean accepting) {
        this.acceptingMarkers = accepting;
    }

    @Override
    public void send(PacketWrapper<?> packet) {
        sent.add(packet);
    }

    @Override
    public void runOnOwnerThread(Runnable task) {
        ownerThreadTasks++;
        task.run();
    }

    @Override
    public void close() {
        closeCount++;
    }

    /** Wire ids of all markers sent, in order. */
    public List<Integer> markerIds() {
        return markerIds;
    }

    public int lastMarkerId() {
        return markerIds.get(markerIds.size() - 1);
    }

    public List<PacketWrapper<?>> sent() {
        return sent;
    }

    public int ownerThreadTasks() {
        return ownerThreadTasks;
    }

    public int closeCount() {
        return closeCount;
    }

    public boolean closed() {
        return closeCount > 0;
    }
}
