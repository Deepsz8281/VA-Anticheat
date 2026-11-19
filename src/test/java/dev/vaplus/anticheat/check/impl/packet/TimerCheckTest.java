package dev.vaplus.anticheat.check.impl.packet;

import dev.vaplus.anticheat.pipeline.MovementProcessor;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Timer (client clock against real time)")
class TimerCheckTest {

    private static final long MILLI = 1_000_000L;

    private record Fixture(Harness harness, PlayerData player, TimerCheck check) {

        /** One client tick arriving {@code gapMicros} after the previous one. */
        void tick(long gapMicros) {
            harness.time.advanceNanos(gapMicros * 1_000L);
            player.onClientTick();
        }

        void ticks(int count, long gapMicros) {
            for (int i = 0; i < count; i++) {
                tick(gapMicros);
            }
        }

        /** Server sends a marker now; the client echoes it after {@code latencyMillis}. */
        void markerRoundTrip(long latencyMillis) {
            player.acks().sendMarker();
            int id = harness.lastTransport().lastMarkerId();
            harness.time.advanceNanos(latencyMillis * MILLI);
            assertTrue(player.acks().onPong(id));
        }

        int flags() {
            return check.violationCount();
        }
    }

    private static Fixture fixture() {
        Harness harness = TestContexts.builder()
                .check(TimerCheck.DESCRIPTOR, TimerCheck::new)
                .build();
        PlayerData player = harness.newPlayer();
        player.enableTickEndMode();
        return new Fixture(harness, player, player.checks().get(TimerCheck.class));
    }

    @Test
    @DisplayName("a client ticking at 20 TPS never flags")
    void vanillaRate() {
        Fixture fixture = fixture();
        fixture.ticks(2000, 50_000);
        assertEquals(0, fixture.flags());
        assertEquals(0.0, fixture.check().buffer());
    }

    @Test
    @DisplayName("jittery arrival around 50 ms never flags")
    void jitter() {
        Fixture fixture = fixture();
        long[] gaps = {20_000, 80_000, 5_000, 95_000, 50_000, 35_000, 65_000};
        for (int i = 0; i < 2000; i++) {
            fixture.tick(gaps[i % gaps.length]);
        }
        assertEquals(0, fixture.flags());
    }

    @Test
    @DisplayName("timer 1.1x flags within a few seconds and keeps flagging")
    void tenPercentFast() {
        Fixture fixture = fixture();
        fixture.ticks(200, 45_455);
        assertTrue(fixture.flags() >= 1, "flags after 200 fast ticks: " + fixture.flags());
        int first = fixture.flags();
        fixture.ticks(400, 45_455);
        assertTrue(fixture.flags() > first, "sustained speed-up keeps raising the level");
    }

    @Test
    @DisplayName("timer 2x flags every few ticks")
    void doubleSpeed() {
        Fixture fixture = fixture();
        fixture.ticks(100, 25_000);
        assertTrue(fixture.flags() >= 5, "flags: " + fixture.flags());
    }

    @Test
    @DisplayName("a marginal 1% speed-up (clock skew) stays below the buffer")
    void onePercentTolerated() {
        Fixture fixture = fixture();
        fixture.ticks(6000, 49_505);
        assertEquals(0, fixture.flags());
    }

    @Test
    @DisplayName("a lag spike followed by the delayed burst does not flag")
    void lagBurst() {
        Fixture fixture = fixture();
        fixture.ticks(100, 50_000);
        // 3 s stall on the wire: 60 ticks arrive at once afterwards.
        fixture.tick(3_000_000);
        fixture.ticks(59, 100);
        fixture.ticks(100, 50_000);
        assertEquals(0, fixture.flags());
    }

    @Test
    @DisplayName("acknowledged markers stop a client from banking ticks while idle")
    void bankingIsBounded() {
        Fixture fixture = fixture();
        fixture.ticks(20, 50_000);
        // The client stops ticking for 5 s but keeps answering markers: no time is saved up.
        for (int i = 0; i < 100; i++) {
            fixture.markerRoundTrip(50);
        }
        // Burst of 100 ticks (5 s of game time) in 100 ms.
        fixture.ticks(100, 1_000);
        assertTrue(fixture.flags() >= 10, "banked burst must flag, flags: " + fixture.flags());
    }

