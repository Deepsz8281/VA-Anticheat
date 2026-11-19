package dev.vaplus.anticheat.obfuscation;

/**
 * Read access to the block states around one chunk packet: the chunk's own decoded sections plus
 * the player's {@code WorldMirror} for everything outside it. Kept tiny so the obfuscation logic can
 * be unit-tested without PacketEvents, a server or a mirror.
 */
public interface BlockView {

    /** No data: the position is outside the world or not mirrored. */
    int UNKNOWN_STATE = -1;

    /** Protocol state id at the world position, or {@link #UNKNOWN_STATE}. */
    int stateId(int x, int y, int z);

    /** Whether this state hides what is behind it (see {@code BlockSight.obscures}). */
    boolean opaque(int stateId);
}
