package dev.vaplus.anticheat.world;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.BlockFace;

/**
 * A block placement, item use or digging action as seen by {@link dev.vaplus.anticheat.check.BlockCheck}s.
 * Reused per player; checks must not keep a reference beyond the callback.
 */
public final class BlockInteraction {

    public enum Kind {
        /** Right click on a block face (placement or block use). */
        PLACE,
        /** Right click without a target block (eating, bows, wind charges, ...). */
        USE_ITEM,
        DIG_START,
        DIG_CANCEL,
        DIG_FINISH
    }

    private Kind kind = Kind.USE_ITEM;
    private int blockX;
    private int blockY;
    private int blockZ;
    private BlockFace face = BlockFace.OTHER;
    private float cursorX;
    private float cursorY;
    private float cursorZ;
    private InteractionHand hand = InteractionHand.MAIN_HAND;
    private int sequence;
    private boolean insideBlock;
    private long timestampNanos;

    /** Pipeline only. */
    public void assignPlace(int x, int y, int z, BlockFace face, float cursorX, float cursorY, float cursorZ,
                            InteractionHand hand, int sequence, boolean insideBlock, long timestampNanos) {
        this.kind = Kind.PLACE;
        this.blockX = x;
        this.blockY = y;
        this.blockZ = z;
        this.face = face;
        this.cursorX = cursorX;
        this.cursorY = cursorY;
        this.cursorZ = cursorZ;
        this.hand = hand;
        this.sequence = sequence;
        this.insideBlock = insideBlock;
        this.timestampNanos = timestampNanos;
    }

    /** Pipeline only. */
    public void assignUse(InteractionHand hand, int sequence, long timestampNanos) {
        this.kind = Kind.USE_ITEM;
        this.blockX = 0;
        this.blockY = 0;
        this.blockZ = 0;
        this.face = BlockFace.OTHER;
        this.cursorX = 0.0f;
        this.cursorY = 0.0f;
        this.cursorZ = 0.0f;
        this.hand = hand;
        this.sequence = sequence;
        this.insideBlock = false;
        this.timestampNanos = timestampNanos;
    }

    /** Pipeline only. {@code kind} must be one of the DIG_* values. */
    public void assignDig(Kind kind, int x, int y, int z, BlockFace face, int sequence, long timestampNanos) {
        if (kind != Kind.DIG_START && kind != Kind.DIG_CANCEL && kind != Kind.DIG_FINISH) {
            throw new IllegalArgumentException("not a dig kind: " + kind);
        }
        this.kind = kind;
        this.blockX = x;
        this.blockY = y;
        this.blockZ = z;
        this.face = face;
        this.cursorX = 0.0f;
        this.cursorY = 0.0f;
        this.cursorZ = 0.0f;
        this.hand = InteractionHand.MAIN_HAND;
        this.sequence = sequence;
        this.insideBlock = false;
        this.timestampNanos = timestampNanos;
    }

    public Kind kind() {
        return kind;
    }

    /** Whether the interaction targets a block position. */
    public boolean hasBlock() {
        return kind != Kind.USE_ITEM;
    }

    public int blockX() {
        return blockX;
    }

    public int blockY() {
        return blockY;
    }

    public int blockZ() {
        return blockZ;
    }

    public BlockFace face() {
        return face;
    }

    public float cursorX() {
        return cursorX;
    }

    public float cursorY() {
        return cursorY;
    }

    public float cursorZ() {
        return cursorZ;
    }

    public InteractionHand hand() {
        return hand;
    }

    /** Block-change acknowledgement sequence (1.19+ protocol). */
    public int sequence() {
        return sequence;
    }

    public boolean insideBlock() {
        return insideBlock;
    }

    public long timestampNanos() {
        return timestampNanos;
    }
}
