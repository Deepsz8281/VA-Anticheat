package dev.vaplus.anticheat.check.impl.combat;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import dev.vaplus.anticheat.world.BlockInteraction;
import dev.vaplus.anticheat.world.mirror.BlockPos;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import dev.vaplus.anticheat.world.mirror.WorldMirror;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Mace (fake fall before an attack)")
class MaceCheckTest {

    private static final double GROUND = 64.0;

    private static final class Fixture {
        final Harness harness;
        final PlayerData player;
        final MaceCheck check;
        final SimulationModule simulation;
        double y = GROUND;

        Fixture(boolean withWorld) {
            simulation = new SimulationModule(BlockStateResolver.NONE);
            harness = TestContexts.builder()
                    .module(simulation)
                    .check(MaceCheck.DESCRIPTOR, p -> new MaceCheck(p, simulation.velocityKey(),
                            withWorld ? simulation.worldMirrorKey() : null))
                    .build();
            player = harness.newPlayer();
            check = player.checks().get(MaceCheck.class);
            send(GROUND, 1, true, false);
            tick();
        }

        void send(double toY, int elapsed, boolean first, boolean teleport) {
            player.movement().applyPosition(0.5, toY, 0.5);
            player.movementUpdate().assign(0.5, y, 0.5, 0.5, toY, 0.5, false, false, false,
                    true, false, first, elapsed, 0L);
            if (teleport) {
                player.movementUpdate().markTeleport();
            }
            y = toY;
            player.checks().dispatchPosition(player.movementUpdate());
        }

        void move(double toY) {
            send(toY, 1, false, false);
        }

        void attack() {
            EntityAttack packet = new EntityAttack();
            packet.assign(7, EntityAttack.Kind.ATTACK, false, 0, 0, 0, 0L);
            player.checks().dispatchAttack(packet);
        }

        void useItem() {
            player.interaction().assignUse(com.github.retrooper.packetevents.protocol.player.InteractionHand.MAIN_HAND, 0, 0L);
            player.checks().dispatchInteraction(player.interaction());
        }

        void tick() {
            player.onClientTick();
        }

        void idle(int ticks) {
            for (int i = 0; i < ticks; i++) {
                tick();
            }
        }

        /** Up-attack-down in one tick, the signature of a mace kill. */
        void fakeKill(double height) {
            move(GROUND + height);
            attack();
            move(GROUND);
            tick();
            idle(8);
        }

        int flags() {
            return check.violationCount();
        }
    }