    @Test
    @DisplayName("without acknowledgements a stall is treated as lag (bounded by the ack timeout)")
    void silentStallIsLag() {
        Fixture fixture = fixture();
        fixture.ticks(100, 50_000);
        fixture.harness().time.advanceNanos(5_000 * MILLI);
        fixture.ticks(99, 1_000);
        assertEquals(0, fixture.flags());
    }

    @Test
    @DisplayName("a latency drop larger than the drift does not flag once the round trip is known")
    void latencyDrop() {
        Fixture fixture = fixture();
        // 300 ms one-way latency, known from a marker before the clock is anchored.
        fixture.markerRoundTrip(600);
        fixture.ticks(100, 50_000);
        // The route improves to ~0 ms: 300 ms of in-flight packets are delivered at once.
        fixture.ticks(6, 100);
        fixture.ticks(400, 50_000);
        assertEquals(0, fixture.flags());
    }

    @Test
    @DisplayName("a faster server tick rate applies immediately, a slower one after the acknowledgement")
    void tickRate() {
        Fixture fixture = fixture();
        fixture.check().applyTickRate(40.0F);
        assertEquals(25 * MILLI, fixture.check().tickNanos());
        fixture.ticks(2000, 25_000);
        assertEquals(0, fixture.flags(), "40 TPS client on a 40 TPS server");

        fixture.check().applyTickRate(20.0F);
        assertEquals(25 * MILLI, fixture.check().tickNanos(), "client may still tick at the old rate");
        int id = fixture.harness().lastTransport().lastMarkerId();
        assertTrue(fixture.player().acks().onPong(id));
        assertEquals(50 * MILLI, fixture.check().tickNanos());
        fixture.ticks(200, 25_000);
        assertTrue(fixture.flags() >= 1, "40 TPS client on a 20 TPS server is a timer");
    }

    @Test
    @DisplayName("a superseded slow-down acknowledgement does not overwrite a newer rate")
    void staleTickRateAck() {
        Fixture fixture = fixture();
        fixture.check().applyTickRate(10.0F);
        int slow = fixture.harness().lastTransport().lastMarkerId();
        fixture.check().applyTickRate(20.0F);
        assertTrue(fixture.player().acks().onPong(slow));
        assertEquals(50 * MILLI, fixture.check().tickNanos());
    }

    @Test
    @DisplayName("join exemption re-anchors the clock instead of judging")
    void exemption() {
        Fixture fixture = fixture();
        fixture.player().exemptions().grant(ExemptionCause.JOIN, 400);
        fixture.ticks(300, 10_000);
        assertEquals(0, fixture.flags());
    }

    @Test
    @DisplayName("legacy clients: duplicate position+look packets are not ticks, a 2x timer still flags")
    void legacyTickSource() {
        Harness harness = TestContexts.builder()
                .check(TimerCheck.DESCRIPTOR, TimerCheck::new)
                .build();
        PlayerData player = harness.newPlayer();
        TimerCheck check = player.checks().get(TimerCheck.class);
        MovementProcessor movement = new MovementProcessor(List.of());
        double x = 0.0;
        for (int i = 0; i < 600; i++) {
            harness.time.advanceNanos(50 * MILLI);
            x += 0.1;
            movement.process(player, true, x, 64.0, 0.0, false, 0f, 0f, true, false);
            // Duplicate position+look in the same tick (item use), identical position.
            movement.process(player, true, x, 64.0, 0.0, true, 10f, 0f, true, false);
        }
        assertEquals(0, check.violationCount());

        for (int i = 0; i < 200; i++) {
            harness.time.advanceNanos(25 * MILLI);
            x += 0.1;
            movement.process(player, true, x, 64.0, 0.0, false, 0f, 0f, true, false);
        }
        assertTrue(check.violationCount() >= 1, "legacy 2x timer flags");
    }
}
