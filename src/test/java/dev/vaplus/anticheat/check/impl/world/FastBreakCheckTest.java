package dev.vaplus.anticheat.check.impl.world;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.world.BlockInteraction;
import dev.vaplus.anticheat.world.mirror.BlockKey;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("FastBreakCheck (break speed and nuker)")
class FastBreakCheckTest {

    private static final BlockKey OBSIDIAN = new BlockKey("minecraft:obsidian");

    private static final class Fixture {
        final TestContexts.Harness harness;
        final PlayerData player;
        final WorldMirror world;
        final FastBreakCheck check;

        Fixture() {
            SimulationModule simulation = new SimulationModule(BlockStateResolver.NONE);
            harness = TestContexts.builder().module(simulation).module(new VisibilityChecksModule(simulation)).build();
            player = harness.newPlayer();
            player.enableTickEndMode();
            world = player.component(simulation.worldMirrorKey());
            for (int x = 0; x < 8; x++) {
                world.setBlock(new BlockPos(x, 64, 0), VanillaBlocks.STONE);
                world.setBlock(new BlockPos(x, 65, 0), OBSIDIAN);
                world.setBlock(new BlockPos(x, 66, 0), VanillaBlocks.BEDROCK);
            }
            check = player.checks().get(FastBreakCheck.class);
        }

        void dig(BlockInteraction.Kind kind, int x, int y, int z) {
            player.interaction().assignDig(kind, x, y, z, BlockFace.UP, 0, harness.time.nanoTime());
            player.checks().dispatchInteraction(player.interaction());
        }

        void ticks(int count) {
            for (int i = 0; i < count; i++) {
                player.onClientTick();
            }
        }

        /** Breaks the block, finishing {@code ticks} client ticks after the start. */
        void breakBlock(int x, int y, int z, int ticks) {
            dig(BlockInteraction.Kind.DIG_START, x, y, z);
            ticks(ticks);
            dig(BlockInteraction.Kind.DIG_FINISH, x, y, z);
            ticks(1);
        }
    }

    @Test
    @DisplayName("descriptor: id fastbreak, WORLD")
    void descriptor() {
        assertEquals("fastbreak", FastBreakCheck.DESCRIPTOR.id());
        assertTrue(new Fixture().check != null);
    }

    @Test
    @DisplayName("required ticks: obsidian with the best tool needs 42 ticks, haste II 30, stone may be instant")
    void requiredTicks() {
        Fixture f = new Fixture();
        // 35 / 50 / 30 = 0.02333 per tick -> 43 damage ticks -> finish 42 ticks after the start
        assertEquals(42, f.check.requiredTicks(50.0));
        f.player.effects().apply(TrackedEffect.HASTE, 1);
        assertEquals(30, f.check.requiredTicks(50.0));
        assertEquals(-1, f.check.requiredTicks(1.0), "1.0 hardness breaks instantly with the best tool");
    }

    @Test
    @DisplayName("legit: obsidian at the best-tool speed, slower breaks, stone, normal one-block-per-tick mining")
    void legit() {
        Fixture f = new Fixture();
        f.breakBlock(0, 65, 0, 42);
        f.breakBlock(1, 65, 0, 41); // within tolerance
        f.breakBlock(2, 65, 0, 200);
        for (int x = 0; x < 8; x++) {
            f.breakBlock(x, 64, 0, 1);
        }
        // insta-mine: one start per tick, no finish
        for (int x = 0; x < 8; x++) {
            f.dig(BlockInteraction.Kind.DIG_START, x, 64, 0);
            f.ticks(1);
        }
        // abort and restart the same block in one tick
        f.dig(BlockInteraction.Kind.DIG_START, 3, 65, 0);
        f.dig(BlockInteraction.Kind.DIG_CANCEL, 3, 65, 0);
        f.dig(BlockInteraction.Kind.DIG_START, 3, 65, 0);
        assertEquals(0, f.check.violationCount());
        assertEquals(0.0, f.check.buffer());
    }

    @Test
    @DisplayName("cheat: obsidian in half the time flags")
    void fastObsidian() {
        Fixture f = new Fixture();
        for (int x = 0; x < 4; x++) {
            f.breakBlock(x, 65, 0, 20);
        }
        assertTrue(f.check.violationCount() >= 1);
    }

    @Test
    @DisplayName("cheat: finishing bedrock flags; finishing in the start tick flags")
    void impossibleFinishes() {
        Fixture f = new Fixture();
        f.breakBlock(0, 66, 0, 100);
        f.breakBlock(1, 66, 0, 100);
        f.breakBlock(2, 66, 0, 100);
        assertTrue(f.check.violationCount() >= 1);

        Fixture same = new Fixture();
        for (int x = 0; x < 3; x++) {
            same.breakBlock(x, 64, 0, 0);
        }
        assertTrue(same.check.violationCount() >= 1);
    }

    @Test
    @DisplayName("cheat: a nuker starting several blocks per tick flags")
    void nuker() {
        Fixture f = new Fixture();
        for (int x = 0; x < 6; x++) {
            f.dig(BlockInteraction.Kind.DIG_START, x, 64, 0);
        }
        assertTrue(f.check.violationCount() >= 1);
    }

    @Test
    @DisplayName("not judged: legacy clients, creative, unknown blocks, pending haste")
    void notJudged() {
        Fixture f = new Fixture();
        PlayerData legacy = f.harness.newPlayer();
        FastBreakCheck legacyCheck = legacy.checks().get(FastBreakCheck.class);
        for (int x = 0; x < 6; x++) {
            legacy.interaction().assignDig(BlockInteraction.Kind.DIG_START, x, 64, 0, BlockFace.UP, 0, 0L);
            legacy.checks().dispatchInteraction(legacy.interaction());
        }
        assertEquals(0, legacyCheck.violationCount(), "no tick source: not judged");

        Fixture creative = new Fixture();
        creative.player.abilities().applyGameMode(GameMode.CREATIVE);
        for (int x = 0; x < 6; x++) {
            creative.dig(BlockInteraction.Kind.DIG_START, x, 64, 0);
        }
        assertEquals(0, creative.check.violationCount());

        Fixture unknown = new Fixture();
        for (int i = 0; i < 4; i++) {
            unknown.breakBlock(20 + i, 65, 0, 2);
        }
        assertEquals(0, unknown.check.violationCount());

        Fixture pending = new Fixture();
        pending.player.effects().beginPending(TrackedEffect.HASTE);
        assertEquals(-1, pending.check.requiredTicks(50.0));
    }
}
