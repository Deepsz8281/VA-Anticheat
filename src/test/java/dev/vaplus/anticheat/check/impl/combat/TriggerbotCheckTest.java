package dev.vaplus.anticheat.check.impl.combat;

import com.github.retrooper.packetevents.protocol.world.BlockFace;
import dev.vaplus.anticheat.check.impl.world.NearbyPlayers;
import dev.vaplus.anticheat.check.impl.world.VisibilityChecksModule;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.world.BlockInteraction;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Triggerbot (cold clicks at the instant of target acquisition)")
class TriggerbotCheckTest {

    private static final int TARGET = 21;
    private static final double FEET_Y = 64.0;
    /** Yaw 0 looks straight at the target, 30 degrees clearly past it, 12 degrees just beside it. */
    private static final float ON = 0f;
    private static final float OFF = 30f;
    private static final float BESIDE = 12f;

    private record Fixture(Harness harness, PlayerData player, NearbyPlayers tracked, TriggerbotCheck check) {

        /** One client tick: optional attack and swing first, then the look packet, then the tick end. */
        void tick(float yaw, boolean attack, boolean swing) {
            if (attack) {
                EntityAttack packet = new EntityAttack();
                packet.assign(TARGET, EntityAttack.Kind.ATTACK, false, 0, 0, 0, 0L);
                player.checks().dispatchAttack(packet);
            }
            if (swing) {
                check.onSwing();
            }
            player.movement().applyRotation(yaw, 0f);
            player.onClientTick();
        }

        void look(float yaw, int ticks) {
            for (int i = 0; i < ticks; i++) {
                tick(yaw, false, false);
            }
        }

        void hit(float yaw) {
            tick(yaw, true, true);
        }

        void miss(float yaw) {
            tick(yaw, false, true);
        }

        /** First hit on the target: starts its history, never judged. */
        void prime() {
            look(ON, 3);
            hit(ON);
            look(OFF, 12);
        }

        int flags() {
            return check.violationCount();
        }

        String details() {
            return "details: " + harness.alerts.alerts() + " fast=" + check.fastSamples()
                    + " slow=" + check.slowSamples() + " misses=" + check.missSwings();
        }
    }

    private static Fixture fixture(boolean tickEnd) {
        SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
        VisibilityChecksModule visibility = new VisibilityChecksModule(simulation);
        Harness harness = TestContexts.builder()
                .module(simulation)
                .module(visibility)
                .module(new CombatChecksModule(simulation, visibility))
                .build();
        PlayerData player = harness.newPlayer();
        if (tickEnd) {
            player.enableTickEndMode();
        }
        player.movement().applyPosition(0.5, FEET_Y, 0.5);
        player.movement().applyRotation(OFF, 0f);
        NearbyPlayers tracked = player.component(visibility.nearbyKey());
        tracked.spawn(TARGET, 0.5, FEET_Y, 3.5);
        return new Fixture(harness, player, tracked, player.checks().get(TriggerbotCheck.class));
    }

    @Test
    @DisplayName("the check is registered with the combat module")
    void registered() {
        assertEquals("triggerbot", TriggerbotCheck.DESCRIPTOR.id());
        assertTrue(fixture(true).check() != null);
    }

    @Test
    @DisplayName("sweeping across the target and hitting on the crossing tick, never missing, flags")
    void sweepTriggerbot() {
        Fixture fixture = fixture(true);
        fixture.prime();
        float side = OFF;
        for (int i = 0; i < 3; i++) {
            fixture.hit(ON);
            side = -side;
            fixture.look(side, 12);
        }
        assertEquals(1, fixture.flags(), fixture::details);
    }

    @Test
    @DisplayName("flicking onto the target and hitting at once flags after more samples than a sweep")
    void flickTriggerbot() {
        Fixture fixture = fixture(true);
        fixture.prime();
        for (int i = 0; i < 4; i++) {
            fixture.hit(ON);
            fixture.look(ON, 2);
            fixture.look(OFF, 12);
        }
        assertEquals(0, fixture.flags(), "four settled fast hits stay inside the buffer");
        fixture.hit(ON);
        fixture.look(ON, 2);
        assertEquals(1, fixture.flags(), fixture::details);
    }

    @Test
    @DisplayName("a delayed triggerbot (one tick) is still inside the reaction window")
    void delayedTriggerbot() {
        Fixture fixture = fixture(true);
        fixture.prime();
        for (int i = 0; i < 6; i++) {
            fixture.look(ON, 1);
            fixture.hit(ON);
            fixture.look(OFF, 12);
        }
        assertEquals(1, fixture.flags(), fixture::details);
    }

