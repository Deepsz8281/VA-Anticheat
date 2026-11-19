package dev.vaplus.anticheat.storage;

import dev.vaplus.anticheat.testutil.Configs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("StorageSettingsParser (storage: section)")
class StorageSettingsParserTest {

    @Test
    @DisplayName("the bundled config resolves to the defaults without warnings")
    void bundled() {
        List<String> warnings = new ArrayList<>();
        StorageSettings settings = StorageSettingsParser.parse(
                Configs.bundledConfig().getConfigurationSection("storage"), warnings);
        assertEquals(List.of(), warnings);
        assertEquals(StorageSettings.defaults(), settings);
    }

    @Test
    @DisplayName("absent section: defaults")
    void absent() {
        List<String> warnings = new ArrayList<>();
        assertEquals(StorageSettings.defaults(), StorageSettingsParser.parse(null, warnings));
        assertTrue(warnings.isEmpty());
    }

    @Test
    @DisplayName("mysql / mariadb alias with a valid URL")
    void mysql() {
        List<String> warnings = new ArrayList<>();
        StorageSettings settings = StorageSettingsParser.parse(Configs.fromString("""
                storage:
                  type: MariaDB
                  url: jdbc:mariadb://db:3306/ac
                  user: ac
                  password: secret
                  retention-days: 0
                """).getConfigurationSection("storage"), warnings);
        assertEquals(List.of(), warnings);
        assertEquals(StorageSettings.Type.MYSQL, settings.type());
        assertEquals(0, settings.retentionDays());
        assertFalse(settings.toString().contains("secret"), "the password is never printed");
        assertFalse(settings.toString().contains("db:3306"), "the URL may embed credentials");
    }

    @Test
    @DisplayName("invalid values fall back with a warning each, never throw")
    void invalid() {
        List<String> warnings = new ArrayList<>();
        StorageSettings settings = StorageSettingsParser.parse(Configs.fromString("""
                storage:
                  type: postgres
                  file: ../../etc/passwd
                  retention-days: -4
                  queue-size: 2
                  batch-size: 1.5
                """).getConfigurationSection("storage"), warnings);
        assertEquals(5, warnings.size(), warnings.toString());
        assertEquals(StorageSettings.defaults(), settings);

        List<String> mysqlWithoutUrl = new ArrayList<>();
        StorageSettings fallback = StorageSettingsParser.parse(Configs.fromString("""
                storage:
                  type: mysql
                  url: http://example
                """).getConfigurationSection("storage"), mysqlWithoutUrl);
        assertEquals(StorageSettings.Type.SQLITE, fallback.type());
        assertEquals(1, mysqlWithoutUrl.size());
    }

    @Test
    @DisplayName("file names must stay inside the plugin folder")
    void fileNames() {
        assertTrue(StorageSettingsParser.validFile("violations.db"));
        assertTrue(StorageSettingsParser.validFile("data/violations.db"));
        assertFalse(StorageSettingsParser.validFile("../violations.db"));
        assertFalse(StorageSettingsParser.validFile(""));
        assertFalse(StorageSettingsParser.validFile(System.getProperty("java.io.tmpdir") + "/x.db"));
    }

    @Test
    @DisplayName("the record rejects out-of-range values")
    void recordValidation() {
        assertThrows(IllegalArgumentException.class, () -> new StorageSettings(StorageSettings.Type.MEMORY,
                "a", "", "", "", 0, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> new StorageSettings(StorageSettings.Type.MEMORY,
                "a", "", "", "", 0, 100, 200));
    }
}
