package dev.vaplus.anticheat.check.impl.combat;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.impl.world.NearbyPlayers;
import dev.vaplus.anticheat.check.impl.world.VisibilityChecksModule;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("Combat checks (reach, killaura, autoclicker, through-wall)")
class CombatChecksTest {

    private static final int TARGET_ID = 21;
    private static final double FEET_Y = 64.0;

    private record Fixture(Harness harness, PlayerData player, NearbyPlayers tracked, WorldMirror world,
                           ReachCheck reach, KillauraCheck killaura, AutoclickerCheck autoclicker,
                           ThroughWallCheck throughWall) {
        void attack(int entityId) {
            attack(entityId, EntityAttack.Kind.ATTACK, System.nanoTime());
        }

        void attack(int entityId, EntityAttack.Kind kind, long nanos) {
            EntityAttack attack = new EntityAttack();
            attack.assign(entityId, kind, false, 0, 0, 0, nanos);
            player.checks().dispatchAttack(attack);
        }
    }

    private static Fixture fixture() {
        SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
        VisibilityChecksModule visibility = new VisibilityChecksModule(simulation);
        CombatChecksModule combat = new CombatChecksModule(simulation, visibility);
        Harness harness = TestContexts.builder()
                .module(simulation)
                .module(visibility)
                .module(combat)
                .build();
        PlayerData player = harness.newPlayer();
        player.movement().applyPosition(0.5, FEET_Y, 0.5);
        player.movement().applyRotation(0f, 0f);
        player.onClientTick();
        return new Fixture(harness, player, player.component(visibility.nearbyKey()),
                player.component(simulation.worldMirrorKey()), player.checks().get(ReachCheck.class),
                player.checks().get(KillauraCheck.class), player.checks().get(AutoclickerCheck.class),
                player.checks().get(ThroughWallCheck.class));
    }

    @Test
    @DisplayName("the module registers its combat checks with the combat category")
    void descriptors() {
        Fixture fixture = fixture();
        assertNotNull(fixture.reach());
        assertNotNull(fixture.killaura());
        assertNotNull(fixture.autoclicker());
        assertNotNull(fixture.throughWall());
        for (var descriptor : CombatChecksModule.descriptors()) {
            assertEquals(CheckCategory.COMBAT, descriptor.category(), descriptor.id());
        }
    }

    @Nested
    @DisplayName("Reach")
    class Reach {

        @Test
        @DisplayName("an attack 3.2 blocks in front flags; inside 3.0 does not")
        void distanceToHitbox() {
            Fixture fixture = fixture();
            fixture.tracked().spawn(TARGET_ID, 0.5, FEET_Y, 3.0);
            fixture.attack(TARGET_ID);
            assertEquals(0, fixture.reach().violationCount(), "2.2 blocks: in reach");

            fixture.tracked().moveTo(TARGET_ID, 0.5, FEET_Y, 4.2);
            // buffer 1.0: the second sample exceeds it
            fixture.attack(TARGET_ID);
            fixture.attack(TARGET_ID);
            assertEquals(1, fixture.reach().violationCount(),
                    () -> "details: " + fixture.harness().alerts.alerts());
        }

        @Test
        @DisplayName("sneaking lowers the eye and shrinks the target box, but not below reach")
        void sneakingTarget() {
            Fixture fixture = fixture();
            fixture.tracked().spawn(TARGET_ID, 0.5, FEET_Y, 3.2);
            fixture.tracked().setSneaking(TARGET_ID, true);
            fixture.attack(TARGET_ID);
            assertEquals(0, fixture.reach().violationCount(),
                    () -> "sneaking box is 1.5 high, eye 1.62: still in reach");
        }

