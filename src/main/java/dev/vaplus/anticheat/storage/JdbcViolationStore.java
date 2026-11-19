package dev.vaplus.anticheat.storage;

import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.violation.ViolationRecord;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JDBC implementation of {@link ViolationStore} for SQLite and MySQL/MariaDB. Single connection, used
 * by the writer thread only; it is reopened lazily after a failure. Every value goes through a
 * {@link PreparedStatement}; the only SQL built from strings are the constant schema statements.
 */
public final class JdbcViolationStore implements ViolationStore {

    /** Latest schema version this code writes. */
    public static final int SCHEMA_VERSION = 1;

    static final String TABLE = "vaplus_violations";
    static final String VERSION_TABLE = "vaplus_schema_version";
    static final int MAX_NAME = 64;
    static final int MAX_CHECK = 64;
    static final int MAX_CATEGORY = 32;
    static final int MAX_CLIENT = 64;
    static final int MAX_DETAILS = 4000;

    /** Opens a new connection; implementations own the URL and credentials. */
    @FunctionalInterface
    public interface ConnectionFactory {
        Connection connect() throws SQLException;
    }

    /** SQL differences between the supported databases. */
    public enum Dialect {
        SQLITE(List.of(
                "PRAGMA journal_mode=WAL",
                "PRAGMA synchronous=NORMAL",
                "PRAGMA busy_timeout=5000"),
                List.of(List.of(
                        "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                                + "player_uuid CHAR(36) NOT NULL, "
                                + "player_name VARCHAR(64) NOT NULL, "
                                + "check_id VARCHAR(64) NOT NULL, "
                                + "category VARCHAR(32) NOT NULL, "
                                + "vl DOUBLE NOT NULL, "
                                + "details TEXT NOT NULL, "
                                + "ping BIGINT NOT NULL, "
                                + "client_version VARCHAR(64) NOT NULL, "
                                + "created_at BIGINT NOT NULL)",
                        "CREATE INDEX IF NOT EXISTS " + TABLE + "_player_time ON " + TABLE + " (player_uuid, created_at)",
                        "CREATE INDEX IF NOT EXISTS " + TABLE + "_check_time ON " + TABLE + " (check_id, created_at)",
                        "CREATE INDEX IF NOT EXISTS " + TABLE + "_time ON " + TABLE + " (created_at)"))),
        MYSQL(List.of(),
                List.of(List.of(
                        "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                                + "player_uuid CHAR(36) NOT NULL, "
                                + "player_name VARCHAR(64) NOT NULL, "
                                + "check_id VARCHAR(64) NOT NULL, "
                                + "category VARCHAR(32) NOT NULL, "
                                + "vl DOUBLE NOT NULL, "
                                + "details TEXT NOT NULL, "
                                + "ping BIGINT NOT NULL, "
                                + "client_version VARCHAR(64) NOT NULL, "
                                + "created_at BIGINT NOT NULL, "
                                + "INDEX " + TABLE + "_player_time (player_uuid, created_at), "
                                + "INDEX " + TABLE + "_check_time (check_id, created_at), "
                                + "INDEX " + TABLE + "_time (created_at)"
                                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")));

        private final List<String> connectionSetup;
        /** Index i holds the statements migrating from version i to i + 1. */
        private final List<List<String>> migrations;

        Dialect(List<String> connectionSetup, List<List<String>> migrations) {
            this.connectionSetup = connectionSetup;
            this.migrations = migrations;
        }
    }

    private static final String INSERT = "INSERT INTO " + TABLE
            + " (player_uuid, player_name, check_id, category, vl, details, ping, client_version, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String SELECT_RECENT = "SELECT player_uuid, player_name, check_id, category, vl, details,"
            + " ping, client_version, created_at FROM " + TABLE
            + " WHERE player_uuid = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
    private static final String COUNT_PLAYER = "SELECT check_id, COUNT(*) FROM " + TABLE
            + " WHERE player_uuid = ? AND created_at >= ? GROUP BY check_id ORDER BY COUNT(*) DESC, check_id";
    private static final String COUNT_ALL = "SELECT check_id, COUNT(*) FROM " + TABLE
            + " WHERE created_at >= ? GROUP BY check_id ORDER BY COUNT(*) DESC, check_id";
    private static final String DELETE_OLD = "DELETE FROM " + TABLE + " WHERE created_at < ?";

    private final ConnectionFactory factory;
    private final Dialect dialect;
    private final String description;
    private Connection connection;

    /**
     * @param description shown in log lines, e.g. the SQLite file name; must not contain credentials
     */
    public JdbcViolationStore(ConnectionFactory factory, Dialect dialect, String description) {
        this.factory = factory;
        this.dialect = dialect;
        this.description = description;
    }

    @Override
    public void open() throws StoreException {
        try {
            Connection c = connection();
            migrate(c);
        } catch (SQLException exception) {
            reset();
            throw new StoreException("cannot open " + description + ": " + exception.getMessage(), exception);
        } catch (StoreException refused) {
            // e.g. a newer schema version: do not keep the connection (and the file lock) open
            reset();
            throw refused;
        }
    }

    /** Current schema version stored in the database, 0 for a fresh database. */
    int schemaVersion() throws StoreException {
        try (Statement statement = connection().createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + VERSION_TABLE + " (version INTEGER NOT NULL)");
            return readVersion(statement);
        } catch (SQLException exception) {
            reset();
            throw new StoreException("cannot read the schema version: " + exception.getMessage(), exception);
        }
    }

    private void migrate(Connection c) throws SQLException, StoreException {
        int version;
        try (Statement statement = c.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + VERSION_TABLE + " (version INTEGER NOT NULL)");
            version = readVersion(statement);
        }
        if (version > SCHEMA_VERSION) {
            throw new StoreException("database schema version " + version + " is newer than this plugin supports ("
                    + SCHEMA_VERSION + "); refusing to write to it");
        }
        while (version < SCHEMA_VERSION) {
            List<String> steps = dialect.migrations.get(version);
            int next = version + 1;
            c.setAutoCommit(false);
            try (Statement statement = c.createStatement();
                 PreparedStatement bump = c.prepareStatement("INSERT INTO " + VERSION_TABLE + " (version) VALUES (?)")) {
                for (String step : steps) {
                    statement.executeUpdate(step);
                }
                bump.setInt(1, next);
                bump.executeUpdate();
                c.commit();
            } catch (SQLException exception) {
                rollbackQuietly(c);
                throw exception;
            } finally {
                c.setAutoCommit(true);
            }
            version = next;
        }
    }

