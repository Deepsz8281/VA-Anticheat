package dev.vaplus.anticheat.check;

import dev.vaplus.anticheat.world.BlockInteraction;

/**
 * Capability interface for checks that evaluate block placement, item use and digging.
 * Implementations must extend {@link Check}.
 */
public interface BlockCheck {

    void onBlockInteraction(BlockInteraction interaction);
}
