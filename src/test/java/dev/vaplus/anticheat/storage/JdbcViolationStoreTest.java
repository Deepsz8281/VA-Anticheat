package dev.vaplus.anticheat.storage;

import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.violation.ViolationRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Driver;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real SQLite roundtrips. The project has no SQLite dependency (the driver ships with the server), so
 * the driver jar is loaded from the local Maven repository when present; otherwise the test is skipped.
 */
@DisplayName("JdbcViolationStore (SQLite roundtrip)")
class JdbcViolationStoreTest {

    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STEVE = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path folder;

    private static Driver sqliteDriver() throws Exception {
        Path jar = Path.of(System.getProperty("user.home"), ".m2", "repository", "org", "xerial", "sqlite-jdbc",
                "3.45.3.0", "sqlite-jdbc-3.45.3.0.jar");
        assumeTrue(Files.isRegularFile(jar), "sqlite-jdbc not in the local Maven repository");
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                JdbcViolationStoreTest.class.getClassLoader());
        return (Driver) Class.forName(ViolationStorage.SQLITE_DRIVER, true, loader).getDeclaredConstructor().newInstance();
    }

    private JdbcViolationStore store(Driver driver, String file) {
        String url = "jdbc:sqlite:" + folder.resolve(file);
        return new JdbcViolationStore(() -> driver.connect(url, new Properties()), JdbcViolationStore.Dialect.SQLITE,
                "sqlite " + file);
    }

    private static ViolationRecord record(UUID player, String check, long time, String details) {
        return new ViolationRecord(player, "P", check, CheckCategory.COMBAT, 1.5, details, 42L, "1.21.4", time);
    }

    @Test
    @DisplayName("schema, insert, newest-first paging, counts, retention, reopen keeps data")
    void roundtrip() throws Exception {
        Driver driver = sqliteDriver();
        JdbcViolationStore store = store(driver, "v.db");
        store.open();
        assertEquals(JdbcViolationStore.SCHEMA_VERSION, store.schemaVersion());
        store.insert(List.of(
                record(ALEX, "reach", 1_000L, "a"),
                record(ALEX, "reach", 2_000L, "b"),
                record(ALEX, "killaura", 3_000L, "c'); DROP TABLE vaplus_violations; --"),
                record(STEVE, "reach", 4_000L, "d")));
        List<ViolationRecord> page = store.recent(ALEX, 0, 2);
        assertEquals(2, page.size());
        assertEquals("c'); DROP TABLE vaplus_violations; --", page.get(0).details(), "values are bound, not concatenated");
        assertEquals("b", page.get(1).details());
        assertEquals(List.of(record(ALEX, "reach", 1_000L, "a")), store.recent(ALEX, 2, 10));
        assertEquals(Map.of("reach", 3L, "killaura", 1L), store.countsByCheck(null, 0L));
        assertEquals(Map.of("reach", 1L), store.countsByCheck(ALEX, 1_500L).entrySet().stream()
                .filter(e -> e.getKey().equals("reach")).collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue)));
        assertEquals(2, store.deleteOlderThan(2_500L));
        store.close();

        JdbcViolationStore reopened = store(driver, "v.db");
        reopened.open();
        assertEquals(1, reopened.recent(ALEX, 0, 10).size());
        assertEquals(1, reopened.recent(STEVE, 0, 10).size());
        reopened.close();
    }

    @Test
    @DisplayName("a newer schema version is refused instead of being written to")
    void newerSchemaRefused() throws Exception {
        Driver driver = sqliteDriver();
        JdbcViolationStore store = store(driver, "future.db");
        store.open();
        try (var connection = driver.connect("jdbc:sqlite:" + folder.resolve("future.db"), new Properties());
             var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO vaplus_schema_version (version) VALUES (99)");
        }
        store.close();
        assertThrows(ViolationStore.StoreException.class, () -> store(driver, "future.db").open());
    }

    @Test
    @DisplayName("long values are truncated to the column sizes, surrogate pairs are not split")
    void truncation() {
        assertEquals("abc", JdbcViolationStore.truncate("abcdef", 3));
        assertEquals("ab", JdbcViolationStore.truncate("ab😀", 3));
        assertEquals("", JdbcViolationStore.truncate(null, 3));
    }
}
