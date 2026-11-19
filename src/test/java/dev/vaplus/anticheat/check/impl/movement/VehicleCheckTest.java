package dev.vaplus.anticheat.check.impl.movement;

import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.world.mirror.BlockKey;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("VehicleCheck (boat fly, vehicle speed, mount high jump)")
class VehicleCheckTest {

    private static final int SELF = 7;
    private static final int BOAT = 50;
    private static final int HORSE = 51;

    private static final class Fixture {
        final TestContexts.Harness harness;
        final PlayerData player;
        final WorldMirror world;
        final VehicleCheck check;
        double x = 0.5;
        double y = 64.0;
        double z = 0.5;

        /** {@code surface} at y=63 over x -40..40, z -3..3 (boats float at y 64); stone below, air above. */
        Fixture(BlockKey surface) {
            SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
            harness = TestContexts.builder().module(simulation).module(new MovementChecksModule(simulation)).build();
            player = harness.newPlayer();
            player.enableTickEndMode();
            world = player.component(simulation.worldMirrorKey());
            for (int bx = -40; bx <= 40; bx++) {
                for (int bz = -3; bz <= 3; bz++) {
                    for (int by = 60; by <= 90; by++) {
                        BlockKey key = by < 63 ? VanillaBlocks.STONE : by == 63 ? surface : VanillaBlocks.AIR;
                        world.setBlock(new BlockPos(bx, by, bz), key);
                    }
                }
            }
            check = player.checks().get(VehicleCheck.class);
            check.onJoin(SELF);
        }

        void ride(int vehicle, int kind) {
            check.remember(vehicle, kind);
            check.onPassengers(vehicle, new int[]{SELF});
        }

        void move(double dx, double dy, double dz) {
            player.onClientTick();
            x += dx;
            y += dy;
            z += dz;
            check.onVehicleMove(x, y, z);
        }
    }

    @Test
    @DisplayName("descriptor and kinds: every boat wood type is a boat, horses, pigs")
    void kinds() {
        assertEquals("vehicle", VehicleCheck.DESCRIPTOR.id());
        // EntityTypes loads its registry through the global PacketEvents API.
        dev.vaplus.anticheat.testutil.FakePacketEvents.install(
                com.github.retrooper.packetevents.manager.server.ServerVersion.V_1_21_4);
        try {
            assertKinds();
        } finally {
            dev.vaplus.anticheat.testutil.FakePacketEvents.uninstall();
        }
    }

    private static void assertKinds() {
        assertEquals(VehicleCheck.KIND_BOAT, VehicleCheck.kindOf(EntityTypes.BOAT));
        assertEquals(VehicleCheck.KIND_BOAT, VehicleCheck.kindOf(EntityTypes.OAK_BOAT));
        assertEquals(VehicleCheck.KIND_BOAT, VehicleCheck.kindOf(EntityTypes.CHEST_BOAT));
        assertEquals(VehicleCheck.KIND_HORSE, VehicleCheck.kindOf(EntityTypes.HORSE));
        assertEquals(VehicleCheck.KIND_OTHER, VehicleCheck.kindOf(EntityTypes.PIG));
        assertEquals(VehicleCheck.KIND_NONE, VehicleCheck.kindOf(EntityTypes.LLAMA));
        assertEquals(VehicleCheck.KIND_NONE, VehicleCheck.kindOf(EntityTypes.MINECART));
    }

    @Test
    @DisplayName("legit: boat at full water speed, horse jumps")
    void legit() {
        Fixture f = new Fixture(VanillaBlocks.WATER_BLOCK);
        f.ride(BOAT, VehicleCheck.KIND_BOAT);
        for (int i = 0; i < 60; i++) {
            f.move(0.4, 0.0, 0.0);
        }
        assertEquals(0, f.check.violationCount());

        Fixture h = new Fixture(VanillaBlocks.STONE);
        h.ride(HORSE, VehicleCheck.KIND_HORSE);
        for (int jump = 0; jump < 3; jump++) {
            double vy = 0.9;
            double height = 0;
            for (int i = 0; i < 12 && height + vy > 0; i++) {
                h.move(0.5, vy, 0.0);
                height += vy;
                vy = (vy - 0.08) * 0.98;
            }
            h.move(0.5, -height, 0.0);
            for (int i = 0; i < 5; i++) {
                h.move(0.5, 0.0, 0.0);
            }
        }
        assertEquals(0, h.check.violationCount());
    }

    @Test
    @DisplayName("cheat: boat speed on water flags")
    void boatSpeed() {
        Fixture f = new Fixture(VanillaBlocks.WATER_BLOCK);
        f.ride(BOAT, VehicleCheck.KIND_BOAT);
        for (int i = 0; i < 40; i++) {
            f.move(1.5, 0.0, 0.0);
        }
        assertTrue(f.check.violationCount() >= 1);
    }

    @Test
    @DisplayName("cheat: boat hovering / rising in open air flags")
    void boatFly() {
        Fixture f = new Fixture(VanillaBlocks.WATER_BLOCK);
        f.ride(BOAT, VehicleCheck.KIND_BOAT);
        for (int i = 0; i < 12; i++) {
            f.move(0.1, 0.0, 0.0);
        }
        for (int i = 0; i < 60; i++) {
            f.move(0.1, 0.25, 0.0);
        }
        assertTrue(f.check.violationCount() >= 1);
    }

    @Test
    @DisplayName("not judged: ice surface (fast boats), server corrections, unknown vehicle kind")
    void notJudged() {
        Fixture ice = new Fixture(VanillaBlocks.BLUE_ICE);
        ice.ride(BOAT, VehicleCheck.KIND_BOAT);
        for (int i = 0; i < 60; i++) {
            ice.move(3.0, 0.0, 0.0);
        }
        assertEquals(0, ice.check.violationCount());

        Fixture corrected = new Fixture(VanillaBlocks.WATER_BLOCK);
        corrected.ride(BOAT, VehicleCheck.KIND_BOAT);
        for (int i = 0; i < 12; i++) {
            corrected.move(0.3, 0.0, 0.0);
        }
        corrected.check.onServerCorrection();
        corrected.move(20.0, 0.0, 0.0);
        assertEquals(0, corrected.check.violationCount());

        Fixture unknown = new Fixture(VanillaBlocks.WATER_BLOCK);
        unknown.check.onPassengers(99, new int[]{SELF});
        for (int i = 0; i < 40; i++) {
            unknown.move(2.0, 0.0, 0.0);
        }
        assertEquals(0, unknown.check.violationCount());
    }
}