    private static int readVersion(Statement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery("SELECT MAX(version) FROM " + VERSION_TABLE)) {
            return result.next() ? result.getInt(1) : 0;
        }
    }

    @Override
    public void insert(List<ViolationRecord> records) throws StoreException {
        if (records.isEmpty()) {
            return;
        }
        try {
            Connection c = connection();
            c.setAutoCommit(false);
            try (PreparedStatement statement = c.prepareStatement(INSERT)) {
                for (ViolationRecord record : records) {
                    statement.setString(1, record.playerId().toString());
                    statement.setString(2, truncate(record.playerName(), MAX_NAME));
                    statement.setString(3, truncate(record.checkId(), MAX_CHECK));
                    statement.setString(4, truncate(record.category().name(), MAX_CATEGORY));
                    statement.setDouble(5, finite(record.violationLevel()));
                    statement.setString(6, truncate(record.details(), MAX_DETAILS));
                    statement.setLong(7, record.pingMillis());
                    statement.setString(8, truncate(record.clientVersion(), MAX_CLIENT));
                    statement.setLong(9, record.timestampMillis());
                    statement.addBatch();
                }
                statement.executeBatch();
                c.commit();
            } catch (SQLException exception) {
                rollbackQuietly(c);
                throw exception;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            reset();
            throw new StoreException("insert of " + records.size() + " records failed: " + exception.getMessage(),
                    exception);
        }
    }

    @Override
    public List<ViolationRecord> recent(UUID playerId, int offset, int limit) throws StoreException {
        if (limit == 0) {
            return List.of();
        }
        try (PreparedStatement statement = connection().prepareStatement(SELECT_RECENT)) {
            statement.setString(1, playerId.toString());
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            List<ViolationRecord> records = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ViolationRecord record = read(result);
                    if (record != null) {
                        records.add(record);
                    }
                }
            }
            return records;
        } catch (SQLException exception) {
            reset();
            throw new StoreException("query failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public Map<String, Long> countsByCheck(UUID playerId, long sinceMillis) throws StoreException {
        try (PreparedStatement statement = connection().prepareStatement(playerId != null ? COUNT_PLAYER : COUNT_ALL)) {
            int index = 1;
            if (playerId != null) {
                statement.setString(index++, playerId.toString());
            }
            statement.setLong(index, sinceMillis);
            Map<String, Long> counts = new LinkedHashMap<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    counts.put(result.getString(1), result.getLong(2));
                }
            }
            return counts;
        } catch (SQLException exception) {
            reset();
            throw new StoreException("count query failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public int deleteOlderThan(long cutoffMillis) throws StoreException {
        try (PreparedStatement statement = connection().prepareStatement(DELETE_OLD)) {
            statement.setLong(1, cutoffMillis);
            return statement.executeUpdate();
        } catch (SQLException exception) {
            reset();
            throw new StoreException("retention delete failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public void close() {
        reset();
    }

    @Override
    public String describe() {
        return description;
    }

    private Connection connection() throws SQLException {
        if (connection == null || connection.isClosed()) {
            Connection c = factory.connect();
            if (c == null) {
                throw new SQLException("no JDBC driver accepted the URL");
            }
            try (Statement statement = c.createStatement()) {
                for (String setup : dialect.connectionSetup) {
                    statement.execute(setup);
                }
            } catch (SQLException exception) {
                try {
                    c.close();
                } catch (SQLException ignored) {
                    // already failing
                }
                throw exception;
            }
            connection = c;
        }
        return connection;
    }

    /** Drops the connection so the next call reconnects. */
    private void reset() {
        Connection c = connection;
        connection = null;
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // nothing left to do
            }
        }
    }

    private static void rollbackQuietly(Connection c) {
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // the connection is reset by the caller
        }
    }

    private static ViolationRecord read(ResultSet result) throws SQLException {
        UUID id;
        try {
            id = UUID.fromString(result.getString(1));
        } catch (IllegalArgumentException | NullPointerException corrupt) {
            return null;
        }
        String categoryName = result.getString(4);
        CheckCategory category = CheckCategory.fromKey(categoryName);
        if (category == null) {
            category = CheckCategory.PACKET;
        }
        String name = result.getString(2);
        String check = result.getString(3);
        return new ViolationRecord(id, name != null ? name : "?", check != null ? check : "?", category,
                result.getDouble(5), result.getString(6), result.getLong(7), result.getString(8), result.getLong(9));
    }

    static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        if (value.length() <= max) {
            return value;
        }
        int end = max;
        if (Character.isHighSurrogate(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    private static double finite(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }
}
