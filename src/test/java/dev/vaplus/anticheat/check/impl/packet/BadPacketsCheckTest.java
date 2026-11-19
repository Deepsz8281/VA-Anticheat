package dev.vaplus.anticheat.check.impl.packet;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.world.BlockInteraction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("BadPackets (cursors, dig sequences, poses)")
class BadPacketsCheckTest {

    private record Fixture(Harness harness, PlayerData player, BadPacketsCheck check) {
        void place(float cursorX, float cursorY, float cursorZ) {
            BlockInteraction interaction = new BlockInteraction();
            interaction.assignPlace(0, 64, 0, BlockFace.UP, cursorX, cursorY, cursorZ,
                    InteractionHand.MAIN_HAND, 1, false, 0L);
            player.checks().dispatchInteraction(interaction);
        }

        void dig(BlockInteraction.Kind kind, int x, int y, int z) {
            BlockInteraction interaction = new BlockInteraction();
            interaction.assignDig(kind, x, y, z, BlockFace.UP, 1, 0L);
            player.checks().dispatchInteraction(interaction);
        }
    }

    private static Fixture fixture() {
        Harness harness = TestContexts.builder()
                .check(BadPacketsCheck.DESCRIPTOR, BadPacketsCheck::new)
                .build();
        PlayerData player = harness.newPlayer();
        return new Fixture(harness, player, player.checks().get(BadPacketsCheck.class));
    }

    @Test
    @DisplayName("the check is registered under the packet category")
    void descriptor() {
        Fixture fixture = fixture();
        assertNotNull(fixture.check());
        assertEquals("badpackets", BadPacketsCheck.DESCRIPTOR.id());
    }

    @Test
    @DisplayName("a placement cursor outside the block box flags, a normal one does not")
    void cursor() {
        Fixture fixture = fixture();
        fixture.place(0.5f, 0.5f, 0.5f);
        fixture.place(1.0f, 0.0f, 0.0f);
        assertEquals(0, fixture.check().violationCount());

        // buffer 1.0: the second invalid cursor exceeds it
        fixture.place(1.4f, 0.5f, 0.5f);
        fixture.place(1.4f, 0.5f, 0.5f);
        assertEquals(1, fixture.check().violationCount(),
                () -> "details: " + fixture.harness().alerts.alerts());
    }

    @Test
    @DisplayName("dig finish/cancel needs a matching start at the same block")
    void digSequence() {
        Fixture fixture = fixture();
        fixture.dig(BlockInteraction.Kind.DIG_FINISH, 1, 64, 1);
        fixture.dig(BlockInteraction.Kind.DIG_FINISH, 1, 64, 1);
        assertEquals(1, fixture.check().violationCount(), "finish without a start");

        fixture.dig(BlockInteraction.Kind.DIG_START, 1, 64, 1);
        fixture.dig(BlockInteraction.Kind.DIG_FINISH, 1, 64, 1);
        assertEquals(1, fixture.check().violationCount(), "a matching pair is fine");

        fixture.dig(BlockInteraction.Kind.DIG_START, 1, 64, 1);
        fixture.dig(BlockInteraction.Kind.DIG_FINISH, 2, 64, 1);
        fixture.dig(BlockInteraction.Kind.DIG_START, 1, 64, 1);
        fixture.dig(BlockInteraction.Kind.DIG_FINISH, 2, 64, 1);
        assertEquals(2, fixture.check().violationCount(), "finish at another block");
    }

    @Test
    @DisplayName("sprinting while sneaking flags once; either alone does not")
    void pose() {
        Fixture fixture = fixture();
        fixture.player().pose().sprinting(true);
        fixture.player().onClientTick();
        assertEquals(0, fixture.check().violationCount());

        fixture.player().pose().sneaking(true);
        fixture.player().onClientTick();
        fixture.player().onClientTick();
        assertEquals(1, fixture.check().violationCount(),
                () -> "details: " + fixture.harness().alerts.alerts());
    }

    @Test
    @DisplayName("a hotbar slot outside 0..8 flags at once; valid slots do not")
    void heldSlot() {
        Fixture fixture = fixture();
        for (int slot = 0; slot <= 8; slot++) {
            fixture.check().onHeldSlot(slot);
        }
        assertEquals(0, fixture.check().violationCount());
        fixture.check().onHeldSlot(9);
        fixture.check().onHeldSlot(-1);
        assertEquals(2, fixture.check().violationCount(),
                () -> "details: " + fixture.harness().alerts.alerts());
    }
}
