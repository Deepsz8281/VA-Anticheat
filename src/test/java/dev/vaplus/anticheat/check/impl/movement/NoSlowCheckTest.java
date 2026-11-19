package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.testutil.RecordingTransport;
import dev.vaplus.anticheat.testutil.replay.scenarios.CheckReplay;
import dev.vaplus.anticheat.testutil.replay.scenarios.Frame;
import dev.vaplus.anticheat.testutil.replay.scenarios.MovementScenarios;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static dev.vaplus.anticheat.testutil.replay.VanillaMotion.carry;
import static dev.vaplus.anticheat.testutil.replay.VanillaMotion.groundAccel;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NoSlowCheck (replayed through the real simulation engine)")
class NoSlowCheckTest {

    private static final double SLOWED_ACCEL = groundAccel(0.1, 0.98 * 0.2, 1.0, 0.6);
    private static final double WALK_ACCEL = groundAccel(0.1, 0.98, 1.0, 0.6);

    private static CheckReplay replay() {
        return CheckReplay.create(new NoSlowTestModule())
                .platform(VanillaBlocks.STONE, 63, MovementReplays.RADIUS)
                .spawn(0.5, MovementScenarios.FLOOR_TOP, MovementReplays.START_Z, 0f, true)
                .settle(true);
    }

    /** Server sets/clears the hand-active flag; every marker so far is acknowledged afterwards. */
    private static void serverFlag(CheckReplay replay, boolean using, boolean acknowledge) {
        NoSlowCheck check = replay.check(NoSlowCheck.class);
        List<Runnable> afterSend = new ArrayList<>();
        check.onUsingFlag(using, afterSend);
        afterSend.forEach(Runnable::run);
        if (acknowledge) {
            RecordingTransport transport = replay.harness.lastTransport();
            for (int id : transport.markerIds()) {
                replay.player.acks().onPong(id);
            }
        }
    }

