package dev.vaplus.anticheat.diagnostics;

import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.TestContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Replay recorder and file parser")
class ReplayRecorderTest {

    @Test
    @DisplayName("recorded frames survive the write/read roundtrip with their values")
    void roundtrip(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("session.txt");
        PlayerData player = TestContexts.empty().newPlayer();
        ReplayRecorder recorder = ReplayRecorder.open(file, Logger.getAnonymousLogger());
        assertTrue(recorder.enabled());

        player.movement().applyPosition(0.5, 64.0, 0.5);
        player.movement().applyRotation(90f, 12.5f);
        recorder.record(player, 1_000_000_000L);
        player.movement().applyPosition(0.5, 64.0, 0.598);
        player.movement().applyRotation(90f, 12.5f);
        recorder.record(player, 1_050_000_000L);
        recorder.close();

        List<ReplayFrame> frames = ReplayFile.read(file);
        assertEquals(2, frames.size());
        assertEquals(0L, frames.get(0).nanos());
        assertEquals(0.5, frames.get(0).x(), 1.0E-6);
        assertEquals(64.0, frames.get(0).y(), 1.0E-6);
        assertEquals(90f, frames.get(0).yaw(), 1.0E-4);
        assertEquals(12.5f, frames.get(0).pitch(), 1.0E-4);
        assertEquals(50L, frames.get(1).nanos(), "relative milliseconds");
        assertEquals(0.598, frames.get(1).z(), 1.0E-6);
    }

    @Test
    @DisplayName("malformed and comment lines are skipped, valid lines are kept")
    void tolerantParser() {
        List<ReplayFrame> frames = ReplayFile.parse(List.of(
                "# VA+ replay v1",
                "",
                "M,0,0.5,64,0.5,0,0,1",
                "M,50,0.5,64,0.6,0,0,1",
                "garbage",
                "M,50,0.5,64",                     // too short
                "M,50,0.5,64,0.6,0,0,1,extra",     // too long
                "M,50,NaN,64,0.6,0,0,1",           // not finite
                "M,100,0.5,64,0.7,0,0,1"));
        assertEquals(3, frames.size());
        assertEquals(100L, frames.get(2).nanos());
        assertEquals(0.7, frames.get(2).z(), 1.0E-6);
    }

    @Test
    @DisplayName("a disabled recorder writes nothing and never opens a file")
    void disabled(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("never.txt");
        ReplayRecorder recorder = ReplayRecorder.disabled();
        assertFalse(recorder.enabled());
        recorder.record(TestContexts.empty().newPlayer(), 1L);
        recorder.close();
        assertFalse(Files.exists(file));
        assertEquals(0, ReplayFile.parse(List.of()).size());
    }

    @Test
    @DisplayName("the recording file is plain UTF-8 text, one packet per line")
    void textFormat(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("session.txt");
        PlayerData player = TestContexts.empty().newPlayer();
        ReplayRecorder recorder = ReplayRecorder.open(file, Logger.getAnonymousLogger());
        player.movement().applyPosition(1.0, 2.0, 3.0);
        player.movement().applyRotation(0f, 0f);
        recorder.record(player, 5_000_000L);
        recorder.close();

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).startsWith("M,0,1.000000,2.000000,3.000000,0.000,0.000,"), lines.get(0));
    }
}
