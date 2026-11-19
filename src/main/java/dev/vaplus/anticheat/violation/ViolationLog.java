package dev.vaplus.anticheat.violation;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Storage of violation records. {@link #append} is called on packet threads and must not block;
 * database backends queue the record and write asynchronously.
 */
public interface ViolationLog {

    void append(ViolationRecord record);

    /** Records of a player, newest first. */
    CompletableFuture<List<ViolationRecord>> query(UUID playerId, int offset, int limit);

    /** Flushes pending writes and releases resources. */
    default void close() {
    }
}
