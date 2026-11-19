package dev.vaplus.anticheat.check;

import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.Descriptors;
import dev.vaplus.anticheat.testutil.ProbeChecks;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.violation.ActionThreshold;
import dev.vaplus.anticheat.violation.ViolationRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Check base class")
class CheckTest {

    private static final double EPS = 1e-9;

    private record Fixture(Harness harness, PlayerData player, ProbeChecks.Probe check) {
    }

    private static Fixture fixture(CheckCategory category, UnaryOperator<CheckDefaults> defaults) {
        return fixture(TestContexts.builder(), category, defaults);
    }

    private static Fixture fixture(TestContexts.Builder builder, CheckCategory category, UnaryOperator<CheckDefaults> defaults) {
        CheckDescriptor descriptor = Descriptors.of("probe", category, defaults);
        Harness harness = builder.check(descriptor, ProbeChecks.factory(descriptor, ProbeChecks.Probe::new)).build();
        PlayerData player = harness.newPlayer(UUID.randomUUID(), "Alex");
        return new Fixture(harness, player, player.checks().get(ProbeChecks.Probe.class));
    }

    private static Fixture movement() {
        return fixture(CheckCategory.MOVEMENT, UnaryOperator.identity());
    }

    @Nested
    @DisplayName("buffer")
    class Buffer {

        @Test
        @DisplayName("raiseBuffer reports true only once the buffer is strictly above the limit")
        void limitIsExclusive() {
            Fixture f = movement(); // limit 2.0, max 8.0
            assertFalse(f.check().raise(1.0));
            assertFalse(f.check().raise(1.0), "buffer == limit must not flag");
            assertEquals(2.0, f.check().buffer(), EPS);
            assertTrue(f.check().raise(0.5));
            assertEquals(2.5, f.check().buffer(), EPS);
        }

        @Test
        @DisplayName("buffer is capped at bufferMax")
        void cappedAtMax() {
            Fixture f = movement();
            assertTrue(f.check().raise(100.0));
            assertEquals(8.0, f.check().buffer(), EPS);
            f.check().raise(1.0);
            assertEquals(8.0, f.check().buffer(), EPS);
        }

        @Test
        @DisplayName("bufferMax of 0 means unbounded")
        void zeroMaxIsUnbounded() {
            Fixture f = fixture(CheckCategory.PACKET, UnaryOperator.identity()); // limit 0, max 0
            assertTrue(f.check().raise(1000.0));
            assertEquals(1000.0, f.check().buffer(), EPS);
        }

        @Test
        @DisplayName("limit 0 flags on the first positive raise, not on zero")
        void zeroLimit() {
            Fixture f = fixture(CheckCategory.PACKET, UnaryOperator.identity());
            assertFalse(f.check().raise(0.0));
            assertTrue(f.check().raise(0.001));
        }

        @Test
        @DisplayName("lowerBuffer() removes the configured decay and never goes below zero")
        void lowerUsesDecayAndFloors() {
            Fixture f = movement(); // decay 0.05
            f.check().raise(1.0);
            f.check().lower();
            assertEquals(0.95, f.check().buffer(), EPS);
            f.check().lower(0.5);
            assertEquals(0.45, f.check().buffer(), EPS);
            f.check().lower(10.0);
            assertEquals(0.0, f.check().buffer(), EPS);
            f.check().lower();
            assertEquals(0.0, f.check().buffer(), EPS);
        }

        @Test
        @DisplayName("resetBuffer sets the buffer to zero")
        void resetBuffer() {
            Fixture f = movement();
            f.check().raise(3.0);
            f.check().clearBuffer();
            assertEquals(0.0, f.check().buffer(), EPS);
        }

        @Test
        @DisplayName("buffer follows reloaded settings")
        void reloadedLimit() {
            Fixture f = movement();
            f.harness().applySettings(f.player(), d -> CheckSettings.of(d.defaults().withBufferMax(12).withBufferLimit(10), List.of()));
            assertFalse(f.check().raise(9.0));
            assertTrue(f.check().raise(2.0));
            assertEquals(11.0, f.check().buffer(), EPS);
        }
    }

    @Nested
    @DisplayName("violation()")
    class Violation {

        @Test
        @DisplayName("increments level and count by weight")
        void incrementsLevelAndCount() {
            Fixture f = movement();
            assertTrue(f.check().flag("first"));
            assertTrue(f.check().flag(2.5, "second"));
            assertEquals(3.5, f.check().violationLevel(), EPS);
            assertEquals(2, f.check().violationCount());
        }

