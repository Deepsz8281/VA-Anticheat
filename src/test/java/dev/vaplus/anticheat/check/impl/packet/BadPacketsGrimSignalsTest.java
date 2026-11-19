package dev.vaplus.anticheat.check.impl.packet;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction.Action;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.RecordingTransport;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.world.BlockInteraction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BadPackets: Grim-equivalent signals")
class BadPacketsGrimSignalsTest {

    private static final int SELF = 42;

    private final Harness harness = TestContexts.builder()
            .check(BadPacketsCheck.DESCRIPTOR, BadPacketsCheck::new)
            .build();
    private final RecordingTransport transport = new RecordingTransport();
    private final PlayerData player = harness.newPlayer(UUID.randomUUID(), "Steve", transport);
    private final BadPacketsCheck check = player.checks().get(BadPacketsCheck.class);

    BadPacketsGrimSignalsTest() {
        check.onJoin(SELF);
        player.movement().applyPosition(0.5, 64.0, 0.5);
        player.movement().applyRotation(0f, 0f);
    }

    private int flags() {
        return check.violationCount();
    }

    private List<String> details() {
        return harness.alerts.alerts().stream().map(a -> a.details()).toList();
    }

    private boolean flagged(String prefix) {
        return details().stream().anyMatch(d -> d.startsWith(prefix));
    }

    private void interact(BlockInteraction.Kind kind, int sequence) {
        BlockInteraction interaction = new BlockInteraction();
        if (kind == BlockInteraction.Kind.PLACE) {
            interaction.assignPlace(0, 64, 0, BlockFace.UP, 0.5f, 1f, 0.5f, InteractionHand.MAIN_HAND, sequence, false, 0L);
        } else if (kind == BlockInteraction.Kind.USE_ITEM) {
            interaction.assignUse(InteractionHand.MAIN_HAND, sequence, 0L);
        } else {
            interaction.assignDig(kind, 0, 63, 0, BlockFace.UP, sequence, 0L);
        }
        player.checks().dispatchInteraction(interaction);
    }

    @Test
    @DisplayName("A: selecting the already selected hotbar slot flags, switching does not")
    void duplicateSlot() {
        check.onHeldSlot(1);
        check.onHeldSlot(2);
        check.onHeldSlot(1);
        assertEquals(0, flags());
        check.onHeldSlot(1);
        assertTrue(flagged("duplicate-slot=1"), details()::toString);
    }

    @Test
    @DisplayName("C: leaving a bed while not sleeping flags; waking up just before is excused")
    void leaveBed() {
        check.onOwnPose(true);
        check.onEntityAction(Action.LEAVE_BED, SELF, 0);
        check.onOwnPose(false);
        check.onEntityAction(Action.LEAVE_BED, SELF, 0); // server woke the player a moment ago
        assertEquals(0, flags());
        harness.time.advance(5, TimeUnit.SECONDS);
        check.onEntityAction(Action.LEAVE_BED, SELF, 0);
        check.onEntityAction(Action.LEAVE_BED, SELF, 0);
        assertTrue(flagged("leave-bed"), details()::toString);
    }

    @Test
    @DisplayName("E: twenty movement packets without a position flag; nineteen do not; vehicles are exempt")
    void movementReminder() {
        for (int i = 0; i < 19; i++) {
            check.onNoPosition(i % 2 == 0);
        }
        check.onPosition(0.5, 64.0, 0.5);
        assertEquals(0, flags());
        player.vehicle().mount(7);
        for (int i = 0; i < 40; i++) {
            check.onNoPosition(true);
        }
        assertEquals(0, flags());
        player.vehicle().dismount();
        for (int i = 0; i < 20; i++) {
            check.onNoPosition(false);
        }
        assertTrue(flagged("movement-reminder"), details()::toString);
    }

