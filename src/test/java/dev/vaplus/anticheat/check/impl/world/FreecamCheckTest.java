package dev.vaplus.anticheat.check.impl.world;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.world.BlockInteraction;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FreecamCheck (interactions against the sent look direction)")
class FreecamCheckTest {

    private static final double EYE = FreecamCheck.STANDING_EYE_HEIGHT;

    private record Fixture(Harness harness, PlayerData player, FreecamCheck check) {
    }

    private static Fixture fixture() {
        SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
        Harness harness = TestContexts.builder()
                .module(simulation)
                .module(new VisibilityChecksModule(simulation))
                .build();
        PlayerData player = harness.newPlayer();
        player.movement().applyPosition(0.5, 64.0, 0.5);
        return new Fixture(harness, player, player.checks().get(FreecamCheck.class));
    }

    /** Points the player's body rotation at a world position, as the client's camera would. */
    private static void lookAt(PlayerData player, double eyeHeight, double x, double y, double z) {
        double eyeX = player.movement().x();
        double eyeY = player.movement().y() + eyeHeight;
        double eyeZ = player.movement().z();
        double dx = x - eyeX;
        double dy = y - eyeY;
        double dz = z - eyeZ;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, Math.hypot(dx, dz)));
        player.movement().applyRotation(yaw, pitch);
    }

    private static void dig(PlayerData player, int x, int y, int z) {
        player.interaction().assignDig(BlockInteraction.Kind.DIG_START, x, y, z, BlockFace.UP, 0, 0L);
        player.checks().dispatchInteraction(player.interaction());
    }

    private static void place(PlayerData player, int x, int y, int z, float cursorX, float cursorY,
                              float cursorZ, boolean insideBlock) {
        player.interaction().assignPlace(x, y, z, BlockFace.UP, cursorX, cursorY, cursorZ,
                InteractionHand.MAIN_HAND, 0, insideBlock, 0L);
        player.checks().dispatchInteraction(player.interaction());
    }

    @Test
    @DisplayName("descriptor is the world-category freecam check registered by the visibility module")
    void descriptor() {
        Fixture fixture = fixture();
        assertNotNull(fixture.check());
        assertEquals("freecam", FreecamCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.WORLD, FreecamCheck.DESCRIPTOR.category());
    }

    @Test
    @DisplayName("digging the block under the crosshair never raises the buffer")
    void digMatchesLook() {
        Fixture fixture = fixture();
        lookAt(fixture.player(), EYE, 0.5, 64.5, 3.5);
        for (int i = 0; i < 10; i++) {
            dig(fixture.player(), 0, 64, 3);
        }
        assertEquals(0.0, fixture.check().buffer());
        assertEquals(0, fixture.check().violationCount());
    }

    @Test
    @DisplayName("digging a block behind the player flags after the buffer limit")
    void digBehindFlags() {
        Fixture fixture = fixture();
        lookAt(fixture.player(), EYE, 0.5, 64.5, -3.5);
        dig(fixture.player(), 0, 64, 3);
        dig(fixture.player(), 0, 64, 3);
        assertEquals(0, fixture.check().violationCount());
        dig(fixture.player(), 0, 64, 3);
        assertEquals(1, fixture.check().violationCount());
        String details = fixture.harness().alerts.alerts().get(0).details();
        assertTrue(details.contains("kind=DIG"), details);
        assertTrue(details.contains("ray=miss"), details);
    }

    @Test
    @DisplayName("digging the block under the crosshair is accepted at any pitch")
    void digAtEyeLevel() {
        Fixture fixture = fixture();
        lookAt(fixture.player(), EYE, 0.5, 64.5, 4.5);
        for (int i = 0; i < 10; i++) {
            dig(fixture.player(), 0, 64, 4);
        }
        assertEquals(0.0, fixture.check().buffer());
    }

    @Test
    @DisplayName("placement hit point under the crosshair never raises the buffer")
    void placeMatchesLook() {
        Fixture fixture = fixture();
        lookAt(fixture.player(), EYE, 0.5, 65.0, 3.5);
        for (int i = 0; i < 10; i++) {
            place(fixture.player(), 0, 64, 3, 0.5f, 1.0f, 0.5f, false);
        }
        assertEquals(0.0, fixture.check().buffer());
        assertEquals(0, fixture.check().violationCount());
    }

    @Test
    @DisplayName("placing while looking elsewhere flags after the buffer limit")
    void placeMismatchFlags() {
        Fixture fixture = fixture();
        lookAt(fixture.player(), EYE, -3.5, 65.0, 0.5); // looking west, placing south
        for (int i = 0; i < 3; i++) {
            place(fixture.player(), 0, 64, 3, 0.5f, 1.0f, 0.5f, false);
        }
        assertEquals(1, fixture.check().violationCount());
        String details = fixture.harness().alerts.alerts().get(0).details();
        assertTrue(details.contains("kind=PLACE"), details);
        assertTrue(details.contains("angle="), details);
    }

    @Test
    @DisplayName("the sneaking eye height is used for sneaking players")
    void sneakingEyeHeight() {
        Fixture fixture = fixture();
        fixture.player().pose().sneaking(true);
        lookAt(fixture.player(), FreecamCheck.SNEAKING_EYE_HEIGHT, 0.5, 64.5, 3.5);
        for (int i = 0; i < 10; i++) {
            dig(fixture.player(), 0, 64, 3);
        }
        assertEquals(0.0, fixture.check().buffer());
    }

    @Test
    @DisplayName("creative players and gliding players are not judged")
    void exemptions() {
        Fixture creative = fixture();
        creative.player().abilities().applyGameMode(GameMode.CREATIVE);
        lookAt(creative.player(), EYE, 0.5, 64.5, -3.5);
        for (int i = 0; i < 5; i++) {
            dig(creative.player(), 0, 64, 3);
        }
        assertEquals(0.0, creative.check().buffer());

        Fixture gliding = fixture();
        gliding.player().pose().gliding(true);
        lookAt(gliding.player(), EYE, 0.5, 64.5, -3.5);
        for (int i = 0; i < 5; i++) {
            dig(gliding.player(), 0, 64, 3);
        }
        assertEquals(0.0, gliding.check().buffer());
    }

    @Test
    @DisplayName("digging far beyond the interaction range is reported as a reach outlier")
    void farDigFlags() {
        Fixture fixture = fixture();
        lookAt(fixture.player(), EYE, 0.5, 64.5, 9.5);
        for (int i = 0; i < 3; i++) {
            dig(fixture.player(), 0, 64, 9);
        }
        assertEquals(1, fixture.check().violationCount());
        assertTrue(fixture.harness().alerts.alerts().get(0).details().contains("reach="));
    }

    @Test
    @DisplayName("placements flagged as inside the player's own hitbox are ignored")
    void insideBlockIgnored() {
        Fixture fixture = fixture();
        lookAt(fixture.player(), EYE, 0.5, 64.5, 3.5);
        for (int i = 0; i < 3; i++) {
            place(fixture.player(), 0, 64, 3, 0.5f, 1.0f, 0.5f, true);
        }
        assertEquals(0.0, fixture.check().buffer());
    }
}
