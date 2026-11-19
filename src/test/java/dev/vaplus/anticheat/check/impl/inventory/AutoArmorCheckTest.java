package dev.vaplus.anticheat.check.impl.inventory;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow.WindowClickType;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AutoArmor (totem reaction, clicks while moving, armor bursts)")
class AutoArmorCheckTest {

    private record Fixture(Harness harness, PlayerData player, AutoArmorCheck check) {
        void ticks(int count) {
            for (int i = 0; i < count; i++) {
                harness.time.advanceNanos(50_000_000L);
                player.onClientTick();
            }
        }

        int flags() {
            return check.violationCount();
        }

        void click(int slot, int button, WindowClickType type, boolean armor) {
            check.onWindowClick(slot, button, type, armor);
        }
    }

    private static Fixture fixture() {
        Harness harness = TestContexts.builder()
                .check(AutoArmorCheck.DESCRIPTOR, AutoArmorCheck::new)
                .build();
        PlayerData player = harness.newPlayer();
        player.enableTickEndMode();
        Fixture fixture = new Fixture(harness, player, player.checks().get(AutoArmorCheck.class));
        fixture.ticks(100); // past join / respawn exemptions
        return fixture;
    }

    @Test
    @DisplayName("totem pop then offhand click 1 tick later flags (twice to pass the buffer)")
    void instantRetotem() {
        Fixture f = fixture();
        for (int i = 0; i < 2; i++) {
            f.check().onTotemPopAcked();
            f.ticks(1);
            f.click(45, 0, WindowClickType.PICKUP, false);
            f.ticks(30);
        }
        assertEquals(1, f.flags());
    }

    @Test
    @DisplayName("swap-with-offhand key in the same tick as the pop acknowledgement flags")
    void instantSwapKey() {
        Fixture f = fixture();
        f.check().onTotemPopAcked();
        f.check().onOffhandAction();
        f.ticks(20);
        f.check().onTotemPopAcked();
        f.check().onOffhandAction();
        assertEquals(1, f.flags());
    }

    @Test
    @DisplayName("hotbar-swap click with button 40 counts as offhand action")
    void hotbarSwapOffhand() {
        Fixture f = fixture();
        for (int i = 0; i < 2; i++) {
            f.check().onTotemPopAcked();
            f.ticks(1);
            f.click(36, 40, WindowClickType.SWAP, false);
            f.ticks(5);
        }
        assertEquals(1, f.flags());
    }

    @Test
    @DisplayName("manual re-totem after 10 ticks and F swap after 5 ticks never flag")
    void humanReaction() {
        Fixture f = fixture();
        for (int i = 0; i < 20; i++) {
            f.check().onTotemPopAcked();
            f.ticks(10);
            f.click(45, 0, WindowClickType.PICKUP, false);
            f.ticks(5);
            f.check().onTotemPopAcked();
            f.ticks(5);
            f.check().onOffhandAction();
            f.ticks(5);
        }
        assertEquals(0, f.flags());
        assertEquals(0.0, f.check().buffer());
    }