    @Test
    @DisplayName("F/G: duplicate sprint or sneak states flag; the first state after a respawn is free")
    void duplicateSprintSneak() {
        check.onEntityAction(Action.START_SPRINTING, SELF, 0);
        check.onEntityAction(Action.STOP_SPRINTING, SELF, 0);
        check.onEntityAction(Action.START_SNEAKING, SELF, 0);
        check.onEntityAction(Action.STOP_SNEAKING, SELF, 0);
        check.onEntityAction(Action.START_SPRINTING, SELF, 0);
        check.onRespawn();
        check.onEntityAction(Action.START_SPRINTING, SELF, 0);
        assertEquals(0, flags());
        check.onEntityAction(Action.START_SPRINTING, SELF, 0);
        assertTrue(flagged("duplicate-sprint start"), details()::toString);
        check.onEntityAction(Action.STOP_SNEAKING, SELF, 0);
        check.onEntityAction(Action.STOP_SNEAKING, SELF, 0);
        assertTrue(flagged("duplicate-sneak stop"), details()::toString);
    }

    @Test
    @DisplayName("H: prediction sequences must grow; dig cancels carry 0; a respawn may restart the counter")
    void sequence() {
        interact(BlockInteraction.Kind.DIG_START, 1);
        interact(BlockInteraction.Kind.DIG_CANCEL, 0);
        interact(BlockInteraction.Kind.DIG_START, 2);
        interact(BlockInteraction.Kind.DIG_FINISH, 3);
        interact(BlockInteraction.Kind.USE_ITEM, 4);
        interact(BlockInteraction.Kind.PLACE, 6); // a dropped packet in between is fine
        check.onRespawn();
        interact(BlockInteraction.Kind.USE_ITEM, 1);
        interact(BlockInteraction.Kind.USE_ITEM, 2);
        assertEquals(0, flags(), details()::toString);
        interact(BlockInteraction.Kind.USE_ITEM, 2);
        assertTrue(flagged("sequence 2 after 2"), details()::toString);
        interact(BlockInteraction.Kind.DIG_START, 3);
        interact(BlockInteraction.Kind.DIG_CANCEL, 5);
        assertTrue(flagged("sequence dig-cancel carries 5"), details()::toString);
    }

    @Test
    @DisplayName("I: claiming to fly without permission flags; with permission or during a change it does not")
    void abilities() {
        player.abilities().applyAbilities(false, true, false, false, 0.05f, 0.1f);
        check.onAbilities(true);
        assertEquals(0, flags());
        player.abilities().applyAbilities(false, false, false, false, 0.05f, 0.1f);
        check.onAbilities(false);
        assertEquals(0, flags());
        check.onAbilities(true);
        assertTrue(flagged("abilities flying"), details()::toString);
    }

    @Test
    @DisplayName("J: a use-item rotation different from the tick's rotation flags (1.21.2+ clients)")
    void useRotation() {
        player.enableTickEndMode();
        player.movement().applyRotation(30f, 10f);
        check.onUseItemRotation(30f, 10f);
        player.onClientTick();
        assertEquals(0, flags());
        check.onUseItemRotation(90f, 45f); // silent rotation for the item use only
        player.onClientTick();
        assertTrue(flagged("use-rotation"), details()::toString);
    }

    @Test
    @DisplayName("K: spectating outside spectator mode flags")
    void spectate() {
        player.abilities().applyGameMode(GameMode.SPECTATOR);
        check.onSpectate();
        assertEquals(0, flags());
        player.abilities().applyGameMode(GameMode.SURVIVAL);
        check.onSpectate();
        assertTrue(flagged("spectate outside"), details()::toString);
    }

    @Test
    @DisplayName("M: respawning while alive flags; after death and after the end credits it does not")
    void respawn() {
        check.onHealth(0f);
        check.onRespawnRequest();
        check.onRespawn();
        check.onHealth(20f);
        check.onWinGame(1f);
        harness.time.advance(10, TimeUnit.SECONDS);
        check.onRespawnRequest();
        assertEquals(0, flags(), details()::toString);
        check.onRespawnRequest();
        check.onRespawnRequest();
        assertTrue(flagged("respawn while alive"), details()::toString);
    }

