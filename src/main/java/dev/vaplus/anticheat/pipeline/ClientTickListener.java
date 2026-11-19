package dev.vaplus.anticheat.pipeline;

import dev.vaplus.anticheat.player.PlayerData;

/**
 * Per-client-tick callback for trackers and engines (runs before {@link
 * dev.vaplus.anticheat.check.ClientTickCheck}s, also for bypassing players). Owner thread.
 */
@FunctionalInterface
public interface ClientTickListener {

    void onClientTick(PlayerData player);
}