    @Test
    @DisplayName("an offhand action without an acknowledged pop is not judged")
    void noPop() {
        Fixture f = fixture();
        for (int i = 0; i < 10; i++) {
            f.check().onOffhandAction();
            f.click(45, 0, WindowClickType.PICKUP, false);
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("only the first offhand action after a pop is judged")
    void firstActionOnly() {
        Fixture f = fixture();
        f.check().onTotemPopAcked();
        f.ticks(8);
        for (int i = 0; i < 11; i++) {
            f.check().onOffhandAction();
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("a very late offhand action (outside the window) is not judged")
    void lateAction() {
        Fixture f = fixture();
        f.check().onTotemPopAcked();
        f.ticks(500);
        f.check().onOffhandAction();
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("descriptor: id and experimental flag")
    void descriptor() {
        assertEquals("autoarmor", AutoArmorCheck.DESCRIPTOR.id());
        assertTrue(AutoArmorCheck.DESCRIPTOR.experimental());
    }

    @Test
    @DisplayName("window clicks while sprinting for several ticks flag")
    void clickWhileSprinting() {
        Fixture f = fixture();
        f.player().pose().sprinting(true);
        f.ticks(5);
        for (int i = 0; i < 3; i++) {
            f.click(20, 0, WindowClickType.PICKUP, false);
            f.ticks(2);
        }
        assertEquals(1, f.flags());
    }

    @Test
    @DisplayName("window clicks with held movement keys (1.21.2+ input) flag")
    void clickWithKeys() {
        Fixture f = fixture();
        f.player().pose().applyInput(true, false, false, false, false, false, false, f.player().clientTicks());
        f.ticks(4);
        for (int i = 0; i < 3; i++) {
            f.click(20, 0, WindowClickType.PICKUP, false);
        }
        assertEquals(1, f.flags());
    }

    @Test
    @DisplayName("clicks right after the inventory opened (release still in flight) do not flag")
    void releaseGrace() {
        Fixture f = fixture();
        f.player().pose().sprinting(true);
        f.ticks(10);
        f.ticks(1);
        f.player().pose().sprinting(false);
        for (int i = 0; i < 10; i++) {
            f.click(20, 0, WindowClickType.PICKUP, false);
            f.ticks(3);
        }
        assertEquals(0, f.flags());
        // keys reported held for only one tick: inside the grace.
        f.player().pose().applyInput(true, false, false, false, false, false, false, 0);
        f.ticks(1);
        f.click(20, 0, WindowClickType.PICKUP, false);
        f.click(20, 0, WindowClickType.PICKUP, false);
        f.click(20, 0, WindowClickType.PICKUP, false);
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("standing still with many clicks (stack sorting, drag spreads) never flags")
    void standingStill() {
        Fixture f = fixture();
        for (int i = 0; i < 100; i++) {
            f.click(9 + i % 30, 0, WindowClickType.QUICK_MOVE, false);
            f.click(9, 0, WindowClickType.QUICK_CRAFT, false);
            f.ticks(1);
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("attack and inventory click in one tick segment flag, in separate ticks they do not")
    void attackAndClick() {
        Fixture f = fixture();
        for (int i = 0; i < 4; i++) {
            f.click(20, 0, WindowClickType.PICKUP, false);
            f.check().onAttack();
            f.ticks(1);
        }
        assertEquals(1, f.flags());

        Fixture g = fixture();
        for (int i = 0; i < 20; i++) {
            g.click(20, 0, WindowClickType.PICKUP, false);
            g.ticks(1);
            g.check().onAttack();
            g.ticks(1);
        }
        assertEquals(0, g.flags());
    }

    @Test
    @DisplayName("three armor shift-clicks in consecutive ticks flag at once")
    void armorBurst() {
        Fixture f = fixture();
        f.click(9, 0, WindowClickType.QUICK_MOVE, true);
        f.ticks(1);
        f.click(10, 0, WindowClickType.QUICK_MOVE, true);
        f.ticks(1);
        f.click(11, 0, WindowClickType.QUICK_MOVE, true);
        assertEquals(1, f.flags());
    }

    @Test
    @DisplayName("slow armor equipping (5 ticks between pieces) never flags")
    void slowArmor() {
        Fixture f = fixture();
        for (int round = 0; round < 10; round++) {
            for (int piece = 0; piece < 4; piece++) {
                f.click(9 + piece, 0, WindowClickType.QUICK_MOVE, true);
                f.ticks(5);
            }
            f.ticks(40);
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("shift-clicks that do not touch armor slots are not armor equips")
    void plainShiftClicks() {
        Fixture f = fixture();
        for (int i = 0; i < 20; i++) {
            f.click(9 + i, 0, WindowClickType.QUICK_MOVE, false);
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("changed slots touching 5-8 are armor, others not")
    void touchesArmor() {
        assertTrue(AutoArmorCheck.touchesArmor(List.of(9, 6)));
        assertFalse(AutoArmorCheck.touchesArmor(List.of(9, 36, 45, 4)));
    }

    @Test
    @DisplayName("creative players are exempt")
    void creativeExempt() {
        Fixture f = fixture();
        f.player().abilities().applyGameMode(GameMode.CREATIVE);
        f.player().pose().sprinting(true);
        f.ticks(10);
        for (int i = 0; i < 10; i++) {
            f.click(20, 0, WindowClickType.PICKUP, false);
            f.click(9 + i, 0, WindowClickType.QUICK_MOVE, true);
        }
        f.check().onTotemPopAcked();
        f.check().onOffhandAction();
        assertEquals(0, f.flags());
    }
}
