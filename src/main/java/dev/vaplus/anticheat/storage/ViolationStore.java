package dev.vaplus.anticheat.storage;

import dev.vaplus.anticheat.violation.ViolationRecord;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Durable storage SPI behind {@link PersistentViolationLog}. All methods are called from the single
 * writer thread only, so implementations need no synchronisation and may block (JDBC).
 * Failures are reported as {@link StoreException}; the caller decides about retries and degradation.
 */
public interface ViolationStore {

    /** Connects and creates or migrates the schema. Called once, before any other method. */
    void open() throws StoreException;

    /** Inserts all records in one transaction (all or nothing). */
    void insert(List<ViolationRecord> records) throws StoreException;

    /** Records of a player, newest first. */
    List<ViolationRecord> recent(UUID playerId, int offset, int limit) throws StoreException;

    /**
     * Number of records per check id since {@code sinceMillis} (inclusive).
     *
     * @param playerId a player, or {@code null} for all players
     */
    Map<String, Long> countsByCheck(UUID playerId, long sinceMillis) throws StoreException;

    /** Deletes records older than {@code cutoffMillis}; returns the number of deleted rows. */
    int deleteOlderThan(long cutoffMillis) throws StoreException;

    /** Releases the connection. Must not throw. */
    void close();

    /** Short description for log lines; must never contain credentials. */
    String describe();

    /** Failure of the storage backend. */
    final class StoreException extends Exception {
        private static final long serialVersionUID = 1L;

        public StoreException(String message) {
            super(message);
        }

        public StoreException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
