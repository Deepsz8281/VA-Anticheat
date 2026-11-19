package dev.vaplus.anticheat.check.impl.world;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ScaffoldCheck (hidden face / off-face hit point)")
class ScaffoldCheckTest {

    private static final class Fixture {
        final TestContexts.Harness harness;
        final PlayerData player;
        final WorldMirror world;
        final ScaffoldCheck check;

        /** Player standing at (0.5, 64, 0.5) on a stone block at (0, 63, 0); stone at (1, 63, 0). */
        Fixture() {
            SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
            harness = TestContexts.builder().module(simulation).module(new VisibilityChecksModule(simulation)).build();
            player = harness.newPlayer();
            world = player.component(simulation.worldMirrorKey());
            set(0, 63, 0, VanillaBlocks.STONE);
            set(1, 63, 0, VanillaBlocks.STONE);
            at(0.5, 64.0, 0.5);
            at(0.5, 64.0, 0.5);
            check = player.checks().get(ScaffoldCheck.class);
        }

        void set(int x, int y, int z, BlockKey key) {
            world.setBlock(new BlockPos(x, y, z), key);
        }

        void at(double x, double y, double z) {
            player.movement().applyPosition(x, y, z);
        }

        void place(int x, int y, int z, BlockFace face, float cx, float cy, float cz) {
            player.interaction().assignPlace(x, y, z, face, cx, cy, cz, InteractionHand.MAIN_HAND, 0, false, 0L);
            player.checks().dispatchInteraction(player.interaction());
        }
    }

    @Test
    @DisplayName("descriptor: id scaffold, experimental, registered in the visibility module")
    void descriptor() {
        Fixture f = new Fixture();
        assertEquals("scaffold", ScaffoldCheck.DESCRIPTOR.id());
        assertTrue(ScaffoldCheck.DESCRIPTOR.experimental());
        assertTrue(f.check != null);
    }

    @Test
    @DisplayName("legit: top face from above, side face from beside, hit points on the face")
    void legitPlacements() {
        Fixture f = new Fixture();
        for (int i = 0; i < 10; i++) {
            f.place(1, 63, 0, BlockFace.UP, 0.3f, 1.0f, 0.6f);
            // the player at x 0.5 sees the WEST face of (1,63,0) (eye x <= 1)
            f.place(1, 63, 0, BlockFace.WEST, 0.0f, 0.5f, 0.5f);
        }
        assertEquals(0, f.check.violationCount());
        assertEquals(0.0, f.check.buffer());
    }

    @Test
    @DisplayName("legit: bridging backwards over the edge clicks the visible side of the block below")
    void backwardsBridge() {
        Fixture f = new Fixture();
        // feet at the east edge of (0,63,0), placing the next block east against its EAST face
        f.at(0.95, 64.0, 0.5);
        f.at(1.02, 64.0, 0.5);
        for (int i = 0; i < 6; i++) {
            f.place(0, 63, 0, BlockFace.EAST, 1.0f, 0.4f, 0.5f);
        }
        assertEquals(0, f.check.violationCount());
    }

    @Test
    @DisplayName("the previous position counts: moving past the plane within one tick does not flag")
    void interpolatedEye() {
        Fixture f = new Fixture();
        f.at(1.1, 64.0, 0.5);
        f.at(0.9, 64.0, 0.5);
        for (int i = 0; i < 6; i++) {
            f.place(0, 63, 0, BlockFace.EAST, 1.0f, 0.4f, 0.5f);
        }
        assertEquals(0, f.check.violationCount());
    }

    @Test
    @DisplayName("cheat: clicking the far side of the block below flags after the buffer")
    void hiddenFace() {
        Fixture f = new Fixture();
        // player at x 0.5 on (0,63,0) clicks the EAST face of (1,63,0) whose plane is at x = 2
        for (int i = 0; i < 4; i++) {
            f.place(1, 63, 0, BlockFace.EAST, 1.0f, 0.5f, 0.5f);
        }
        assertTrue(f.check.violationCount() >= 1);
    }

    @Test
    @DisplayName("cheat: clicking the bottom face of the block under the feet flags")
    void bottomFace() {
        Fixture f = new Fixture();
        for (int i = 0; i < 4; i++) {
            f.place(0, 63, 0, BlockFace.DOWN, 0.5f, 0.0f, 0.5f);
        }
        assertTrue(f.check.violationCount() >= 1);
    }

