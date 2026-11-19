package dev.vaplus.anticheat.simulation;

import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.player.PlayerData;

/** Engine used until a real simulator is installed: reports {@link SimulationResult.Status#NOT_RUN}. */
public final class InactiveSimulator implements MovementSimulator {

    public static final InactiveSimulator INSTANCE = new InactiveSimulator();

    private InactiveSimulator() {
    }

    @Override
    public void simulate(PlayerData player, MovementUpdate update, SimulationResult out) {
        out.reset();
    }
}
