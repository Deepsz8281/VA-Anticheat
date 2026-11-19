package dev.vaplus.anticheat.check;

import dev.vaplus.anticheat.player.PlayerData;

/** Creates the per-player instance of one check type, typically a constructor reference. */
@FunctionalInterface
public interface CheckFactory {

    Check create(PlayerData player);
}