    @Test
    @DisplayName("normal hits on the ground never flag")
    void normalHits() {
        Fixture f = new Fixture(false);
        for (int i = 0; i < 30; i++) {
            f.attack();
            f.move(GROUND);
            f.tick();
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("a real fall from a tower ending in a smash does not flag")
    void realFall() {
        Fixture f = new Fixture(false);
        double top = GROUND + 120.0;
        f.move(top);
        f.tick();
        double vy = 0.0;
        double y = top;
        while (y > GROUND + 0.5) {
            vy = (vy - 0.08) * 0.98;
            y = Math.max(GROUND, y + vy);
            f.move(y);
            f.tick();
        }
        f.attack();
        f.move(GROUND);
        f.tick();
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("a jump followed by a hit at the apex and on the way down does not flag")
    void jumpAndHit() {
        Fixture f = new Fixture(false);
        double vy = 0.42;
        double y = GROUND;
        for (int i = 0; i < 12; i++) {
            y += vy;
            vy = (vy - 0.08) * 0.98;
            f.attack();
            f.move(y);
            f.tick();
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("a wind charge launch (velocity packet) and a smash on the way down does not flag")
    void windChargeWithVelocity() {
        Fixture f = new Fixture(false);
        f.player.component(f.simulation.velocityKey()).register(VelocityState.Kind.ADD, 0, 1.5, 0, 0);
        f.useItem();
        double vy = 1.5;
        double y = GROUND;
        for (int i = 0; i < 20; i++) {
            y += vy;
            vy = (vy - 0.08) * 0.98;
            f.attack();
            f.move(y);
            f.tick();
        }
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("a wind charge use alone (no velocity seen) still allows a large launch")
    void windChargeWithoutVelocity() {
        Fixture f = new Fixture(false);
        f.useItem();
        f.idle(2);
        f.move(GROUND + 6.0);
        f.attack();
        f.tick();
        f.move(GROUND + 5.0);
        f.tick();
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("a fake teleport-up, hit, drop-down in one tick flags after two kills")
    void fakeKillFlags() {
        Fixture f = new Fixture(false);
        f.fakeKill(6.0);
        assertEquals(0, f.flags(), "first small kill only fills the buffer");
        f.fakeKill(6.0);
        assertEquals(1, f.flags(), () -> String.valueOf(f.harness.alerts.alerts()));
    }

    @Test
    @DisplayName("a huge fake height flags at once, the position packets come in 10 block steps")
    void hugeFakeFlagsOnce() {
        Fixture f = new Fixture(false);
        f.move(GROUND + 10.0);
        f.move(GROUND + 20.0);
        f.move(GROUND + 30.0);
        f.attack();
        f.move(GROUND);
        f.tick();
        assertEquals(1, f.flags());
    }

    @Test
    @DisplayName("up and down both before the attack still flags")
    void spikeBeforeAttack() {
        Fixture f = new Fixture(false);
        f.move(GROUND + 12.0);
        f.move(GROUND);
        f.attack();
        f.tick();
        assertEquals(1, f.flags());
    }

    @Test
    @DisplayName("an impossible single-packet drop right after an attack flags")
    void impossibleDrop() {
        Fixture f = new Fixture(false);
        f.send(GROUND + 30.0, 1, false, true);
        f.idle(2);
        f.attack();
        f.move(GROUND);
        f.tick();
        assertEquals(1, f.flags());
    }

    @Test
    @DisplayName("a server teleport up is not a fake rise")
    void teleportIsExempt() {
        Fixture f = new Fixture(false);
        f.send(GROUND + 30.0, 1, false, true);
        f.attack();
        f.tick();
        f.send(GROUND + 30.5, 1, false, false);
        f.attack();
        f.tick();
        assertEquals(0, f.flags());
    }

    @Test
    @DisplayName("jump boost, creative mode and spectator mode are not judged")
    void exemptStates() {
        Fixture f = new Fixture(false);
        f.player.effects().apply(TrackedEffect.JUMP_BOOST, 3);
        f.fakeKill(30.0);
        f.fakeKill(30.0);
        assertEquals(0, f.flags());
        f.player.effects().remove(TrackedEffect.JUMP_BOOST);
        f.player.abilities().applyGameMode(GameMode.CREATIVE);
        f.fakeKill(30.0);
        f.player.abilities().applyGameMode(GameMode.SPECTATOR);
        f.fakeKill(30.0);
        assertEquals(0, f.flags());
    }

    /** Smooth rise of 1.0 per tick for 6 ticks, a hit at the top, then a controlled descent. */
    private static void ride(Fixture f) {
        for (int i = 1; i <= 6; i++) {
            f.move(GROUND + i);
            f.tick();
        }
        f.attack();
        f.tick();
        for (int i = 5; i >= 0; i--) {
            f.move(GROUND + i);
            f.tick();
        }
        f.idle(8);
    }

    @Test
    @DisplayName("a rise out of a bubble column is not judged, the same rise in open air is")
    void environmentGate() {
        Fixture f = new Fixture(true);
        WorldMirror world = f.player.component(f.simulation.worldMirrorKey());
        world.setBlock(new BlockPos(0, 63, 0), VanillaBlocks.BUBBLE_COLUMN);
        world.setBlock(new BlockPos(0, 64, 0), VanillaBlocks.BUBBLE_COLUMN);
        world.setBlock(new BlockPos(0, 65, 0), VanillaBlocks.AIR);
        ride(f);
        ride(f);
        assertEquals(0, f.flags(), "bubble column start is skipped");

        Fixture g = new Fixture(true);
        WorldMirror open = g.player.component(g.simulation.worldMirrorKey());
        open.setBlock(new BlockPos(0, 63, 0), VanillaBlocks.STONE);
        open.setBlock(new BlockPos(0, 64, 0), VanillaBlocks.AIR);
        open.setBlock(new BlockPos(0, 65, 0), VanillaBlocks.AIR);
        for (int y = 66; y <= 75; y++) {
            // unknown blocks next to the lowest point would (correctly) skip the rise
            open.setBlock(new BlockPos(0, y, 0), VanillaBlocks.AIR);
        }
        ride(g);
        ride(g);
        assertEquals(1, g.flags());
    }

    @Test
    @DisplayName("a velocity packet opens a window in which fake-looking rises are tolerated")
    void velocityWindow() {
        Fixture f = new Fixture(false);
        f.player.component(f.simulation.velocityKey()).register(VelocityState.Kind.SET, 0, 2.0, 0, 0);
        f.attack();
        f.tick();
        f.fakeKill(6.0);
        f.fakeKill(6.0);
        assertEquals(0, f.flags());
    }
}