    /** Ground walk with item-use input (x 0.2) starting from forward speed {@code v}. */
    private static List<Frame> slowedWalk(double v, int ticks) {
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            v = carry(v, 0.6) + SLOWED_ACCEL;
            frames.add(new Frame(0, 0, v, true));
        }
        return frames;
    }

    /** Ground walk with full input starting from forward speed {@code v}. */
    private static List<Frame> fullWalk(double v, int ticks) {
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            v = carry(v, 0.6) + WALK_ACCEL;
            frames.add(new Frame(0, 0, v, true));
        }
        return frames;
    }

    /** No input: momentum only. */
    private static List<Frame> coast(double v, int ticks) {
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            v = carry(v, 0.6);
            frames.add(new Frame(0, 0, v, true));
        }
        return frames;
    }

    @Test
    @DisplayName("descriptor: id noslow, MOVEMENT, experimental")
    void descriptor() {
        assertEquals("noslow", NoSlowCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.MOVEMENT, NoSlowCheck.DESCRIPTOR.category());
        assertTrue(NoSlowCheck.DESCRIPTOR.experimental());
        assertTrue((NoSlowCheck.DESCRIPTOR.exemptionMask() & ExemptionCause.TELEPORT.bit()) != 0);
    }

    @Test
    @DisplayName("walking at full speed while the server says the player is using an item flags")
    void fullSpeedWalkFlags() {
        CheckReplay replay = replay();
        serverFlag(replay, true, true);
        NoSlowCheck check = replay.check(NoSlowCheck.class);
        assertTrue(check.usingConfirmed());
        replay.play(MovementScenarios.walk(30));
        assertTrue(check.violationCount() >= 1, "violations " + check.violationCount());
        assertTrue(replay.harness.alerts.alerts().get(0).details().startsWith("moved="));
    }

    @Test
    @DisplayName("sprinting at full speed while using an item flags")
    void sprintFlags() {
        CheckReplay replay = replay();
        serverFlag(replay, true, true);
        replay.play(MovementScenarios.sprint(30));
        assertTrue(replay.check(NoSlowCheck.class).violationCount() >= 1);
    }

    @Test
    @DisplayName("walking slowly (input x 0.2) while using an item never raises the buffer")
    void slowedWalkIsLegit() {
        CheckReplay replay = replay();
        serverFlag(replay, true, true);
        replay.play(slowedWalk(0.0, 40));
        NoSlowCheck check = replay.check(NoSlowCheck.class);
        assertEquals(0, check.violationCount());
        assertEquals(0.0, check.buffer());
        assertTrue(replay.completeCount() >= 40);
    }

    @Test
    @DisplayName("starting to eat while running: momentum within the grace window, then slowed, is legit")
    void startEatingWhileRunning() {
        CheckReplay replay = replay();
        List<Frame> run = fullWalk(0.0, 12);
        replay.play(run);
        serverFlag(replay, true, true);
        double v = run.get(run.size() - 1).dz();
        replay.play(slowedWalk(v, 40));
        assertEquals(0, replay.check(NoSlowCheck.class).violationCount());
        assertEquals(0.0, replay.check(NoSlowCheck.class).buffer());
    }

    @Test
    @DisplayName("sprinting, then eating and coasting on momentum without input is legit")
    void coastingAfterSprint() {
        CheckReplay replay = replay();
        List<Frame> run = MovementScenarios.sprint(12);
        replay.play(run);
        serverFlag(replay, true, true);
        replay.play(coast(run.get(run.size() - 1).dz(), 20));
        assertEquals(0, replay.check(NoSlowCheck.class).violationCount());
        assertEquals(0.0, replay.check(NoSlowCheck.class).buffer());
    }

    @Test
    @DisplayName("an unacknowledged flag is not judged (the client may not have received it yet)")
    void unconfirmedNotJudged() {
        CheckReplay replay = replay();
        serverFlag(replay, true, false);
        assertFalse(replay.check(NoSlowCheck.class).usingConfirmed());
        replay.play(MovementScenarios.walk(30));
        assertEquals(0, replay.check(NoSlowCheck.class).violationCount());
        assertEquals(0.0, replay.check(NoSlowCheck.class).buffer());
    }

    @Test
    @DisplayName("without any item-use flag, full speed is not NoSlow")
    void noFlagNoJudgement() {
        CheckReplay replay = replay();
        replay.play(MovementScenarios.walk(30));
        assertEquals(0, replay.check(NoSlowCheck.class).violationCount());
    }

    @Test
    @DisplayName("stopping to eat (server clears the flag) hands full speed back immediately")
    void stopEating() {
        CheckReplay replay = replay();
        serverFlag(replay, true, true);
        replay.play(slowedWalk(0.0, 20));
        serverFlag(replay, false, true);
        assertFalse(replay.check(NoSlowCheck.class).usingConfirmed());
        replay.play(fullWalk(0.05, 30));
        assertEquals(0, replay.check(NoSlowCheck.class).violationCount());
    }

    @Test
    @DisplayName("a flag superseded before its acknowledgement never confirms")
    void supersededFlag() {
        CheckReplay replay = replay();
        serverFlag(replay, true, false);
        serverFlag(replay, false, false);
        for (int id : replay.harness.lastTransport().markerIds()) {
            replay.player.acks().onPong(id);
        }
        assertFalse(replay.check(NoSlowCheck.class).usingConfirmed());
    }

    @Test
    @DisplayName("an active exemption (teleport) suppresses judging")
    void exemption() {
        CheckReplay replay = replay();
        serverFlag(replay, true, true);
        replay.player.exemptions().grant(ExemptionCause.TELEPORT, 100);
        replay.play(MovementScenarios.walk(30));
        assertEquals(0, replay.check(NoSlowCheck.class).violationCount());
        assertEquals(0.0, replay.check(NoSlowCheck.class).buffer());
    }

    @Test
    @DisplayName("options: valid values apply, invalid ones fall back; a high min-speed suppresses the flag")
    void options() {
        CheckReplay replay = replay();
        NoSlowCheck check = replay.check(NoSlowCheck.class);
        replay.harness.applySettings(replay.player, d -> {
            CheckSettings base = CheckSettings.of(d.defaults(), List.of());
            return new CheckSettings(base.enabled(), base.bufferLimit(), base.bufferDecay(), base.bufferMax(),
                    base.vlDecayPerMinute(), base.alertVl(), base.setbackVl(), base.actions(),
                    new CheckOptions(Map.of("min-speed", 1.5, "grace-ticks", -4, "threshold", "x")));
        });
        serverFlag(replay, true, true);
        replay.play(MovementScenarios.walk(30));
        assertEquals(0, check.violationCount(), "full walking speed is below min-speed 1.5");

        CheckReplay bad = replay();
        bad.harness.applySettings(bad.player, d -> {
            CheckSettings base = CheckSettings.of(d.defaults(), List.of());
            return new CheckSettings(base.enabled(), base.bufferLimit(), base.bufferDecay(), base.bufferMax(),
                    base.vlDecayPerMinute(), base.alertVl(), base.setbackVl(), base.actions(),
                    new CheckOptions(Map.of("min-speed", Double.NaN)));
        });
        serverFlag(bad, true, true);
        bad.play(MovementScenarios.walk(30));
        assertTrue(bad.check(NoSlowCheck.class).violationCount() >= 1, "NaN falls back to the default");
    }
}