        @Test
        @DisplayName("weight must be positive and finite-comparable")
        void invalidWeightThrows() {
            Fixture f = movement();
            assertThrows(IllegalArgumentException.class, () -> f.check().flag(0.0, "x"));
            assertThrows(IllegalArgumentException.class, () -> f.check().flag(-1.0, "x"));
            assertThrows(IllegalArgumentException.class, () -> f.check().flag(Double.NaN, "x"));
            assertEquals(0.0, f.check().violationLevel());
            assertEquals(0, f.check().violationCount());
            assertTrue(f.harness().flags.events().isEmpty(), "invalid weight must not reach API listeners");
        }

        @Test
        @DisplayName("null details are logged as empty string")
        void nullDetails() {
            Fixture f = movement();
            f.check().flag(1.0, null);
            ViolationRecord record = f.harness().violationLog.query(f.player().uuid(), 0, 10).join().get(0);
            assertEquals("", record.details());
            assertEquals("", f.harness().alerts.alerts().get(0).details());
        }

        @Test
        @DisplayName("the API event carries the prospective level before it is committed")
        void eventSeesNewLevel() {
            Fixture f = movement();
            f.check().flag(2.0, "a");
            f.check().flag(1.0, "b");
            assertEquals(2.0, f.harness().flags.events().get(0).violationLevel(), EPS);
            assertEquals(3.0, f.harness().flags.events().get(1).violationLevel(), EPS);
            assertEquals("b", f.harness().flags.events().get(1).details());
        }

        @Test
        @DisplayName("violations are retained when decay is disabled")
        void noDecayWhenZero() {
            Fixture f = fixture(CheckCategory.MOVEMENT, d -> d.withVlDecayPerMinute(0));
            f.check().flag(5.0, "x");
            f.harness().time.advanceMinutes(600);
            assertEquals(5.0, f.check().violationLevel(), EPS);
        }
    }

    @Nested
    @DisplayName("violation level decay")
    class Decay {

        @Test
        @DisplayName("decays linearly per minute and floors at zero")
        void linearDecay() {
            Fixture f = movement(); // 6 VL per minute
            f.check().flag(10.0, "x");
            assertEquals(10.0, f.check().violationLevel(), EPS);
            f.harness().time.advanceSeconds(30);
            assertEquals(7.0, f.check().violationLevel(), EPS);
            f.harness().time.advanceSeconds(30);
            assertEquals(4.0, f.check().violationLevel(), EPS);
            f.harness().time.advanceMinutes(5);
            assertEquals(0.0, f.check().violationLevel(), EPS);
        }

        @Test
        @DisplayName("new violation builds on the decayed level and re-anchors the clock")
        void violationAfterDecay() {
            Fixture f = movement();
            f.check().flag(10.0, "x");
            f.harness().time.advanceSeconds(30); // level 7
            f.check().flag(1.0, "y");
            assertEquals(8.0, f.check().violationLevel(), EPS);
            f.harness().time.advanceSeconds(30); // 8 - 3 = 5
            assertEquals(5.0, f.check().violationLevel(), EPS);
            ViolationRecord latest = f.harness().violationLog.query(f.player().uuid(), 0, 1).join().get(0);
            assertEquals(8.0, latest.violationLevel(), EPS);
        }

        @Test
        @DisplayName("count is not decayed")
        void countNotDecayed() {
            Fixture f = movement();
            f.check().flag(1.0, "x");
            f.harness().time.advanceMinutes(10);
            assertEquals(0.0, f.check().violationLevel(), EPS);
            assertEquals(1, f.check().violationCount());
        }

        @Test
        @DisplayName("decay rate follows reloaded settings")
        void reloadChangesRate() {
            Fixture f = movement();
            f.check().flag(10.0, "x");
            f.harness().applySettings(f.player(), d -> CheckSettings.of(d.defaults().withVlDecayPerMinute(60), List.of()));
            f.harness().time.advanceSeconds(30);
            assertEquals(0.0, f.check().violationLevel(), EPS, "30s at 60 VL/min removes 30");
        }
    }

    @Nested
    @DisplayName("cancelled flag event")
    class Cancelled {

        @Test
        @DisplayName("leaves level and count unchanged and produces no alert, log, setback or punishment")
        void cancelledHasNoEffect() {
            ActionThreshold kickAtOne = new ActionThreshold(1.0, 0.0,
                    List.of(new dev.vaplus.anticheat.violation.ViolationAction.Kick(null)));
            Fixture f = movement();
            f.harness().applySettings(f.player(), d -> CheckSettings.of(d.defaults(), List.of(kickAtOne)));
            f.harness().flags.cancelAll(true);

            assertFalse(f.check().flag(5.0, "cancelled"));

            assertEquals(0.0, f.check().violationLevel(), EPS);
            assertEquals(0, f.check().violationCount());
            assertEquals(1, f.harness().flags.events().size(), "listeners still see the attempt");
            assertTrue(f.harness().alerts.alerts().isEmpty());
            assertTrue(f.harness().violationLog.query(f.player().uuid(), 0, 10).join().isEmpty());
            assertTrue(f.harness().setbacks.requests().isEmpty());
            assertTrue(f.harness().punishments.executions().isEmpty());
        }