    @Test
    @DisplayName("cheat: block-centre hit points on a visible face flag")
    void centreCursor() {
        Fixture f = new Fixture();
        for (int i = 0; i < 4; i++) {
            f.place(1, 63, 0, BlockFace.UP, 0.5f, 0.5f, 0.5f);
        }
        assertTrue(f.check.violationCount() >= 1);
    }

    @Test
    @DisplayName("not judged: unknown, non-full-cube, pending targets, inside-block and creative")
    void notJudged() {
        Fixture f = new Fixture();
        // unknown block (not mirrored), within reach
        f.place(0, 62, 1, BlockFace.DOWN, 0.5f, 0.5f, 0.5f);
        // a non-full-cube block is not judged by the geometric signals
        f.set(1, 63, 0, VanillaBlocks.WHITE_CARPET);
        f.place(1, 63, 0, BlockFace.EAST, 0.5f, 0.5f, 0.5f);
        // pending block change
        f.set(1, 63, 0, VanillaBlocks.STONE);
        f.world.pending().markBlock(1, 63, 0);
        f.place(1, 63, 0, BlockFace.EAST, 0.5f, 0.5f, 0.5f);
        // inside-block placement
        f.player.interaction().assignPlace(0, 63, 0, BlockFace.DOWN, 0.5f, 0.5f, 0.5f,
                InteractionHand.MAIN_HAND, 0, true, 0L);
        f.player.checks().dispatchInteraction(f.player.interaction());
        // creative
        f.player.abilities().applyGameMode(GameMode.CREATIVE);
        for (int i = 0; i < 4; i++) {
            f.place(0, 63, 0, BlockFace.DOWN, 0.5f, 0.5f, 0.5f);
        }
        assertEquals(0.0, f.check.buffer());
        assertEquals(0, f.check.violationCount());
    }

    /** Rotation that looks from the standing eye at (0.5, 64, 0.5) to an absolute point. */
    private static float[] lookAt(double x, double y, double z) {
        double dx = x - 0.5;
        double dy = y - (64.0 + ScaffoldCheck.MAX_EYE_HEIGHT);
        double dz = z - 0.5;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{yaw, pitch};
    }

    private static void rotate(Fixture f, float fromYaw, float fromPitch, float toYaw, float toPitch) {
        dev.vaplus.anticheat.movement.LookChange change = new dev.vaplus.anticheat.movement.LookChange();
        change.assign(fromYaw, fromPitch, toYaw, toPitch, false, 0L);
        f.player.checks().dispatchRotation(change);
    }

    @Test
    @DisplayName("ray: looking at the hit point is legit, placing while looking straight ahead flags")
    void ray() {
        Fixture f = new Fixture();
        float[] look = lookAt(1.5, 64.0, 0.5);
        f.player.movement().applyRotation(look[0], look[1]);
        f.player.movement().applyRotation(look[0], look[1]);
        for (int i = 0; i < 20; i++) {
            f.place(1, 63, 0, BlockFace.UP, 0.5f, 1.0f, 0.5f);
            f.player.onClientTick();
        }
        assertEquals(0, f.check.violationCount(), () -> "alerts: " + f.harness.alerts.alerts());

        // Scaffold without rotations: the camera looks at the horizon (east, away from the block's top).
        f.player.movement().applyRotation(-90f, 0f);
        f.player.movement().applyRotation(-90f, 0f);
        for (int i = 0; i < 4; i++) {
            f.place(1, 63, 0, BlockFace.UP, 0.5f, 1.0f, 0.5f);
            f.player.onClientTick();
        }
        assertTrue(f.check.violationCount() >= 1, () -> "alerts: " + f.harness.alerts.alerts());
    }

    @Test
    @DisplayName("ray: a turn inside the tick is covered by the rotation sent after the placement")
    void rayCoversTurnWithinTick() {
        Fixture f = new Fixture();
        float[] look = lookAt(1.5, 64.0, 0.5);
        for (int i = 0; i < 20; i++) {
            // Sent rotation before: 20 degrees off; the camera reached the block during the tick.
            f.player.movement().applyRotation(look[0] - 20f, look[1] - 10f);
            f.place(1, 63, 0, BlockFace.UP, 0.5f, 1.0f, 0.5f);
            // a human never repeats a turn exactly (duplicate-rotation)
            float end = look[0] + 5f + i * 0.01f;
            rotate(f, look[0] - 20f, look[1] - 10f, end, look[1] + 2f);
            f.player.movement().applyRotation(end, look[1] + 2f);
            f.player.onClientTick();
        }
        assertEquals(0, f.check.violationCount(), () -> "alerts: " + f.harness.alerts.alerts());
    }

