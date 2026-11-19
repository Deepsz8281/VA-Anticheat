package dev.vaplus.anticheat.check.impl.packet;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.impl.movement.MovementChecksModule;
import dev.vaplus.anticheat.check.impl.movement.VehicleCheck;
import dev.vaplus.anticheat.check.impl.movement.VehicleCheckAccess;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.world.BlockInteraction;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MultiActionsCheck (using + dig/swing, closing while moving, block and entity, rowing)")
class MultiActionsCheckTest {

    private final TestContexts.Harness harness;
    private final PlayerData player;
    private final MultiActionsCheck check;
    private int sequence;

    MultiActionsCheckTest() {
        SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
        harness = TestContexts.builder()
                .module(simulation)
                .module(new MovementChecksModule(simulation))
                .check(MultiActionsCheck.DESCRIPTOR, MultiActionsCheck::new)
                .build();
        player = harness.newPlayer();
        player.enableTickEndMode();
        player.pose().applyInput(false, false, false, false, false, false, false, 0L);
        check = player.checks().get(MultiActionsCheck.class);
    }

    private boolean flagged(String prefix) {
        return harness.alerts.alerts().stream().anyMatch(a -> a.check() == check && a.details().startsWith(prefix));
    }

    private void ticks(int count) {
        for (int i = 0; i < count; i++) {
            player.onClientTick();
        }
    }

    /** Server sets the hand-active flag; the client acknowledges it. */
    private void startUsing() {
        List<Runnable> tasks = new ArrayList<>();
        check.onUsingFlag(true, tasks);
        tasks.forEach(Runnable::run);
        for (int id : harness.lastTransport().markerIds()) {
            player.acks().onPong(id);
        }
    }

    private void interaction(BlockInteraction.Kind kind) {
        BlockInteraction interaction = new BlockInteraction();
        if (kind == BlockInteraction.Kind.PLACE) {
            interaction.assignPlace(0, 64, 0, BlockFace.UP, 0.5f, 1f, 0.5f, InteractionHand.MAIN_HAND, ++sequence, false, 0L);
        } else {
            interaction.assignDig(kind, 0, 63, 0, BlockFace.UP, ++sequence, 0L);
        }
        player.checks().dispatchInteraction(interaction);
    }

    @Test
    @DisplayName("descriptor: id multiactions, PACKET, experimental")
    void descriptor() {
        assertEquals("multiactions", MultiActionsCheck.DESCRIPTOR.id());
        assertEquals(CheckCategory.PACKET, MultiActionsCheck.DESCRIPTOR.category());
        assertTrue(MultiActionsCheck.DESCRIPTOR.experimental());
    }

    @Test
    @DisplayName("using-swing / using-break: swinging or digging during a confirmed use flags; a use ending in time does not")
    void whileUsing() {
        startUsing();
        check.onSwing();
        check.onUsingFlag(false, new ArrayList<>()); // eating ended on the client a moment earlier
        ticks(5);
        assertEquals(0, check.violationCount());
        startUsing();
        // experimental signal: needs a repeat before it flags
        for (int i = 0; i < 2; i++) {
            check.onSwing();
            ticks(5);
        }
        assertTrue(flagged("using-swing"), harness.alerts.alerts()::toString);
        for (int i = 0; i < 2; i++) {
            interaction(BlockInteraction.Kind.DIG_START);
            ticks(5);
        }
        assertTrue(flagged("using-break"), harness.alerts.alerts()::toString);
    }

    @Test
    @DisplayName("close-moving: closing a container with movement keys held flags")
    void closeMoving() {
        check.onCloseWindow();
        assertEquals(0, check.violationCount());
        player.pose().applyInput(true, false, false, false, false, false, false, 1L);
        check.onCloseWindow();
        assertTrue(flagged("close-moving"));
    }

    @Test
    @DisplayName("block-and-entity: a dig and an attack in one tick flag; in different ticks they do not")
    void blockAndEntity() {
        interaction(BlockInteraction.Kind.DIG_START);
        ticks(1);
        check.onEntityAction();
        ticks(1);
        assertEquals(0, check.violationCount());
        interaction(BlockInteraction.Kind.DIG_START);
        check.onEntityAction();
        assertTrue(flagged("block-and-entity"));
    }

    @Test
    @DisplayName("rowing: attacking while paddling a boat flags")
    void rowing() {
        VehicleCheck vehicle = player.checks().get(VehicleCheck.class);
        VehicleCheckAccess.ride(vehicle, player, 50);
        check.onEntityAction();
        ticks(1);
        assertEquals(0, check.violationCount(), harness.alerts.alerts()::toString);
        player.pose().applyInput(true, false, false, false, false, false, false, 2L);
        check.onEntityAction();
        assertTrue(flagged("rowing"), harness.alerts.alerts()::toString);
    }
}