        @Test
        @DisplayName("unknown entity ids and plain interactions never judge")
        void unknownTargetsAreIgnored() {
            Fixture fixture = fixture();
            fixture.attack(999);
            fixture.tracked().spawn(TARGET_ID, 0.5, FEET_Y, 6.0);
            fixture.attack(TARGET_ID, EntityAttack.Kind.INTERACT, System.nanoTime());
            assertEquals(0, fixture.reach().violationCount());
            assertEquals(0.0, fixture.reach().buffer());
        }
    }

    @Nested
    @DisplayName("Killaura")
    class Killaura {

        @Test
        @DisplayName("an attack straight ahead is silent, an attack behind the back flags after three samples")
        void lookDirection() {
            Fixture fixture = fixture();
            fixture.tracked().spawn(TARGET_ID, 0.5, FEET_Y, 4.5);
            fixture.attack(TARGET_ID);
            assertEquals(0, fixture.killaura().violationCount(), "target at yaw 0 is straight ahead");

            fixture.player().movement().applyRotation(180f, 0f);
            // buffer 3.0: four aligned samples exceed it
            for (int i = 0; i < 5; i++) {
                fixture.attack(TARGET_ID);
            }
            assertEquals(1, fixture.killaura().violationCount(),
                    () -> "details: " + fixture.harness().alerts.alerts());
        }

        @Test
        @DisplayName("two different targets in one client tick flag; the same target twice or one per tick does not")
        void multiTarget() {
            Fixture fixture = fixture();
            fixture.player().enableTickEndMode();
            fixture.tracked().spawn(TARGET_ID, 0.5, FEET_Y, 2.5);
            fixture.tracked().spawn(TARGET_ID + 1, 0.7, FEET_Y, 2.5);

            // Double click on one target, then a legit switch on the next tick.
            for (int i = 0; i < 20; i++) {
                fixture.attack(TARGET_ID);
                fixture.attack(TARGET_ID);
                fixture.killaura().onSwing();
                fixture.player().onClientTick();
                fixture.attack(TARGET_ID + 1);
                fixture.killaura().onSwing();
                fixture.player().onClientTick();
            }
            assertEquals(0, fixture.killaura().violationCount(),
                    () -> "details: " + fixture.harness().alerts.alerts());

            // buffer 3.0, weight 2.0: the second in-tick switch exceeds it
            for (int i = 0; i < 2; i++) {
                fixture.attack(TARGET_ID);
                fixture.attack(TARGET_ID + 1);
                fixture.killaura().onSwing();
                fixture.player().onClientTick();
            }
            assertEquals(1, fixture.killaura().violationCount(),
                    () -> "details: " + fixture.harness().alerts.alerts());
        }

        @Test
        @DisplayName("without tick-end packets (pre-1.21.2) the in-tick switch is not judged")
        void multiTargetNeedsTickEnd() {
            Fixture fixture = fixture();
            fixture.tracked().spawn(TARGET_ID, 0.5, FEET_Y, 2.5);
            fixture.tracked().spawn(TARGET_ID + 1, 0.7, FEET_Y, 2.5);
            for (int i = 0; i < 10; i++) {
                fixture.attack(TARGET_ID);
                fixture.attack(TARGET_ID + 1);
            }
            assertEquals(0, fixture.killaura().violationCount());
        }

        @Test
        @DisplayName("out-of-range targets are not judged")
        void tooFarIsIgnored() {
            Fixture fixture = fixture();
            fixture.tracked().spawn(TARGET_ID, 0.5, FEET_Y, 9.0);
            fixture.player().movement().applyRotation(180f, 0f);
            for (int i = 0; i < 10; i++) {
                fixture.attack(TARGET_ID);
            }
            assertEquals(0, fixture.killaura().violationCount());
        }
    }

    @Nested
    @DisplayName("Autoclicker")
    class Autoclicker {