        @Test
        @DisplayName("a cancelled violation does not touch an existing level")
        void cancelledKeepsExistingLevel() {
            Fixture f = movement();
            f.check().flag(2.0, "ok");
            f.harness().flags.cancelAll(true);
            assertFalse(f.check().flag(2.0, "cancelled"));
            assertEquals(2.0, f.check().violationLevel(), EPS);
            assertEquals(1, f.check().violationCount());
            f.harness().flags.cancelAll(false);
            assertTrue(f.check().flag(1.0, "ok again"));
            assertEquals(3.0, f.check().violationLevel(), EPS);
        }
    }

    @Nested
    @DisplayName("alerts")
    class Alerts {

        @Test
        @DisplayName("an alert is sent for each violation at or above alert-vl")
        void alertVl() {
            Fixture f = fixture(CheckCategory.MOVEMENT, d -> d.withAlertVl(3.0));
            f.check().flag(1.0, "1");
            f.check().flag(1.0, "2");
            assertTrue(f.harness().alerts.alerts().isEmpty(), "level 2 is below alert-vl 3");
            f.check().flag(1.0, "3");
            f.check().flag(1.0, "4");
            assertEquals(2, f.harness().alerts.alerts().size());
            assertEquals(3.0, f.harness().alerts.alerts().get(0).violationLevel(), EPS);
            assertEquals("3", f.harness().alerts.alerts().get(0).details());
            assertEquals(4.0, f.harness().alerts.alerts().get(1).violationLevel(), EPS);
            assertEquals(4, f.harness().violationLog.query(f.player().uuid(), 0, 10).join().size(),
                    "logging is independent of alert-vl");
        }

        @Test
        @DisplayName("alerts.min-interval rate-limits alerts per check instance")
        void minInterval() {
            Fixture f = fixture(TestContexts.builder().settings(TestContexts.globals().alertMinIntervalMillis(1000).build()),
                    CheckCategory.MOVEMENT, UnaryOperator.identity());
            f.check().flag(1.0, "a");
            f.check().flag(1.0, "b");
            assertEquals(1, f.harness().alerts.alerts().size(), "second alert in the same instant is suppressed");
            f.harness().time.advanceMillis(999);
            f.check().flag(1.0, "c");
            assertEquals(1, f.harness().alerts.alerts().size());
            f.harness().time.advanceMillis(1);
            f.check().flag(1.0, "d");
            assertEquals(2, f.harness().alerts.alerts().size(), "exactly min-interval later is allowed");
            assertEquals("d", f.harness().alerts.alerts().get(1).details());
            assertEquals(4, f.harness().violationLog.query(f.player().uuid(), 0, 10).join().size(),
                    "suppressed alerts are still logged");
        }

        @Test
        @DisplayName("suppressed alerts below alert-vl do not consume the rate-limit window")
        void belowAlertVlDoesNotClaim() {
            Fixture f = fixture(TestContexts.builder().settings(TestContexts.globals().alertMinIntervalMillis(1000).build()),
                    CheckCategory.MOVEMENT, d -> d.withAlertVl(2.0));
            f.check().flag(1.0, "below");
            f.check().flag(1.0, "reaches");
            assertEquals(1, f.harness().alerts.alerts().size());
        }

        @Test
        @DisplayName("min-interval 0 alerts on every violation")
        void zeroInterval() {
            Fixture f = movement();
            for (int i = 0; i < 5; i++) {
                f.check().flag(1.0, "x");
            }
            assertEquals(5, f.harness().alerts.alerts().size());
        }
    }

    @Nested
    @DisplayName("setbacks")
    class Setbacks {

        @Test
        @DisplayName("requested for MOVEMENT once level reaches setback-vl")
        void movementSetback() {
            Fixture f = fixture(CheckCategory.MOVEMENT, d -> d.withSetbackVl(2.0));
            f.check().flag(1.0, "x");
            assertTrue(f.harness().setbacks.requests().isEmpty());
            f.check().flag(1.0, "x");
            assertEquals(1, f.harness().setbacks.requests().size());
            assertEquals(f.check(), f.harness().setbacks.requests().get(0).cause());
            assertEquals(f.player(), f.harness().setbacks.requests().get(0).player());
            f.check().flag(1.0, "x");
            assertEquals(2, f.harness().setbacks.requests().size(), "every violation above the threshold asks again");
        }

        @Test
        @DisplayName("negative setback-vl disables setbacks")
        void disabledByNegativeVl() {
            Fixture f = fixture(CheckCategory.MOVEMENT, d -> d.withSetbackVl(-1.0));
            f.check().flag(100.0, "x");
            assertTrue(f.harness().setbacks.requests().isEmpty());
        }

