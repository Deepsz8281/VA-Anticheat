package dev.vaplus.anticheat.storage;

import dev.vaplus.anticheat.violation.InMemoryViolationLog;
import dev.vaplus.anticheat.violation.ViolationLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Builds the {@link ViolationLog} selected by {@link StorageSettings}. JDBC drivers are not bundled
 * with the plugin: they are looked up at runtime (Paper ships SQLite and MySQL drivers - UNCONFIRMED
 * per server build). When the driver is missing the in-memory log is returned with one warning.
 * Opening the database itself happens on the writer thread, see {@link PersistentViolationLog}.
 */
public final class ViolationStorage {

    static final String SQLITE_DRIVER = "org.sqlite.JDBC";
    static final List<String> MYSQL_DRIVERS = List.of("com.mysql.cj.jdbc.Driver", "com.mysql.jdbc.Driver");
    static final List<String> MARIADB_DRIVERS = List.of("org.mariadb.jdbc.Driver");

    /** Resolves a JDBC driver class by name, or returns {@code null} when it is not present. */
    @FunctionalInterface
    interface DriverLoader {
        Driver load(String className);
    }

    private ViolationStorage() {
    }

    public static ViolationLog create(StorageSettings settings, Path dataFolder, InMemoryViolationLog cache,
                                      Logger logger) {
        return create(settings, dataFolder, cache, logger, ViolationStorage::loadDriver);
    }

    static ViolationLog create(StorageSettings settings, Path dataFolder, InMemoryViolationLog cache, Logger logger,
                               DriverLoader drivers) {
        ViolationStore store;
        try {
            store = switch (settings.type()) {
                case MEMORY -> null;
                case SQLITE -> sqlite(settings, dataFolder, drivers);
                case MYSQL -> mysql(settings, drivers);
            };
        } catch (UnavailableException unavailable) {
            logger.warning("Violation storage '" + settings.type().key() + "' is unavailable (" + unavailable.getMessage()
                    + "); violations are kept in memory only.");
            return cache;
        }
        if (store == null) {
            return cache;
        }
        return PersistentViolationLog.start(cache, store, settings, logger);
    }

    private static ViolationStore sqlite(StorageSettings settings, Path dataFolder, DriverLoader drivers)
            throws UnavailableException {
        Driver driver = drivers.load(SQLITE_DRIVER);
        if (driver == null) {
            throw new UnavailableException("SQLite JDBC driver " + SQLITE_DRIVER + " not found on this server");
        }
        Path file = dataFolder.resolve(settings.file()).toAbsolutePath().normalize();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException exception) {
            throw new UnavailableException("cannot create the folder for " + settings.file() + ": " + exception.getMessage());
        }
        String url = "jdbc:sqlite:" + file;
        return new JdbcViolationStore(() -> connect(driver, url, new Properties()),
                JdbcViolationStore.Dialect.SQLITE, "sqlite " + settings.file());
    }

    private static ViolationStore mysql(StorageSettings settings, DriverLoader drivers) throws UnavailableException {
        String url = settings.url();
        boolean mariadbUrl = url.toLowerCase(Locale.ROOT).startsWith("jdbc:mariadb:");
        Driver driver = null;
        for (String name : mariadbUrl ? MARIADB_DRIVERS : MYSQL_DRIVERS) {
            driver = drivers.load(name);
            if (driver != null) {
                break;
            }
        }
        if (driver == null) {
            throw new UnavailableException("no " + (mariadbUrl ? "MariaDB" : "MySQL") + " JDBC driver found on this server");
        }
        Driver chosen = driver;
        String user = settings.user();
        String password = settings.password();
        return new JdbcViolationStore(() -> {
            Properties properties = new Properties();
            if (!user.isEmpty()) {
                properties.setProperty("user", user);
            }
            if (!password.isEmpty()) {
                properties.setProperty("password", password);
            }
            return connect(chosen, url, properties);
        }, JdbcViolationStore.Dialect.MYSQL, mariadbUrl ? "mariadb" : "mysql");
    }

    private static java.sql.Connection connect(Driver driver, String url, Properties properties) throws SQLException {
        java.sql.Connection connection = driver.connect(url, properties);
        if (connection == null) {
            throw new SQLException("the JDBC driver does not accept this URL");
        }
        return connection;
    }

    /** Loads a driver visible to the plugin class loader, without relying on DriverManager registration. */
    static Driver loadDriver(String className) {
        try {
            Class<?> type = Class.forName(className, true, ViolationStorage.class.getClassLoader());
            if (!Driver.class.isAssignableFrom(type)) {
                return null;
            }
            for (java.util.Enumeration<Driver> registered = DriverManager.getDrivers(); registered.hasMoreElements(); ) {
                Driver candidate = registered.nextElement();
                if (candidate.getClass() == type) {
                    return candidate;
                }
            }
            return (Driver) type.getDeclaredConstructor().newInstance();
        } catch (ClassNotFoundException | LinkageError missing) {
            return null;
        } catch (ReflectiveOperationException | RuntimeException broken) {
            return null;
        }
    }

    private static final class UnavailableException extends Exception {
        private static final long serialVersionUID = 1L;

        UnavailableException(String message) {
            super(message);
        }
    }
}