    @Test
    @DisplayName("O: keep-alive answers must match an id the server sent")
    void keepAlive() {
        check.onKeepAliveReceived(999L); // nothing tracked yet (plugin enabled mid-session)
        check.onKeepAliveSent(100L);
        check.onKeepAliveSent(200L);
        check.onKeepAliveReceived(200L);
        assertEquals(0, flags());
        check.onKeepAliveReceived(100L); // dropped together with the newer answer
        assertTrue(flagged("keepalive id=100"), details()::toString);
    }

    @Test
    @DisplayName("Q: entity actions for other entities or with impossible jump boosts flag")
    void entityAction() {
        check.onEntityAction(Action.START_JUMPING_WITH_HORSE, SELF, 90);
        assertEquals(0, flags());
        check.onEntityAction(Action.START_SPRINTING, SELF + 1, 0);
        assertTrue(flagged("entity-action action=START_SPRINTING entity=43"), details()::toString);
        check.onEntityAction(Action.START_JUMPING_WITH_HORSE, SELF, 500);
        check.onEntityAction(Action.OPEN_HORSE_INVENTORY, SELF, 5);
        assertEquals(3, flags(), details()::toString);
    }

    @Test
    @DisplayName("R: answering markers for seconds without any position flags; idle reminders keep it clean")
    void positionStarvation() {
        for (int second = 0; second < 6; second++) {
            for (int step = 0; step < 4; step++) {
                harness.time.advance(250, TimeUnit.MILLISECONDS);
                player.acks().sendMarker();
                List<Integer> ids = transport.markerIds();
                player.acks().onPong(ids.get(ids.size() - 1));
                check.onMarkerAnswered();
            }
            check.onPosition(0.5, 64.0, 0.5); // the vanilla 20-tick reminder
        }
        assertEquals(0, flags(), details()::toString);
        for (int step = 0; step < 40; step++) {
            harness.time.advance(250, TimeUnit.MILLISECONDS);
            player.acks().sendMarker();
            List<Integer> ids = transport.markerIds();
            player.acks().onPong(ids.get(ids.size() - 1));
            check.onMarkerAnswered();
        }
        assertTrue(flagged("position-starvation"), details()::toString);
    }

    @Test
    @DisplayName("V: a position that moved less than the send threshold before the reminder flags")
    void slowMove() {
        player.enableTickEndMode();
        check.onPosition(0.5, 64.0, 0.5);
        player.onClientTick();
        check.onPosition(0.6, 64.0, 0.5);
        player.movement().applyPosition(0.6, 64.0, 0.5);
        player.onClientTick();
        // after a teleport the client compares against an older position: two packets are skipped
        check.onTeleportConfirm();
        check.onPosition(0.6, 64.0, 0.5);
        check.onPosition(0.6, 64.0, 0.5);
        player.onClientTick();
        assertEquals(0, flags(), details()::toString);
        check.onPosition(0.6, 64.0, 0.5);
        player.onClientTick();
        check.onPosition(0.6, 64.0, 0.5);
        assertTrue(flagged("slow-move"), details()::toString);
    }

    @Test
    @DisplayName("X/Z: two sprint changes or two input packets in one tick flag (1.21.2+ clients)")
    void perTick() {
        player.enableTickEndMode();
        check.onEntityAction(Action.START_SPRINTING, SELF, 0);
        check.onPlayerInput();
        player.onClientTick();
        check.onEntityAction(Action.STOP_SPRINTING, SELF, 0);
        check.onPlayerInput();
        player.onClientTick();
        assertEquals(0, flags(), details()::toString);
        check.onEntityAction(Action.START_SPRINTING, SELF, 0);
        check.onEntityAction(Action.STOP_SPRINTING, SELF, 0);
        assertTrue(flagged("input-actions"), details()::toString);
        player.onClientTick();
        check.onPlayerInput();
        check.onPlayerInput();
        assertTrue(flagged("duplicate-input"), details()::toString);
    }
}