        @Test
        @DisplayName("never requested for non-movement categories even when setbackVl is set")
        void onlyMovementCategory() {
            for (CheckCategory category : CheckCategory.values()) {
                if (category == CheckCategory.MOVEMENT) {
                    continue;
                }
                Fixture f = fixture(category, d -> d.withSetbackVl(0.5));
                f.check().flag(10.0, "x");
                assertTrue(f.harness().setbacks.requests().isEmpty(), category + " must not set back");
            }
        }

        @Test
        @DisplayName("disabled globally by setback.enabled=false")
        void globalSwitch() {
            Fixture f = fixture(TestContexts.builder().settings(TestContexts.globals().setbacksEnabled(false).build()),
                    CheckCategory.MOVEMENT, UnaryOperator.identity());
            f.check().flag(10.0, "x");
            assertTrue(f.harness().setbacks.requests().isEmpty());
        }

        @Test
        @DisplayName("global switch is re-read on every violation (live reload)")
        void globalSwitchLive() {
            Fixture f = movement();
            f.harness().context.updateSettings(TestContexts.globals().setbacksEnabled(false).build());
            f.check().flag(1.0, "x");
            assertTrue(f.harness().setbacks.requests().isEmpty());
            f.harness().context.updateSettings(TestContexts.globals().setbacksEnabled(true).build());
            f.check().flag(1.0, "x");
            assertEquals(1, f.harness().setbacks.requests().size());
        }
    }

    @Nested
    @DisplayName("misc")
    class Misc {

        @Test
        @DisplayName("slot is bound to the registry slot and toString names check and player")
        void slotAndToString() {
            Fixture f = movement();
            assertEquals(0, f.check().slot());
            assertEquals("probe[Alex]", f.check().toString());
            assertEquals(f.player(), f.check().player());
        }

        @Test
        @DisplayName("isExempt delegates to the player's game-mode exemption for the check category")
        void exemptDelegates() {
            Fixture f = movement();
            assertFalse(f.check().exempt());
            f.player().abilities().applyGameMode(com.github.retrooper.packetevents.protocol.player.GameMode.CREATIVE);
            assertTrue(f.check().exempt());
        }

        @Test
        @DisplayName("isExempt also honours the descriptor's exemptionMask (timed grant and bracket hold)")
        void exemptionMask() {
            CheckDescriptor descriptor = CheckDescriptor.builder("masked", CheckCategory.MOVEMENT)
                    .exemptWhile(dev.vaplus.anticheat.player.state.ExemptionCause.TELEPORT,
                            dev.vaplus.anticheat.player.state.ExemptionCause.VEHICLE)
                    .build();
            Harness harness = TestContexts.builder()
                    .check(descriptor, ProbeChecks.factory(descriptor, ProbeChecks.Probe::new))
                    .build();
            PlayerData player = harness.newPlayer(UUID.randomUUID(), "Alex");
            ProbeChecks.Probe check = player.checks().get(ProbeChecks.Probe.class);

            assertFalse(check.exempt(), "survival without active causes is not exempt");
            player.exemptions().grant(dev.vaplus.anticheat.player.state.ExemptionCause.TELEPORT, 1);
            assertTrue(check.exempt(), "an active masked cause exempts even in survival");

            // bracket hold of a masked cause: exempt while held, again judgeable once it expired
            player = harness.newPlayer(UUID.randomUUID(), "Bob");
            check = player.checks().get(ProbeChecks.Probe.class);
            player.exemptions().hold(dev.vaplus.anticheat.player.state.ExemptionCause.TELEPORT);
            assertTrue(check.exempt(), "a held masked cause exempts");
            for (int i = 0; i <= dev.vaplus.anticheat.player.state.ExemptionState.MAX_HOLD_TICKS; i++) {
                player.onClientTick();
            }
            assertFalse(check.exempt(), "an expired hold must not exempt any longer");
            assertEquals(1L, player.exemptions().expiredHolds());

            // an unmasked cause must not exempt
            PlayerData other = harness.newPlayer(UUID.randomUUID(), "Other");
            ProbeChecks.Probe otherCheck = other.checks().get(ProbeChecks.Probe.class);
            other.exemptions().grant(dev.vaplus.anticheat.player.state.ExemptionCause.JOIN, 5);
            assertFalse(otherCheck.exempt(), "JOIN is not in the mask of this check");
        }

        @Test
        @DisplayName("violation level is readable from another thread without throwing")
        void crossThreadRead() throws Exception {
            Fixture f = movement();
            f.check().flag(3.0, "x");
            double[] seen = new double[1];
            Thread thread = new Thread(() -> seen[0] = f.check().violationLevel());
            thread.start();
            thread.join();
            assertEquals(3.0, seen[0], EPS);
        }
    }
}
