package dev.vaplus.anticheat.storage;

import java.util.Locale;
import java.util.Objects;

/**
 * The {@code storage:} section of config.yml. Read once on enable; changes require a restart.
 *
 * @param type          backend of the violation log
 * @param file          SQLite file name, relative to the plugin data folder
 * @param url           JDBC URL for {@link Type#MYSQL} (MySQL or MariaDB)
 * @param user          database user for {@link Type#MYSQL}; may be empty
 * @param password      database password for {@link Type#MYSQL}; may be empty, never logged
 * @param retentionDays records older than this are deleted on startup and once per day; 0 keeps them
 * @param queueSize     pending writes kept before the oldest one is dropped
 * @param batchSize     records written per transaction
 */
public record StorageSettings(
        Type type,
        String file,
        String url,
        String user,
        String password,
        int retentionDays,
        int queueSize,
        int batchSize) {

    public static final int MAX_RETENTION_DAYS = 36_500;
    public static final int MIN_QUEUE_SIZE = 16;
    public static final int MAX_QUEUE_SIZE = 1_000_000;
    public static final int MAX_BATCH_SIZE = 10_000;

    /** Storage backend. */
    public enum Type {
        MEMORY, SQLITE, MYSQL;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Resolves a configuration key ({@code mariadb} is an alias of {@code mysql}), or {@code null}. */
        public static Type fromKey(String key) {
            if (key == null) {
                return null;
            }
            String normalized = key.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals("mariadb")) {
                return MYSQL;
            }
            for (Type type : values()) {
                if (type.key().equals(normalized)) {
                    return type;
                }
            }
            return null;
        }
    }

    public StorageSettings {
        Objects.requireNonNull(type, "type");
        file = file != null ? file : "";
        url = url != null ? url : "";
        user = user != null ? user : "";
        password = password != null ? password : "";
        if (retentionDays < 0 || retentionDays > MAX_RETENTION_DAYS) {
            throw new IllegalArgumentException("retentionDays out of range");
        }
        if (queueSize < MIN_QUEUE_SIZE || queueSize > MAX_QUEUE_SIZE) {
            throw new IllegalArgumentException("queueSize out of range");
        }
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE || batchSize > queueSize) {
            throw new IllegalArgumentException("batchSize out of range");
        }
    }

    public static StorageSettings defaults() {
        return new StorageSettings(Type.SQLITE, "violations.db", "", "", "", 30, 10_000, 200);
    }

    /** Never prints the password (and not the URL, which may embed credentials). */
    @Override
    public String toString() {
        return "StorageSettings[type=" + type.key() + ", file=" + file + ", url=" + (url.isEmpty() ? "" : "<set>")
                + ", user=" + (user.isEmpty() ? "" : "<set>") + ", password=" + (password.isEmpty() ? "" : "<hidden>")
                + ", retentionDays=" + retentionDays + ", queueSize=" + queueSize + ", batchSize=" + batchSize + "]";
    }
}
