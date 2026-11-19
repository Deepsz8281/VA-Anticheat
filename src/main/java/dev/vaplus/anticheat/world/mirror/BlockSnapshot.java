package dev.vaplus.anticheat.world.mirror;

import java.util.Objects;

/**
 * Immutable query result. {@code known} means a feeder supplied this position, not that all of its
 * physics are modelled. Missing data is deliberately distinguishable from acknowledged air.
 */
public record BlockSnapshot(BlockKey key, BlockProperties properties, boolean known) {

    public static final BlockSnapshot UNKNOWN =
            new BlockSnapshot(BlockKey.UNKNOWN, VanillaBlocks.MISSING_PROPERTIES, false);

    public BlockSnapshot {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(properties, "properties");
    }

    public boolean uncertain() {
        return !known || properties.uncertain();
    }
}
