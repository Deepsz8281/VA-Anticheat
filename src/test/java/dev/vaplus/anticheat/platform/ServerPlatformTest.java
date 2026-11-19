package dev.vaplus.anticheat.platform;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("server platform detection")
class ServerPlatformTest {

    @Test
    @DisplayName("Paper and its builds are detected as Paper")
    void detectsPaper() {
        assertEquals(ServerPlatform.PAPER,
                ServerPlatform.detect("Paper", "git-Paper-196 (MC: 1.21.1)", false));
        assertEquals(ServerPlatform.PAPER,
                ServerPlatform.detect("CraftBukkit", "git-Paper-123", false));
    }

    @Test
    @DisplayName("Purpur is reported separately from Paper")
    void detectsPurpur() {
        assertEquals(ServerPlatform.PURPUR,
                ServerPlatform.detect("Purpur", "1.21.1-R0.1-SNAPSHOT", false));
    }

    @Test
    @DisplayName("Folia wins over its Paper base, both by class and by name")
    void detectsFolia() {
        assertEquals(ServerPlatform.FOLIA,
                ServerPlatform.detect("Paper", "1.21.1", true));
        assertEquals(ServerPlatform.FOLIA,
                ServerPlatform.detect("Folia", "1.21.1", false));
    }

    @Test
    @DisplayName("Bukkit bridges on Fabric/NeoForge are reported as hybrid")
    void detectsHybrid() {
        assertEquals(ServerPlatform.HYBRID,
                ServerPlatform.detect("Fabric", "1.21.1", false));
        assertEquals(ServerPlatform.HYBRID,
                ServerPlatform.detect("Cardboard", "1.21.1", false));
        assertEquals(ServerPlatform.HYBRID,
                ServerPlatform.detect("Mohist", "1.21.1", false));
    }

    @Test
    @DisplayName("unknown implementations do not crash detection")
    void detectsUnknown() {
        assertEquals(ServerPlatform.UNKNOWN, ServerPlatform.detect("SomeServer", "1.0", false));
        assertEquals(ServerPlatform.UNKNOWN, ServerPlatform.detect(null, null, false));
    }
}
