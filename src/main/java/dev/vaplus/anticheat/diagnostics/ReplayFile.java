package dev.vaplus.anticheat.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reader for {@link ReplayRecorder} files. Tolerant by design: blank lines, {@code #} comments and
 * malformed lines are skipped, so a recording cut off mid-line still replays everything before it.
 */
public final class ReplayFile {

    private ReplayFile() {
    }

    public static List<ReplayFrame> read(Path path) throws IOException {
        return parse(Files.readAllLines(path, StandardCharsets.UTF_8));
    }

    static List<ReplayFrame> parse(List<String> lines) {
        List<ReplayFrame> frames = new ArrayList<>(lines.size());
        for (String line : lines) {
            ReplayFrame frame = parseLine(line);
            if (frame != null) {
                frames.add(frame);
            }
        }
        return List.copyOf(frames);
    }

    private static ReplayFrame parseLine(String raw) {
        if (raw == null) {
            return null;
        }
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }
        String[] parts = line.split(",");
        if (parts.length != 8 || !parts[0].equals("M")) {
            return null;
        }
        try {
            long nanos = Long.parseLong(parts[1]);
            double x = Double.parseDouble(parts[2]);
            double y = Double.parseDouble(parts[3]);
            double z = Double.parseDouble(parts[4]);
            float yaw = Float.parseFloat(parts[5]);
            float pitch = Float.parseFloat(parts[6]);
            boolean onGround = Integer.parseInt(parts[7]) != 0;
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || !Float.isFinite(yaw) || !Float.isFinite(pitch) || nanos < 0L) {
                return null;
            }
            return new ReplayFrame(nanos, x, y, z, yaw, pitch, onGround);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
