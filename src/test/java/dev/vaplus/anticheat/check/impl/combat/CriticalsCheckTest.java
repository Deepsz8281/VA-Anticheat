package dev.vaplus.anticheat.check.impl.combat;

import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.module.ModuleContext;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.simulation.SimulationModule;
import dev.vaplus.anticheat.testutil.replay.ReplayScenario;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CriticalsCheck (faked take-off before an attack)")
class CriticalsCheckTest {

    private static final class CriticalsOnly implements AnticheatModule {
        private final SimulationModule simulation;

        CriticalsOnly(SimulationModule simulation) {
            this.simulation = simulation;
        }

        @Override
        public String name() {
            return "criticals-only";
        }

        @Override
        public void register(ModuleContext context) {
            context.registerCheck(CriticalsCheck.DESCRIPTOR,
                    player -> new CriticalsCheck(player, simulation.worldMirrorKey(), simulation.velocityKey()));
        }
    }

    private static ReplayScenario onStone() {
        ReplayScenario s = ReplayScenario.withModule(CriticalsOnly::new).platform(VanillaBlocks.STONE, 63, 4);
        s.spawn(0.5, 64.0, 0.5, 0f);
        s.move(0, 0, 0, true);
        s.move(0, 0, 0, true);
        return s;
    }

    private static CriticalsCheck check(ReplayScenario s) {
        return s.player.checks().get(CriticalsCheck.class);
    }

    private static void attack(ReplayScenario s) {
        EntityAttack attack = s.player.entityAttack();
        attack.assign(42, EntityAttack.Kind.ATTACK, false, 0, 0, 0, s.harness.time.nanoTime());
        s.player.checks().dispatchAttack(attack);
    }

    /** Packet-crit pattern: tiny hop up (airborne claim), back down, attack, land. */
    private static void packetCrit(ReplayScenario s) {
        s.move(0, 0.0625, 0, false);
        s.move(0, -0.0625 + 1.0E-5, 0, false);
        attack(s);
        s.move(0, -1.0E-5, 0, true);
        s.move(0, 0, 0, true);
    }

    @Test
    @DisplayName("descriptor: id criticals, combat, experimental")
    void descriptor() {
        assertEquals("criticals", CriticalsCheck.DESCRIPTOR.id());
        assertTrue(CriticalsCheck.DESCRIPTOR.experimental());
        assertTrue(java.util.List.of(CombatChecksModule.descriptors()).contains(CriticalsCheck.DESCRIPTOR));
    }

    @Test
    @DisplayName("legit: real jump crits and ground hits never raise the buffer")
    void realJumps() {
        ReplayScenario s = onStone();
        for (int round = 0; round < 4; round++) {
            double vy = 0.42;
            double height = 0.0;
            int tick = 0;
            while (height + vy > 0.0) {
                s.move(0, vy, 0, false);
                height += vy;
                if (++tick == 8) {
                    attack(s);
                }
                vy = (vy - 0.08) * 0.98;
            }
            s.move(0, -height, 0, true);
            s.move(0, 0, 0, true);
            attack(s);
        }
        assertEquals(0, check(s).fakeTakeOffs());
        assertEquals(0, check(s).violationCount());
        assertEquals(0.0, check(s).buffer());
    }

    @Test
    @DisplayName("cheat: repeated packet crits flag")
    void packetCrits() {
        ReplayScenario s = onStone();
        for (int i = 0; i < 4; i++) {
            packetCrit(s);
        }
        assertTrue(check(s).fakeTakeOffs() >= 3, "fake take-offs " + check(s).fakeTakeOffs());
        assertTrue(check(s).violationCount() >= 1);
    }

    @Test
    @DisplayName("a fake hop without an attack does not raise the buffer")
    void hopWithoutAttack() {
        ReplayScenario s = onStone();
        for (int i = 0; i < 4; i++) {
            s.move(0, 0.0625, 0, false);
            s.move(0, -0.0625, 0, true);
        }
        assertEquals(0.0, check(s).buffer());
    }

    @Test
    @DisplayName("not judged: pending server velocity, low ceiling")
    void notJudged() {
        ReplayScenario s = onStone();
        VelocityState velocity = s.velocity();
        velocity.register(VelocityState.Kind.SET, 0.0, 0.0625, 0.0, s.player.movement().worldEpoch());
        packetCrit(s);
        assertEquals(0, check(s).fakeTakeOffs());

        ReplayScenario low = onStone();
        low.fill(VanillaBlocks.STONE, -4, 4, 66, 66, -4, 4);
        packetCrit(low);
        assertEquals(0, check(low).fakeTakeOffs(), "head room is not free for a full jump: a bonk is legit");
        assertFalse(check(low).fakeEpisodeOpen());
    }
}
