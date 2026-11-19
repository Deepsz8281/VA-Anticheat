package dev.vaplus.anticheat.check.impl.world;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EspCheck (aim at players hidden behind walls)")
class EspCheckTest {

    private static final int TARGET_ID = 7;
    private static final double TARGET_Y = 64.0;

    private record Fixture(Harness harness, PlayerData player, NearbyPlayers tracked, WorldMirror world,
                           EspCheck check) {
    }

    private static Fixture fixture() {
        SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
        VisibilityChecksModule visibility = new VisibilityChecksModule(simulation);
        Harness harness = TestContexts.builder()
                .module(simulation)
                .module(visibility)
                .build();
        PlayerData player = harness.newPlayer();
        player.movement().applyPosition(0.5, 64.0, 0.5);
        return new Fixture(harness, player, player.component(visibility.nearbyKey()),
                player.component(simulation.worldMirrorKey()), player.checks().get(EspCheck.class));
    }

    /** Air along the line of sight, optionally with a stone wall at z=5. */
    private static void corridor(WorldMirror world, boolean wall) {
        for (int z = 0; z <= 40; z++) {
            world.setBlock(new BlockPos(0, 65, z), VanillaBlocks.AIR);
        }
        if (wall) {
            world.setBlock(new BlockPos(0, 65, 5), VanillaBlocks.STONE);
        }
    }

    private static void aimAt(PlayerData player, double targetZ) {
        double eyeX = player.movement().x();
        double eyeY = player.movement().y() + FreecamCheck.STANDING_EYE_HEIGHT;
        double eyeZ = player.movement().z();
        double dx = 0.5 - eyeX;
        double dy = TARGET_Y + EspCheck.TARGET_HEIGHT - eyeY;
        double dz = targetZ - eyeZ;
        player.movement().applyRotation(
                (float) Math.toDegrees(Math.atan2(-dx, dz)),
                (float) Math.toDegrees(Math.atan2(-dy, Math.hypot(dx, dz))));
    }

    /**
     * Runs {@code ticks} client ticks. The target moves {@code step} blocks along +Z every other
     * tick (as a walking player would) and the viewer optionally keeps the crosshair on it.
     */
    private static void run(Fixture fixture, int ticks, double step, boolean followAim) {
        double z = 10.5;
        fixture.tracked().spawn(TARGET_ID, 0.5, TARGET_Y, z);
        for (int tick = 0; tick < ticks; tick++) {
            if (tick % 2 == 0) {
                z += step;
                fixture.tracked().moveTo(TARGET_ID, 0.5, TARGET_Y, z);
            }
            if (followAim) {
                aimAt(fixture.player(), z);
            }
            fixture.player().onClientTick();
        }
    }

    @Test
    @DisplayName("descriptor is the experimental combat-category esp check")
    void descriptor() {
        Fixture fixture = fixture();
        assertNotNull(fixture.check());
        assertEquals("esp", EspCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.COMBAT, EspCheck.DESCRIPTOR.category());
        assertTrue(EspCheck.DESCRIPTOR.experimental());
    }

    @Test
    @DisplayName("following a moving player through a wall flags")
    void hiddenMovingTargetFlags() {
        Fixture fixture = fixture();
        corridor(fixture.world(), true);
        run(fixture, 120, 0.3, true);

        assertTrue(fixture.check().violationCount() >= 1,
                () -> "buffer=" + fixture.check().buffer() + " streak=" + fixture.check().streak());
        String details = fixture.harness().alerts.alerts().get(0).details();
        assertTrue(details.contains("entity=" + TARGET_ID), details);
        assertTrue(details.contains("movement="), details);
    }

    @Test
    @DisplayName("a visible target never flags even when followed for a long time")
    void visibleTargetDoesNotFlag() {
        Fixture fixture = fixture();
        corridor(fixture.world(), false);
        run(fixture, 200, 0.3, true);
        assertEquals(0, fixture.check().violationCount());
        assertEquals(0.0, fixture.check().buffer());
    }