    @Test
    @DisplayName("a human who aims first and clicks after a reaction time never flags")
    void humanReaction() {
        Fixture fixture = fixture(true);
        fixture.prime();
        for (int i = 0; i < 40; i++) {
            fixture.look(ON, 4);
            fixture.hit(ON);
            fixture.look(OFF, 12);
        }
        assertEquals(0, fixture.flags(), fixture::details);
        assertEquals(0, fixture.check().fastSamples());
        assertTrue(fixture.check().slowSamples() >= 30);
    }

    @Test
    @DisplayName("a player who is already clicking when the crosshair arrives is not judged")
    void spamClicker() {
        Fixture fixture = fixture(true);
        fixture.prime();
        for (int i = 0; i < 40; i++) {
            // Clicking at air every other tick while turning in, then the hit lands on arrival.
            fixture.miss(OFF);
            fixture.look(OFF, 1);
            fixture.miss(OFF);
            fixture.look(OFF, 1);
            fixture.hit(ON);
            fixture.look(OFF, 3);
        }
        assertEquals(0, fixture.flags(), fixture::details);
        assertEquals(0, fixture.check().fastSamples());
    }

    @Test
    @DisplayName("flick shots mixed with near misses stay below the buffer")
    void humanFlicksWithMisses() {
        Fixture fixture = fixture(true);
        fixture.prime();
        for (int i = 0; i < 40; i++) {
            fixture.hit(ON);
            fixture.look(ON, 2);
            fixture.look(OFF, 12);
            // The next flick lands beside the hitbox: a swing at air close to the target.
            fixture.miss(BESIDE);
            fixture.look(OFF, 12);
        }
        assertEquals(0, fixture.flags(), fixture::details);
        assertTrue(fixture.check().missSwings() >= 30, fixture::details);
    }

    @Test
    @DisplayName("swings at air far away from the target are no human evidence")
    void farMissesDoNotHelp() {
        Fixture fixture = fixture(true);
        fixture.prime();
        float side = OFF;
        for (int i = 0; i < 3; i++) {
            fixture.hit(ON);
            side = -side;
            fixture.look(side, 2);
            fixture.miss(side * 3f);
            fixture.look(side, 12);
        }
        assertEquals(0, fixture.check().missSwings(), fixture::details);
        assertEquals(1, fixture.flags(), fixture::details);
    }

    @Test
    @DisplayName("swings while digging or using a block are not misses")
    void diggingSwings() {
        Fixture fixture = fixture(true);
        fixture.prime();
        BlockInteraction dig = new BlockInteraction();
        dig.assignDig(BlockInteraction.Kind.DIG_START, 0, 63, 2, BlockFace.UP, 1, 0L);
        fixture.player().checks().dispatchInteraction(dig);
        for (int i = 0; i < 10; i++) {
            fixture.miss(BESIDE);
        }
        assertEquals(0, fixture.check().missSwings());
    }

    @Test
    @DisplayName("holding the crosshair on the target and hitting on cooldown produces no sample")
    void tracking() {
        Fixture fixture = fixture(true);
        fixture.prime();
        fixture.look(ON, 12);
        for (int i = 0; i < 40; i++) {
            fixture.hit(ON);
            fixture.look(ON, 12);
        }
        assertEquals(0, fixture.flags());
        assertEquals(0, fixture.check().fastSamples());
        assertEquals(0, fixture.check().slowSamples());
    }

    @Test
    @DisplayName("a target that stood under the crosshair inside the lag window is not provably off")
    void lagWindow() {
        Fixture fixture = fixture(true);
        fixture.prime();
        fixture.look(ON, 1);
        for (int i = 0; i < 20; i++) {
            // The attacker never turns; the server already moved the target away, but the client
            // may still render it under the crosshair (latency + interpolation).
            fixture.tracked().moveTo(TARGET, 0.5, FEET_Y, 3.5);
            fixture.look(ON, 12);
            fixture.tracked().moveTo(TARGET, 3.5, FEET_Y, 3.5);
            fixture.look(ON, 1);
            fixture.hit(ON);
        }
        assertEquals(0, fixture.flags(), fixture::details);
        assertEquals(0, fixture.check().fastSamples(), fixture::details);
    }

    @Test
    @DisplayName("the first hit on a new target has no history and is never judged")
    void newTarget() {
        Fixture fixture = fixture(true);
        fixture.look(OFF, 12);
        fixture.hit(ON);
        fixture.look(OFF, 2);
        assertEquals(0, fixture.check().fastSamples());
        assertEquals(0, fixture.flags());
    }

    @Test
    @DisplayName("clients without tick-end packets (pre-1.21.2) are not judged")
    void legacyClient() {
        Fixture fixture = fixture(false);
        fixture.prime();
        float side = OFF;
        for (int i = 0; i < 10; i++) {
            fixture.hit(ON);
            side = -side;
            fixture.look(side, 12);
        }
        assertEquals(0, fixture.flags());
        assertEquals(0, fixture.check().fastSamples());
    }
}