        @Test
        @DisplayName("20 CPS at 0 ms jitter flags, human jitter and low CPS do not")
        void constantRate() {
            Fixture fixture = fixture();
            fixture.tracked().spawn(TARGET_ID, 0.5, FEET_Y, 2.0);
            long nanos = 1_000_000_000L;
            for (int i = 0; i < 25; i++) {
                fixture.attack(TARGET_ID, EntityAttack.Kind.ATTACK, nanos);
                nanos += 50_000_000L;
            }
            assertEquals(1, fixture.autoclicker().violationCount(),
                    () -> "details: " + fixture.harness().alerts.alerts());
        }

        @Test
        @DisplayName("noisy human clicking at 8 CPS stays silent")
        void humanClicking() {
            Fixture fixture = fixture();
            fixture.tracked().spawn(TARGET_ID, 0.5, FEET_Y, 2.0);
            long nanos = 1_000_000_000L;
            int[] jitter = {0, 18, 4, 25, 9, 31, 2, 15, 40, 7, 22, 11, 35, 5, 27, 13, 38, 8, 20, 3, 30, 16, 6, 24, 12};
            for (int value : jitter) {
                fixture.attack(TARGET_ID, EntityAttack.Kind.ATTACK, nanos);
                nanos += 125_000_000L + value * 1_000_000L;
            }
            assertEquals(0, fixture.autoclicker().violationCount());
        }
    }

    @Nested
    @DisplayName("ThroughWall")
    class ThroughWall {

        private void corridor(WorldMirror world, boolean wall) {
            for (int z = 0; z <= 20; z++) {
                world.setBlock(new BlockPos(0, 64, z), VanillaBlocks.AIR);
                world.setBlock(new BlockPos(0, 65, z), VanillaBlocks.AIR);
            }
            if (wall) {
                world.setBlock(new BlockPos(0, 65, 3), VanillaBlocks.STONE);
            }
        }

        @Test
        @DisplayName("a target fully behind a stone wall flags once")
        void behindWallFlags() {
            Fixture fixture = fixture();
            corridor(fixture.world(), true);
            fixture.tracked().spawn(TARGET_ID, 0.5, FEET_Y, 5.5);
            fixture.player().movement().applyRotation(0f, 0f);
            fixture.attack(TARGET_ID);
            fixture.attack(TARGET_ID);
            assertEquals(1, fixture.throughWall().violationCount(),
                    () -> "details: " + fixture.harness().alerts.alerts());
        }

        @Test
        @DisplayName("a visible target and a target inside min-distance stay silent")
        void visibleAndCloseTargets() {
            Fixture fixture = fixture();
            corridor(fixture.world(), false);
            fixture.tracked().spawn(TARGET_ID, 0.5, FEET_Y, 5.5);
            fixture.attack(TARGET_ID);
            assertEquals(0, fixture.throughWall().violationCount());

            corridor(fixture.world(), true);
            fixture.tracked().moveTo(TARGET_ID, 0.5, FEET_Y, 1.2);
            fixture.attack(TARGET_ID);
            assertEquals(0, fixture.throughWall().violationCount(), "0.7 blocks: no wall fits between");
        }

        @Test
        @DisplayName("without mirrored blocks the check cannot prove occlusion")
        void unknownWorldIsSilent() {
            Fixture fixture = fixture();
            fixture.tracked().spawn(TARGET_ID, 0.5, FEET_Y, 5.5);
            fixture.attack(TARGET_ID);
            assertEquals(0, fixture.throughWall().violationCount());
        }
    }

    @Test
    @DisplayName("spectators are exempt from every combat check")
    void spectatorExempt() {
        Fixture fixture = fixture();
        fixture.player().abilities().applyGameMode(GameMode.SPECTATOR);
        fixture.player().movement().applyRotation(180f, 0f);
        fixture.tracked().spawn(TARGET_ID, 0.5, FEET_Y, 4.0);
        for (int i = 0; i < 10; i++) {
            fixture.attack(TARGET_ID);
        }
        assertEquals(0, fixture.reach().violationCount());
        assertEquals(0, fixture.killaura().violationCount());
    }
}