    @Test
    @DisplayName("air: clicking air flags at once; a block the player just placed is not judged")
    void airPlace() {
        Fixture f = new Fixture();
        // own placement on top of (1,63,0) creates (1,64,0), still air in the mirror
        f.place(1, 63, 0, BlockFace.UP, 0.5f, 1.0f, 0.5f);
        f.set(1, 64, 0, VanillaBlocks.AIR);
        for (int i = 0; i < 5; i++) {
            f.place(1, 64, 0, BlockFace.UP, 0.5f, 1.0f, 0.5f);
        }
        assertEquals(0, f.check.violationCount());

        f.set(2, 63, 0, VanillaBlocks.AIR);
        f.place(2, 63, 0, BlockFace.WEST, 0.0f, 0.5f, 0.5f);
        f.place(2, 63, 0, BlockFace.WEST, 0.0f, 0.5f, 0.5f);
        assertEquals(1, f.check.violationCount(), () -> "alerts: " + f.harness.alerts.alerts());
        assertTrue(ScaffoldCheck.unclickable(VanillaBlocks.WATER_BLOCK));
        assertFalse(ScaffoldCheck.unclickable(VanillaBlocks.STONE));
    }

    @Test
    @DisplayName("reach: a block beyond the block interaction range flags")
    void reach() {
        Fixture f = new Fixture();
        f.set(6, 63, 0, VanillaBlocks.STONE);
        for (int i = 0; i < 3; i++) {
            f.place(6, 63, 0, BlockFace.UP, 0.5f, 1.0f, 0.5f);
        }
        assertTrue(f.check.violationCount() >= 1, () -> "alerts: " + f.harness.alerts.alerts());
    }

    @Test
    @DisplayName("snap: rotate away, place, rotate straight back flags on the third time; turning and staying does not")
    void snap() {
        Fixture f = new Fixture();
        rotate(f, 0f, 0f, 1f, 0f);
        f.player.onClientTick();
        for (int i = 0; i < 6; i++) {
            rotate(f, 1f, 0f, 120f, 80f);
            f.place(1, 63, 0, BlockFace.UP, 0.5f, 1.0f, 0.5f);
            f.player.onClientTick();
            rotate(f, 120f, 80f, 122f, 81f);
            f.player.onClientTick();
            rotate(f, 122f, 81f, 1f, 0f);
            f.player.onClientTick();
            for (int k = 0; k < 10; k++) {
                f.player.onClientTick();
            }
        }
        assertTrue(f.check.violationCount() >= 1, () -> "alerts: " + f.harness.alerts.alerts());

        Fixture legit = new Fixture();
        float yaw = 0f;
        for (int i = 0; i < 10; i++) {
            rotate(legit, yaw, 0f, yaw + 120f, 70f);
            legit.place(1, 63, 0, BlockFace.UP, 0.5f, 1.0f, 0.5f);
            legit.player.onClientTick();
            rotate(legit, yaw + 120f, 70f, yaw + 125f, 0f);
            legit.player.onClientTick();
            yaw += 125f;
        }
        assertEquals(0, legit.check.violationCount(), () -> "alerts: " + legit.harness.alerts.alerts());
    }

    @Test
    @DisplayName("geometry helpers: eye height range and face planes")
    void helpers() {
        assertFalse(ScaffoldCheck.faceVisible(BlockFace.UP, 0, 63, 0, 0.5, 62.0, 0.5, 0.0));
        assertTrue(ScaffoldCheck.faceVisible(BlockFace.UP, 0, 63, 0, 0.5, 62.4, 0.5, 0.0));
        assertTrue(ScaffoldCheck.faceVisible(BlockFace.DOWN, 0, 66, 0, 0.5, 64.0, 0.5, 0.0));
        assertFalse(ScaffoldCheck.faceVisible(BlockFace.DOWN, 0, 63, 0, 0.5, 64.0, 0.5, 0.0));
        assertTrue(ScaffoldCheck.faceVisible(BlockFace.OTHER, 0, 63, 0, 0.5, 64.0, 0.5, 0.0));
        assertTrue(ScaffoldCheck.cursorOnFace(BlockFace.NORTH, 0.3f, 0.2f, 0.0f, 0.02));
        assertFalse(ScaffoldCheck.cursorOnFace(BlockFace.SOUTH, 0.3f, 0.2f, 0.5f, 0.02));
    }
}
