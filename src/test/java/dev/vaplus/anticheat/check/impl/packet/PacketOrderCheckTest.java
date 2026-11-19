package dev.vaplus.anticheat.check.impl.packet;

import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.TestContexts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PacketOrder (Grim PacketOrderA-P, TransactionOrder)")
class PacketOrderCheckTest {

    private final TestContexts.Harness harness = TestContexts.builder().module(new PacketChecksModule()).build();
    private final PlayerData player = harness.newPlayer();
    private final PacketOrderCheck check = player.checks().get(PacketOrderCheck.class);

    private boolean flagged(String prefix) {
        return harness.alerts.alerts().stream().anyMatch(a -> a.check() == check && a.details().startsWith(prefix));
    }

    /** Runs a tick twice (every signal needs a repeat). */
    private void twice(Runnable tick) {
        for (int i = 0; i < 2; i++) {
            tick.run();
            check.onMovement();
        }
    }

    /** A vanilla tick: slot, swap, attack, entity right click on both hands, dig, sprint, movement. */
    private void vanillaTick() {
        check.onSlotChange();
        check.onDig(DiggingAction.SWAP_ITEM_WITH_OFFHAND);
        check.onAttack();
        check.onInteract(true, 5, InteractionHand.MAIN_HAND, false);
        check.onInteract(false, 5, InteractionHand.MAIN_HAND, false);
        check.onUse(false);
        check.onInteract(true, 5, InteractionHand.OFF_HAND, false);
        check.onInteract(false, 5, InteractionHand.OFF_HAND, false);
        check.onUse(false);
        check.onDig(DiggingAction.START_DIGGING);
        check.onEntityAction(WrapperPlayClientEntityAction.Action.START_SPRINTING);
        check.onMovement();
        check.onTickEnd();
    }

    @Test
    @DisplayName("descriptor: id packetorder, PACKET, experimental")
    void descriptor() {
        assertEquals("packetorder", PacketOrderCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.PACKET, PacketOrderCheck.DESCRIPTOR.category());
        assertTrue(PacketOrderCheck.DESCRIPTOR.experimental());
    }

    @Test
    @DisplayName("vanilla tick orders never flag, also in tick-end mode")
    void vanilla() {
        player.enableTickEndMode();
        for (int i = 0; i < 20; i++) {
            vanillaTick();
            check.onUse(true); // next tick: block placement then use
            check.onUse(false);
            check.onMovement();
            check.onTickEnd();
        }
        assertEquals(0, check.violationCount(), harness.alerts.alerts()::toString);
    }

    @Test
    @DisplayName("interact-order and hand-order")
    void interactOrder() {
        twice(() -> check.onInteract(false, 5, InteractionHand.MAIN_HAND, false));
        assertTrue(flagged("interact-order"));
        twice(() -> {
            check.onInteract(true, 5, InteractionHand.OFF_HAND, false);
            check.onInteract(false, 5, InteractionHand.OFF_HAND, false);
        });
        assertTrue(flagged("hand-order"));
    }

    @Test
    @DisplayName("slot, after-pose and inventory orders")
    void slotPoseInventory() {
        twice(() -> {
            check.onAttack();
            check.onSlotChange();
        });
        assertTrue(flagged("slot"));
        twice(() -> {
            check.onEntityAction(WrapperPlayClientEntityAction.Action.START_SPRINTING);
            check.onAttack();
        });
        assertTrue(flagged("after-pose"));
        twice(() -> {
            check.onDig(DiggingAction.DROP_ITEM);
            check.onDig(DiggingAction.SWAP_ITEM_WITH_OFFHAND);
        });
        assertTrue(flagged("inventory"));
    }

    @Test
    @DisplayName("use-order: attack after use, use after attack without interaction, release after attack")
    void useOrder() {
        twice(() -> {
            check.onUse(false);
            check.onAttack();
        });
        assertTrue(flagged("use-order attack after use"));
        twice(() -> {
            check.onAttack();
            check.onUse(false);
        });
        assertTrue(flagged("use-order use after an attack"));
        twice(() -> {
            check.onAttack();
            check.onDig(DiggingAction.RELEASE_USE_ITEM);
        });
        assertTrue(flagged("use-order release after attack"));
    }

    @Test
    @DisplayName("tick-end: a game action between movement and tick end flags; teleport answers do not count")
    void tickEnd() {
        player.enableTickEndMode();
        for (int i = 0; i < 3; i++) {
            check.onTeleportConfirm();
            check.onMovement();
            check.onAttack();
            check.onTickEnd();
        }
        assertEquals(0, check.violationCount());
        for (int i = 0; i < 2; i++) {
            check.onMovement();
            check.onAttack();
            check.onTickEnd();
        }
        assertTrue(flagged("tick-end"));
    }

    @Test
    @DisplayName("chunk-ack: acknowledged between the markers is vanilla; before the batch or without one flags")
    void chunkAck() {
        List<Runnable> tasks = new ArrayList<>();
        check.onChunkBatchEnd(tasks);
        long first = player.acks().lastSentMarker();
        tasks.forEach(Runnable::run);
        ackUpTo(first);
        check.onChunkBatchAck();
        ackAll();
        assertEquals(0, check.violationCount(), harness.alerts.alerts()::toString);
        for (int i = 0; i < 2; i++) {
            tasks.clear();
            check.onChunkBatchEnd(tasks);
            tasks.forEach(Runnable::run);
            check.onChunkBatchAck(); // before the client could have read the batch
            ackAll();
        }
        assertTrue(flagged("chunk-ack"), harness.alerts.alerts()::toString);
    }

    private void ackUpTo(long marker) {
        for (int id : harness.lastTransport().markerIds()) {
            player.acks().onPong(id);
            if (player.acks().lastAckedMarker() >= marker) {
                return;
            }
        }
    }

    private void ackAll() {
        for (int id : harness.lastTransport().markerIds()) {
            player.acks().onPong(id);
        }
    }
}
