package dev.vaplus.anticheat.check.impl.world;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FastUseCheck (item uses faster than vanilla)")
class FastUseCheckTest {

    private static final class Fixture {
        final PlayerData player;
        final FastUseCheck check;

        Fixture() {
            SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
            TestContexts.Harness harness = TestContexts.builder().module(simulation)
                    .module(new VisibilityChecksModule(simulation)).build();
            player = harness.newPlayer();
            player.enableTickEndMode();
            check = player.checks().get(FastUseCheck.class);
        }

        void use(InteractionHand hand) {
            player.interaction().assignUse(hand, 0, 0L);
            player.checks().dispatchInteraction(player.interaction());
        }

        void tick() {
            player.onClientTick();
        }
    }

    @Test
    @DisplayName("legit: holding the use key (every 4 ticks) with both-hand packets")
    void holding() {
        Fixture f = new Fixture();
        for (int i = 0; i < 400; i++) {
            if (i % 4 == 0) {
                f.use(InteractionHand.MAIN_HAND);
                f.use(InteractionHand.OFF_HAND);
            }
            f.tick();
        }
        assertEquals(0, f.check.violationCount());
        assertEquals(0.0, f.check.buffer());
    }

    @Test
    @DisplayName("legit: butterfly clicking around 20 CPS with natural gaps")
    void fastHuman() {
        Fixture f = new Fixture();
        Random random = new Random(42);
        for (int i = 0; i < 600; i++) {
            // ~20 CPS on average: 0, 1 or 2 clicks per tick, never a perfect pattern
            int clicks = random.nextInt(100) < 25 ? 0 : random.nextInt(100) < 85 ? 1 : 2;
            for (int c = 0; c < clicks; c++) {
                f.use(InteractionHand.MAIN_HAND);
            }
            f.tick();
        }
        assertEquals(0, f.check.violationCount());
    }

    @Test
    @DisplayName("cheat: no use delay (a use every tick) flags within seconds")
    void everyTick() {
        Fixture f = new Fixture();
        for (int i = 0; i < 100; i++) {
            f.use(InteractionHand.MAIN_HAND);
            f.tick();
        }
        assertTrue(f.check.violationCount() >= 1);
    }

    @Test
    @DisplayName("cheat: bursts of several uses per tick flag")
    void bursts() {
        Fixture f = new Fixture();
        for (int i = 0; i < 20; i++) {
            for (int c = 0; c < 4; c++) {
                f.use(InteractionHand.MAIN_HAND);
            }
            f.tick();
            f.tick();
            f.tick();
        }
        assertTrue(f.check.violationCount() >= 1);
    }

    @Test
    @DisplayName("not judged: creative, legacy clients")
    void notJudged() {
        Fixture creative = new Fixture();
        creative.player.abilities().applyGameMode(GameMode.CREATIVE);
        for (int i = 0; i < 100; i++) {
            creative.use(InteractionHand.MAIN_HAND);
            creative.tick();
        }
        assertEquals(0, creative.check.violationCount());

        SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
        TestContexts.Harness harness = TestContexts.builder().module(simulation)
                .module(new VisibilityChecksModule(simulation)).build();
        PlayerData legacy = harness.newPlayer();
        FastUseCheck check = legacy.checks().get(FastUseCheck.class);
        for (int i = 0; i < 100; i++) {
            legacy.interaction().assignUse(InteractionHand.MAIN_HAND, 0, 0L);
            legacy.checks().dispatchInteraction(legacy.interaction());
        }
        assertEquals(0, check.violationCount());
    }
}
