package dev.vaplus.anticheat.testutil.replay;

import dev.vaplus.anticheat.diagnostics.ReplayFrame;
import dev.vaplus.anticheat.simulation.SimulationResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Replays a {@code -Dvaplus.record} file through the real movement pipeline: the first frame spawns
 * the player, every later frame becomes one position+rotation packet (world-space deltas between
 * consecutive frames). The world must be set up like the recorded one (flat known terrain); block
 * changes are not part of the recording format yet.
 */
public final class ReplayHarness {

    private ReplayHarness() {
    }

    /** @return one result per frame, index 0 is the spawn packet */
    public static List<SimulationResult> play(ReplayScenario scenario, List<ReplayFrame> frames) {
        if (frames.isEmpty()) {
            return List.of();
        }
        ReplayFrame first = frames.get(0);
        List<SimulationResult> results = new ArrayList<>(frames.size());
        results.add(scenario.spawn(first.x(), first.y(), first.z(), first.yaw()));
        for (int i = 1; i < frames.size(); i++) {
            ReplayFrame previous = frames.get(i - 1);
            ReplayFrame frame = frames.get(i);
            scenario.yaw(frame.yaw());
            results.add(scenario.move(frame.x() - previous.x(), frame.y() - previous.y(),
                    frame.z() - previous.z()));
        }
        return List.copyOf(results);
    }
}
