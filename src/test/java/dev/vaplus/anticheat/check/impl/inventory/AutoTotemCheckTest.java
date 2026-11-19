package dev.vaplus.anticheat.check.impl.inventory;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.component.HashedComponentPatchMap;
import com.github.retrooper.packetevents.protocol.item.HashedStack;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow.WindowClickType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.FakePacketEvents;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoTotemCheckTest {

    @BeforeAll
    static void installPackets() {
        FakePacketEvents.install(ServerVersion.V_1_21_5);
    }

    @AfterAll
    static void uninstallPackets() {
        FakePacketEvents.uninstall();
    }

    private static ItemStack totem() {
        return ItemStack.builder().type(ItemTypes.TOTEM_OF_UNDYING).amount(1).build();
    }

    private record Fixture(Harness harness, PlayerData player, AutoTotemCheck check) {
        void ticks(int count) {
            for (int i = 0; i < count; i++) {
                harness.time.advanceNanos(50_000_000L);
                player.onClientTick();
            }
        }

        void ack(List<Runnable> after) {
            after.forEach(Runnable::run);
            player.acks().onPong(harness.lastTransport().lastMarkerId());
        }

        void seed(int slot, ItemStack item) {
            List<Runnable> after = new ArrayList<>();
            check.onSetSlot(0, slot, item, after);
            ack(after);
        }

        void pop() {
            List<Runnable> after = new ArrayList<>();
            check.onStatus(71, 35, after);
            ack(after);
        }

        void click(int source, int button, WindowClickType type, int changedSlot, ItemStack item) {
            check.onClick(new WrapperPlayClientClickWindow(0, Optional.of(1), source, button,
                    Optional.empty(), type, Optional.of(Map.of(changedSlot, item)), ItemStack.EMPTY));
        }

        void refill(int source) {
            click(source, 40, WindowClickType.SWAP, 45, totem());
        }

        void empty(int slot) {
            click(slot, 0, WindowClickType.PICKUP, slot, ItemStack.EMPTY);
        }

        int flags() {
            return check.violationCount();
        }
    }

    private static Fixture fixture(boolean tickEnd) {
        Harness h = TestContexts.builder().check(AutoTotemCheck.DESCRIPTOR, AutoTotemCheck::new).build();
        PlayerData player = h.newPlayer();
        if (tickEnd) {
            player.enableTickEndMode();
        }
        Fixture f = new Fixture(h, player, player.checks().get(AutoTotemCheck.class));
        f.check.onJoin(71);
        f.ticks(100);
        f.seed(36, ItemStack.EMPTY);
        f.seed(45, totem());
        return f;
    }

    private static Fixture fixture() {
        return fixture(true);
    }

    @Test
    void inventorySwapConsistencyIncludesRandomSourceSlotsAndClosingInventory() {
        Fixture f = fixture();
        for (int i = 0; i < 8; i++) {
            f.pop();
            f.ticks(4); // deliberately outside AutoArmor's fast-reaction threshold
            f.refill(9 + (i * 7) % 27);
            f.check.onClose(0);
            f.ticks(20);
        }
        assertEquals(1, f.flags());
    }

    @Test
    void totemOffhandHeldSlotAndSwapKeyConsistency() {
        Fixture f = fixture();
        for (int i = 0; i < 8; i++) {
            f.seed(38, totem());
            f.pop();
            f.check.onHeldSlot(2);
            f.ticks(5);
            f.check.onSwapKey();
            f.check.onHeldSlot(0); // switch-back option
            f.ticks(20);
        }
        assertEquals(1, f.flags());
    }

    @Test
    void hashedStacksAreDetectedWithoutRawItemStacks() {
        Fixture f = fixture();
        for (int i = 0; i < 8; i++) {
            f.pop();
            f.ticks(4);
            HashedStack stack = new HashedStack(ItemTypes.TOTEM_OF_UNDYING, 1,
                    new HashedComponentPatchMap(Map.of(), Set.of()));
            f.check.onClick(new WrapperPlayClientClickWindow(0, 1, 10 + i, 40, WindowClickType.SWAP,
                    Map.of(45, Optional.of(stack)), Optional.empty()));
            f.ticks(20);
        }
        assertEquals(1, f.flags());
    }

    @Test
    void hoverAndAutoInventoryDualHandPairsInEitherOrder() {
        for (boolean offhandFirst : List.of(true, false)) {
            Fixture f = fixture();
            f.empty(45);
            f.seed(36, ItemStack.EMPTY);
            for (int i = 0; i < 4; i++) {
                if (offhandFirst) {
                    f.refill(9);
                    f.ticks(1);
                    f.click(10, 0, WindowClickType.SWAP, 36, totem());
                } else {
                    f.click(9, 0, WindowClickType.SWAP, 36, totem());
                    f.ticks(1);
                    f.refill(10);
                }
                f.empty(36);
                f.empty(45);
                f.ticks(20);
            }
            assertEquals(1, f.flags());
        }
    }

    @Test
    void variableHumanDelaysAndSlowDualHandEquipsDoNotFlag() {
        Fixture f = fixture();
        for (int delay : new int[]{4, 8, 6, 12, 5, 10, 7, 16, 8, 6, 11, 5}) {
            f.pop();
            f.ticks(delay);
            f.refill(9);
            f.empty(36);
            f.ticks(5);
            f.click(10, 0, WindowClickType.SWAP, 36, totem());
            f.ticks(20);
        }
        assertEquals(0, f.flags());
    }

    @Test
    void repeatedAlreadyEquippedClicksAndNonTotemOffhandItemsDoNotCount() {
        Fixture f = fixture();
        for (int i = 0; i < 12; i++) {
            f.check.onPopAcked();
            f.refill(9); // already a totem, no transition
        }
        f.empty(45);
        ItemStack shield = ItemStack.builder().type(ItemTypes.SHIELD).amount(1).build();
        for (int i = 0; i < 12; i++) {
            f.check.onPopAcked();
            f.click(9, 40, WindowClickType.SWAP, 45, shield);
            f.empty(45);
        }
        assertEquals(0, f.flags());
        assertEquals(0, f.check.buffer());
    }

    @Test
    void otherEntityStatusAndWrongStatusNeverArmSamples() {
        Fixture f = fixture();
        for (int i = 0; i < 12; i++) {
            List<Runnable> after = new ArrayList<>();
            f.check.onStatus(72, 35, after);
            f.check.onStatus(71, 2, after);
            assertTrue(after.isEmpty());
            f.empty(45);
            f.refill(9);
        }
        assertEquals(0, f.flags());
    }

    @Test
    void clientRefillBeforePopAcknowledgementIsNotJudged() {
        Fixture f = fixture();
        for (int i = 0; i < 12; i++) {
            List<Runnable> after = new ArrayList<>();
            f.check.onStatus(71, 35, after);
            after.forEach(Runnable::run);
            f.refill(9);
            f.player.acks().onPong(f.harness.lastTransport().lastMarkerId());
            f.refill(10); // already equipped: must not fabricate a second sample
            f.ticks(20);
        }
        assertEquals(0, f.flags());
    }

    @Test
    void serverGrantedTotemsDoNotCountAsClientRefills() {
        Fixture f = fixture();
        for (int i = 0; i < 12; i++) {
            f.pop();
            f.ticks(4);
            f.seed(45, totem());
            f.refill(9);
        }
        assertEquals(0, f.flags());
    }

    @Test
    void staleServerAcknowledgementDoesNotOverwriteNewerClientEvidence() {
        Fixture f = fixture();
        f.seed(36, ItemStack.EMPTY);
        for (int i = 0; i < 4; i++) {
            f.empty(36);
            f.empty(45);
            List<Runnable> after = new ArrayList<>();
            f.check.onSetSlot(0, 45, ItemStack.EMPTY, after);
            f.refill(9); // uncertain previous item, no equip signal
            f.ack(after); // must NOT restore stale empty state
            f.click(10, 0, WindowClickType.SWAP, 36, totem());
            f.refill(11); // still a totem; not a rapid pair
            f.ticks(20);
        }
        assertEquals(0, f.flags());
    }

    @Test
    void resetInvalidatesPendingPopCallbacks() {
        Fixture f = fixture();
        for (int i = 0; i < 12; i++) {
            f.seed(45, totem());
            List<Runnable> after = new ArrayList<>();
            f.check.onStatus(71, 35, after);
            f.check.onReset();
            f.ack(after);
            f.seed(45, ItemStack.EMPTY);
            f.ticks(4);
            f.refill(9);
        }
        assertEquals(0, f.flags());
    }

    @Test
    void unknownInventoryMissingSlotReportsAndContainersDoNotFlag() {
        Fixture f = fixture();
        f.check.onReset();
        for (int i = 0; i < 12; i++) {
            f.check.onPopAcked();
            f.check.onClick(new WrapperPlayClientClickWindow(0, Optional.of(1), 9, 40,
                    Optional.empty(), WindowClickType.SWAP, Optional.empty(), ItemStack.EMPTY));
            f.refill(9);
            f.check.onContainer(true);
            f.check.onSwapKey();
            f.check.onClose(1);
        }
        assertEquals(0, f.flags());
    }

    @Test
    void serverSnapshotSeedsSwapKeyWithoutGeneratingEquipSignals() {
        Fixture f = fixture();
        List<ItemStack> items = new ArrayList<>(Collections.nCopies(46, ItemStack.EMPTY));
        items.set(36, totem());
        List<Runnable> after = new ArrayList<>();
        f.check.onContents(new WrapperPlayServerWindowItems(0, 1, items, ItemStack.EMPTY), after);
        f.ack(after);
        assertEquals(0, f.flags());
        f.check.onHeldSlot(0);
        f.check.onPopAcked();
        f.ticks(4);
        f.check.onSwapKey();
        assertEquals(0, f.flags()); // one sample, not enough
    }

    @Test
    void creativeIsExemptFromTimingHeuristics() {
        Fixture f = fixture();
        f.player.abilities().applyGameMode(GameMode.CREATIVE);
        for (int i = 0; i < 12; i++) {
            f.pop();
            f.ticks(4);
            f.refill(9);
        }
        assertEquals(0, f.flags());
    }

    @Test
    void legacyInventoryConsistencyDoesNotNeedMovementOrTickEndPackets() {
        Fixture f = fixture(false);
        for (int i = 0; i < 8; i++) {
            f.pop();
            f.harness.time.advanceNanos(200_000_000L);
            f.refill(9 + i);
            f.check.onClose(0);
            f.harness.time.advanceNanos(500_000_000L);
        }
        assertEquals(1, f.flags());
    }

    @Test
    void legacyTotemOffhandConsistency() {
        Fixture f = fixture(false);
        for (int i = 0; i < 8; i++) {
            f.seed(37, totem());
            f.check.onHeldSlot(0);
            f.pop();
            f.check.onHeldSlot(1);
            f.harness.time.advanceNanos(100_000_000L);
            f.check.onSwapKey();
        }
        assertEquals(1, f.flags());
    }

    @Test
    void staleServerSelectionDoesNotEnableASwapDuringNewerPendingSelection() {
        Fixture f = fixture();
        for (int i = 0; i < 8; i++) {
            f.seed(36, totem());
            f.seed(37, ItemStack.EMPTY);
            f.seed(45, ItemStack.EMPTY);
            List<Runnable> first = new ArrayList<>();
            List<Runnable> second = new ArrayList<>();
            f.check.onServerHeldSlot(0, first);
            f.check.onServerHeldSlot(1, second);
            f.ack(first);
            f.check.onPopAcked();
            f.ticks(4);
            f.check.onSwapKey(); // selection is still unknown, not the stale totem slot 0
            f.ack(second);
        }
        assertEquals(0, f.flags());
    }

    @Test
    void mainHandPopDoesNotFabricateAnOffhandRefill() {
        Fixture f = fixture();
        for (int i = 0; i < 8; i++) {
            f.seed(36, totem());
            f.pop(); // main-hand totem is consumed first; offhand remains a totem
            f.ticks(4);
            f.refill(9); // reporting that same offhand item is not a refill
        }
        assertEquals(0, f.flags());
    }

    @Test
    void legacyDualHandPairsAreDetectedAcrossRealTimeIntervals() {
        Fixture f = fixture(false);
        f.seed(36, ItemStack.EMPTY);
        for (int i = 0; i < 4; i++) {
            f.empty(36);
            f.empty(45);
            f.refill(9);
            f.harness.time.advanceNanos(50_000_000L);
            f.click(10, 0, WindowClickType.SWAP, 36, totem());
            f.harness.time.advanceNanos(200_000_000L);
        }
        assertEquals(1, f.flags());
    }

    @Test
    void legacyBunchedPacketsAndHighRttAreNotTimingEvidence() {
        for (boolean highPing : List.of(true, false)) {
            Fixture f = fixture(false);
            for (int i = 0; i < 12; i++) {
                f.empty(45);
                if (highPing) {
                    long marker = f.player.acks().sendMarker();
                    f.harness.time.advanceNanos(500_000_000L);
                    f.player.acks().onPong(dev.vaplus.anticheat.latency.MarkerTracker.toWireId(marker));
                    f.check.onPopAcked();
                    f.harness.time.advanceNanos(200_000_000L);
                } else {
                    f.pop();
                    // Zero arrival-time distance, even though inferred movement ticks advance.
                    for (int t = 0; t < 4; t++) {
                        f.player.onClientTick();
                    }
                }
                f.refill(9);
            }
            assertEquals(0, f.flags());
        }
    }

    @Test
    void wallClockLagDoesNotChangeClientTickDelayStatistics() {
        Fixture f = fixture();
        for (int i = 0; i < 8; i++) {
            f.pop();
            f.ticks(4);
            f.harness.time.advanceNanos((i + 1L) * 100_000_000L);
            f.refill(9);
            f.ticks(20);
        }
        assertEquals(1, f.flags());
    }
}
