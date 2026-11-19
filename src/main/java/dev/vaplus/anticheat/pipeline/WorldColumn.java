package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import dev.vaplus.anticheat.world.mirror.ColumnStorage;

/**
 * {@link ColumnStorage} over the sections PacketEvents decoded from a chunk packet. The decoded,
 * bit-packed palettes are kept as they are (no copy), so a mirrored chunk costs about what the
 * client's own copy costs, and loading a chunk does no per-block work on the netty thread.
 *
 * <p>Verified with {@code javap} against the installed PacketEvents 2.14.0 jar:
 * <ul>
 *   <li>{@code WrapperPlayServerChunkData.read()} sizes the section array from
 *       {@code User.getTotalWorldHeight()}; section {@code i} covers
 *       {@code [minY + 16 i, minY + 16 i + 16)} with {@code minY = User.getMinWorldHeight()}.</li>
 *   <li>{@code ChunkReader_v1_18.read} creates a fresh {@code Chunk_v1_18} per section (no shared
 *       singleton), so the mirror may own and mutate them.</li>
 *   <li>{@code BaseChunk.getBlockId(x, y, z)} → {@code DataPalette.get(x, y, z)}, index
 *       {@code (y << 4 | z) << 4 | x} with section-local coordinates.</li>
 *   <li>Writes go to {@code Chunk_v1_18.getChunkData().set(x, y, z, id)} (palette resize included).
 *       {@code Chunk_v1_18.set(int, int, int, int)} is NOT used: it maps the id through
 *       {@code WrappedBlockState.getByGlobalId}, which turns unknown ids into air.</li>
 * </ul>
 * Ownership caveat: PacketEvents may share the decoded {@code Column} between wrappers of the same
 * event ({@code PacketWrapper.readEvent} copies the column reference from the event's last used
 * wrapper). The sections are only mutated by later block updates, i.e. after this packet was
 * encoded, so the bytes the client received are unaffected.
 */
final class WorldColumn implements ColumnStorage {

    private final BaseChunk[] sections;
    private final int minY;

    WorldColumn(BaseChunk[] sections, int minY) {
        this.sections = sections;
        this.minY = minY;
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int height() {
        return sections.length << 4;
    }

    @Override
    public int stateId(int localX, int y, int localZ) {
        int offset = y - minY;
        if (offset < 0) {
            return UNKNOWN_STATE;
        }
        int index = offset >> 4;
        if (index >= sections.length) {
            return UNKNOWN_STATE;
        }
        BaseChunk section = sections[index];
        if (section == null) {
            return UNKNOWN_STATE;
        }
        return section.getBlockId(localX, offset & 15, localZ);
    }

    @Override
    public boolean setStateId(int localX, int y, int localZ, int stateId) {
        int offset = y - minY;
        if (offset < 0 || stateId < 0 || (offset >> 4) >= sections.length) {
            return false;
        }
        if (!(sections[offset >> 4] instanceof Chunk_v1_18 section)) {
            // Older section formats would route through the id->air fallback; refuse instead.
            return false;
        }
        try {
            section.getChunkData().set(localX, offset & 15, localZ, stateId);
            return section.getBlockId(localX, offset & 15, localZ) == stateId;
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
