package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationResult;
import dev.vaplus.anticheat.testutil.RecordingAlertSink;
import dev.vaplus.anticheat.testutil.TestContexts;

import java.util.List;
import java.util.Map;

/**
 * Unit-test driver for the movement checks: a harness with {@link MovementChecksModule} and no
 * simulator; every call hand-builds the {@link SimulationResult} and dispatches one position update
 * through the player's check bundle (exactly as the pipeline would after the simulation ran).
 */
final class ResultFeeder {

    final TestContexts.Harness harness;
    final PlayerData player;
    private double x;
    private double y = 64.0;
    private double z;
    private boolean ground = true;

    ResultFeeder() {
        harness = TestContexts.builder().module(new MovementChecksModule()).build();
        player = harness.newPlayer();
    }

    <T extends Check> T check(Class<T> type) {
        return player.checks().get(type);
    }

    /** Replaces the options of {@code descriptor} (everything else stays at the descriptor defaults). */
    ResultFeeder options(CheckDescriptor descriptor, Map<String, ?> options) {
        harness.applySettings(player, d -> {
            CheckSettings base = CheckSettings.of(d.defaults(), List.of());
            if (d != descriptor) {
                return base;
            }
            return new CheckSettings(base.enabled(), base.bufferLimit(), base.bufferDecay(), base.bufferMax(),
                    base.vlDecayPerMinute(), base.alertVl(), base.setbackVl(), base.actions(), new CheckOptions(options));
        });
        return this;
    }

    /**
     * Dispatches one COMPLETE update: the client reported {@code (dx, dy, dz)} with ground flag
     * {@code claimedGround}; the best candidate predicted {@code (px, py, pz)} and {@code predictedGround}.
     */
    void complete(double dx, double dy, double dz, boolean claimedGround,
                  double px, double py, double pz, boolean predictedGround, int ticks, double uncertainty) {
        MovementUpdate update = assign(dx, dy, dz, claimedGround, ticks);
        player.simulation().complete(px, py, pz, dx - px, dy - py, dz - pz, predictedGround, ticks, 0, uncertainty);
        player.checks().dispatchPosition(update);
    }

    /** Dispatches one update whose simulation was skipped. */
    void skipped(double dx, double dy, double dz, boolean claimedGround, String reason) {
        MovementUpdate update = assign(dx, dy, dz, claimedGround, 1);
        player.simulation().skip(reason);
        player.checks().dispatchPosition(update);
    }

    /** Dispatches one update with status NOT_RUN. */
    void notRun(double dx, double dy, double dz, boolean claimedGround) {
        MovementUpdate update = assign(dx, dy, dz, claimedGround, 1);
        player.simulation().reset();
        player.checks().dispatchPosition(update);
    }

    List<RecordingAlertSink.Alert> alerts() {
        return harness.alerts.alerts();
    }

    private MovementUpdate assign(double dx, double dy, double dz, boolean claimedGround, int ticks) {
        MovementUpdate update = player.movementUpdate();
        update.assign(x, y, z, x + dx, y + dy, z + dz, ground, claimedGround, false,
                true, false, false, ticks, harness.time.nanoTime());
        x += dx;
        y += dy;
        z += dz;
        ground = claimedGround;
        return update;
    }
}
