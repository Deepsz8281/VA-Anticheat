package dev.vaplus.anticheat.world.mirror;

/**
 * Dense block-state storage of one chunk column, supplied by a feeder (for example the decoded
 * sections of a chunk packet). Values are opaque protocol state ids that a
 * {@link BlockStateResolver} turns into {@link BlockSnapshot}s; this package never interprets them.
 *
 * <p>Coordinates: {@code localX}/{@code localZ} are 0..15 within the column, {@code y} is the
 * absolute block height. Implementations are owner-thread confined like the mirror holding them.
 */
public interface ColumnStorage {

    /** "No data": outside the column's height range or a missing section. Never a valid state id. */
    int UNKNOWN_STATE = -1;

    /** Lowest block height covered (inclusive). */
    int minY();

    /** Number of block layers covered; the column spans {@code [minY, minY + height)}. */
    int height();

    /** Whether {@code y} lies inside {@code [minY, minY + height)}. */
    default boolean containsY(int y) {
        return y >= minY() && y - minY() < height();
    }

    /** State id at the position, or {@link #UNKNOWN_STATE} outside the column or for missing data. */
    int stateId(int localX, int y, int localZ);

    /**
     * Stores a state id. Callers only pass heights for which {@link #containsY} holds.
     *
     * @return {@code false} when the storage could not represent the id (the caller must then treat
     *         the column as unknown instead of keeping possibly wrong data)
     */
    boolean setStateId(int localX, int y, int localZ, int stateId);
}