    @Test
    @DisplayName("aiming somewhere else never flags")
    void aimAwayDoesNotFlag() {
        Fixture fixture = fixture();
        corridor(fixture.world(), true);
        fixture.player().movement().applyRotation(180f, 0f);
        run(fixture, 200, 0.3, false);
        assertEquals(0, fixture.check().violationCount());
        assertEquals(0.0, fixture.check().buffer());
    }

    @Test
    @DisplayName("a hidden target that does not move never satisfies the movement requirement")
    void staticTargetDoesNotFlag() {
        Fixture fixture = fixture();
        corridor(fixture.world(), true);
        run(fixture, 200, 0.0, true);
        assertEquals(0, fixture.check().violationCount());
        assertEquals(0.0, fixture.check().buffer());
    }

    @Test
    @DisplayName("without mirrored blocks on the path the check stays silent")
    void unknownWorldDoesNotFlag() {
        Fixture fixture = fixture();
        run(fixture, 200, 0.3, true);
        assertEquals(0, fixture.check().violationCount());
        assertEquals(0.0, fixture.check().buffer());
    }

    @Test
    @DisplayName("spectators are exempt through the combat category")
    void spectatorExempt() {
        Fixture fixture = fixture();
        fixture.player().abilities().applyGameMode(GameMode.SPECTATOR);
        corridor(fixture.world(), true);
        run(fixture, 200, 0.3, true);
        assertEquals(0, fixture.check().violationCount());
    }

    @Test
    @DisplayName("a destroyed entity is no longer considered")
    void destroyedEntityIsForgotten() {
        Fixture fixture = fixture();
        corridor(fixture.world(), true);
        fixture.tracked().spawn(TARGET_ID, 0.5, TARGET_Y, 10.5);
        fixture.tracked().remove(TARGET_ID);
        aimAt(fixture.player(), 10.5);
        for (int tick = 0; tick < 100; tick++) {
            fixture.player().onClientTick();
        }
        assertEquals(0, fixture.check().violationCount());
    }

    @Test
    @DisplayName("NearbyPlayers keeps player positions consistent over spawn/move/remove/clear")
    void nearbyPlayersComponent() {
        NearbyPlayers tracked = new NearbyPlayers();
        assertTrue(tracked.isEmpty());

        tracked.spawn(4, 1.0, 2.0, 3.0);
        tracked.spawn(5, 4.0, 5.0, 6.0);
        assertEquals(2, tracked.size());
        assertEquals(0, tracked.indexOf(4));
        assertEquals(1, tracked.indexOf(5));

        tracked.moveBy(4, 0.5, 0.0, -0.5);
        assertEquals(1.5, tracked.x(0), 1e-9);
        assertEquals(2.0, tracked.y(0), 1e-9);
        assertEquals(2.5, tracked.z(0), 1e-9);
        tracked.moveTo(4, 9.0, 9.0, 9.0);
        assertEquals(9.0, tracked.x(0), 1e-9);

        tracked.moveBy(99, 1, 1, 1);
        tracked.moveTo(99, 1, 1, 1);
        assertEquals(2, tracked.size(), "unknown ids are ignored");

        tracked.spawn(4, 7.0, 7.0, 7.0);
        assertEquals(2, tracked.size(), "a duplicate spawn replaces the entry");
        assertEquals(7.0, tracked.x(tracked.indexOf(4)), 1e-9);

        assertTrue(tracked.remove(4));
        assertEquals(1, tracked.size());
        assertEquals(5, tracked.id(0), "the last entry moved into the hole");
        assertTrue(!tracked.remove(4));

        tracked.clear();
        assertTrue(tracked.isEmpty());

        for (int id = 0; id < NearbyPlayers.MAX_TRACKED + 20; id++) {
            tracked.spawn(id, 0, 0, 0);
        }
        assertEquals(NearbyPlayers.MAX_TRACKED, tracked.size(), "capacity is bounded");
    }
}
