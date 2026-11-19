package dev.vaplus.anticheat.world.mirror;

/**
 * Maps the opaque state ids of a {@link ColumnStorage} to mirror snapshots. Implementations must
 * never return {@code null} and must return {@link BlockSnapshot#UNKNOWN} (not air) for ids they
 * cannot identify, so the movement engine skips instead of simulating against invented blocks.
 * Shared between players, so implementations must be safe for concurrent use.
 */
@FunctionalInterface
public interface BlockStateResolver {

    /** Resolver that knows no state at all (every dense position reads as UNKNOWN). */
    BlockStateResolver NONE = stateId -> BlockSnapshot.UNKNOWN;

    BlockSnapshot resolve(int stateId);
}
