package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Elytra glide starts (Grim ElytraA-I)")
class ElytraStartTest {

    private final SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
    private final TestContexts.Harness harness = TestContexts.builder()
            .module(simulation)
            .module(new MovementChecksModule(simulation))
            .build();
    private final PlayerData player = harness.newPlayer();
    private final ElytraCheck check = player.checks().get(ElytraCheck.class);
    private final WorldMirror world = player.component(simulation.worldMirrorKey());

    ElytraStartTest() {
        player.enableTickEndMode();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = 60; y <= 70; y++) {
                    world.setBlock(new BlockPos(x, y, z), VanillaBlocks.AIR);
                }
            }
        }
        player.movement().applyPosition(0.5, 65.4, 0.5);
        player.movement().applyFlags(false, false);
        input(false);
    }

    private void input(boolean jump) {
        player.pose().applyInput(false, false, false, false, jump, false, false, player.clientTicks());
    }

    private void ticks(int count) {
        for (int i = 0; i < count; i++) {
            player.onClientTick();
        }
    }

    private boolean flagged(String prefix) {
        return harness.alerts.alerts().stream().anyMatch(a -> a.check() == check && a.details().startsWith(prefix));
    }

    /** Vanilla order: glide start, then the input packet with jump held, then the tick end. */
    private void legitStart() {
        input(false);
        check.onGlideStart();
        input(true);
        ticks(1);
        input(false);
        ticks(2);
    }

    private void armor(boolean glider) {
        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            check.onArmor(i, glider && i == 1, tasks);
        }
        tasks.forEach(Runnable::run);
        for (int id : harness.lastTransport().markerIds()) {
            player.acks().onPong(id);
        }
    }

    @Test
    @DisplayName("a vanilla glide start in the air does not flag")
    void legit() {
        for (int i = 0; i < 4; i++) {
            legitStart();
        }
        armor(true);
        legitStart();
        assertEquals(0, check.violationCount(), harness.alerts.alerts()::toString);
    }

    @Test
    @DisplayName("start-ground: starting on the ground flags")
    void ground() {
        player.movement().applyFlags(true, false);
        legitStart();
        assertTrue(flagged("start-ground"));
    }

    @Test
    @DisplayName("start-jump: jump already held, or never pressed, flags")
    void jump() {
        input(true);
        check.onGlideStart();
        assertTrue(flagged("start-jump started gliding without releasing"));
        ticks(3);
        input(false);
        check.onGlideStart();
        ticks(1);
        assertTrue(flagged("start-jump started gliding without pressing"));
    }

    @Test
    @DisplayName("start-frequency: two starts in consecutive ticks flag")
    void frequency() {
        input(false);
        check.onGlideStart();
        input(true);
        ticks(1);
        input(false);
        check.onGlideStart();
        assertTrue(flagged("start-frequency"));
    }

    @Test
    @DisplayName("start-no-elytra: only while the server set and the client acknowledged no glider")
    void noElytra() {
        armor(false);
        check.armorUnknown(); // the client clicked in its inventory
        legitStart();
        legitStart();
        assertEquals(0, check.violationCount());
        armor(false);
        legitStart();
        legitStart();
        assertTrue(flagged("start-no-elytra"), harness.alerts.alerts()::toString);
    }

    @Test
    @DisplayName("start-levitation and start-water")
    void levitationAndWater() {
        player.effects().apply(TrackedEffect.LEVITATION, 0);
        legitStart();
        legitStart();
        assertTrue(flagged("start-levitation"));
        player.effects().remove(TrackedEffect.LEVITATION);
        world.setBlock(new BlockPos(0, 65, 0), VanillaBlocks.WATER_BLOCK);
        player.movement().applyPosition(0.5, 65.05, 0.5);
        legitStart();
        legitStart();
        assertTrue(flagged("start-water"));
    }
}
