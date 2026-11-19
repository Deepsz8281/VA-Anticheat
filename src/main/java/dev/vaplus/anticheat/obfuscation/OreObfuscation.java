package dev.vaplus.anticheat.obfuscation;

import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.HeightmapType;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.GlobalPalette;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.Palette;

import java.util.Map;

/**
 * Server-side X-Ray prevention (engine mode): rewrites an outgoing chunk column so fully enclosed
 * ores are sent as the configured fake block. A client that draws blocks it should not see therefore
 * receives no ore to draw — the information never leaves the server.
 *
 * <h2>What is replaced</h2>
 * Exactly the blocks {@code XrayCheck} also treats as "invisible": a valuable ore whose six
 * neighbours are all known and opaque. Exposed ores, ores at unmirrored chunk borders and ores the
 * mirror cannot resolve stay untouched. Nothing is replaced when the chunk is not a full chunk, when
 * the section format is not {@code Chunk_v1_18}, when the fake block or an ore id could not be
 * resolved, or when {@code maxReplacements} is exhausted (safety valve against pathological chunks).
 *
 * <h2>Performance</h2>
 * The per-section palette is checked first: a section whose palette contains no valuable ore state is
 * skipped without touching its 4096 blocks, which is the normal case (stone, air, deepslate). Only
 * sections that can contain an ore are scanned, and a section is copied only once it actually has a
 * replacement — untouched sections stay shared with the caller's column.
 *
 * <h2>Ownership</h2>
 * The returned column is a new object with a new section array; the input column, its sections and
 * its heightmaps/biome data are never mutated. The caller's world mirror keeps the original sections,
 * so the true world stays consistent server-side while the client only receives the fake blocks.
 */
public final class OreObfuscation {

    /** Upper bound of palette entries that is worth iterating (global palettes report MAX_VALUE). */
    private static final int MAX_PALETTE_SCAN = 512;

    private static final int[] NEIGHBOUR_X = {1, -1, 0, 0, 0, 0};
    private static final int[] NEIGHBOUR_Y = {0, 0, 1, -1, 0, 0};
    private static final int[] NEIGHBOUR_Z = {0, 0, 0, 0, 1, -1};

    private OreObfuscation() {
    }

    /**
     * @param minY  lowest block height of the world (from {@code User#getMinWorldHeight()})
     * @param view  block states around the chunk (own sections + mirror)
     * @return a rewritten column, or {@code null} when nothing had to change
     */
    public static Column obfuscate(Column column, int minY, ClientVersion version, OreStates states,
                                   BlockView view, int maxReplacements) {
        if (column == null || !column.isFullChunk() || !states.usable() || maxReplacements <= 0) {
            return null;
        }
        BaseChunk[] sections = column.getChunks();
        if (sections == null || sections.length == 0) {
            return null;
        }
        int chunkX = column.getX();
        int chunkZ = column.getZ();
        BaseChunk[] patched = null;
        int replaced = 0;

        for (int index = 0; index < sections.length && replaced < maxReplacements; index++) {
            BaseChunk section = sections[index];
            if (!(section instanceof Chunk_v1_18 source) || !mayContainOre(source, states)) {
                continue;
            }
            Chunk_v1_18 copy = null;
            int baseY = minY + (index << 4);
            // Flat 16^3 loop (x = bits 0-3, z = bits 4-7, y = bits 8-11): one exit point, so the
            // per-chunk cap cannot be exceeded by an inner loop that keeps scanning.
            for (int cell = 0; cell < 4096 && replaced < maxReplacements; cell++) {
                int x = cell & 15;
                int z = (cell >> 4) & 15;
                int y = cell >> 8;
                if (!states.isValuable(source.getBlockId(x, y, z))) {
                    continue;
                }
                int worldX = (chunkX << 4) + x;
                int worldZ = (chunkZ << 4) + z;
                if (!enclosed(view, worldX, baseY + y, worldZ)) {
                    continue;
                }
                if (copy == null) {
                    copy = copyOf(source, version);
                }
                copy.getChunkData().set(x, y, z, states.fakeStateId());
                replaced++;
            }
            if (copy != null) {
                if (patched == null) {
                    patched = sections.clone();
                }
                patched[index] = copy;
            }
        }
        return patched == null ? null : withSections(column, patched);
    }

    /** Cheap pre-check: does the section palette contain any valuable ore state at all? */
    private static boolean mayContainOre(Chunk_v1_18 section, OreStates states) {
        DataPalette data = section.getChunkData();
        Palette palette = data != null ? data.palette : null;
        if (palette == null || palette == GlobalPalette.INSTANCE) {
            // No usable palette: the section has to be scanned block by block.
            return true;
        }
        int size = palette.size();
        if (size <= 0 || size > MAX_PALETTE_SCAN) {
            return true;
        }
        for (int i = 0; i < size; i++) {
            if (states.isValuable(palette.idToState(i))) {
                return true;
            }
        }
        return false;
    }

    /** All six neighbours must be known and opaque; anything unknown keeps the ore visible. */
    private static boolean enclosed(BlockView view, int x, int y, int z) {
        for (int i = 0; i < 6; i++) {
            int state = view.stateId(x + NEIGHBOUR_X[i], y + NEIGHBOUR_Y[i], z + NEIGHBOUR_Z[i]);
            if (state == BlockView.UNKNOWN_STATE || !view.opaque(state)) {
                return false;
            }
        }
        return true;
    }

    /** Byte-for-byte copy of one section, including the block/fluid counters and the biome palette. */
    private static Chunk_v1_18 copyOf(Chunk_v1_18 source, ClientVersion version) {
        Chunk_v1_18 copy = new Chunk_v1_18(version, source.getBlockCount(), source.getFluidCount(),
                DataPalette.createForChunk(), source.getBiomeData());
        DataPalette target = copy.getChunkData();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    target.set(x, y, z, source.getBlockId(x, y, z));
                }
            }
        }
        return copy;
    }

    /**
     * Rebuilds the column with the patched sections. Heightmaps and legacy biome data are carried over
     * with the constructor variant that matches what the read path populated, so no metadata is lost.
     */
    private static Column withSections(Column original, BaseChunk[] sections) {
        int x = original.getX();
        int z = original.getZ();
        boolean full = original.isFullChunk();
        TileEntity[] tiles = original.getTileEntities();
        Map<HeightmapType, long[]> heightmaps = original.getHeightmaps();
        if (heightmaps != null) {
            return new Column(x, z, full, sections, tiles, heightmaps);
        }
        NBTCompound nbt = original.hasHeightMaps() ? original.getHeightMaps() : null;
        int[] biomeInts = original.hasBiomeData() ? original.getBiomeDataInts() : null;
        byte[] biomeBytes = original.hasBiomeData() ? original.getBiomeDataBytes() : null;
        if (nbt != null && biomeInts != null) {
            return new Column(x, z, full, sections, tiles, nbt, biomeInts);
        }
        if (nbt != null && biomeBytes != null) {
            return new Column(x, z, full, sections, tiles, nbt, biomeBytes);
        }
        if (nbt != null) {
            return new Column(x, z, full, sections, tiles, nbt);
        }
        if (biomeInts != null) {
            return new Column(x, z, full, sections, tiles, biomeInts);
        }
        if (biomeBytes != null) {
            return new Column(x, z, full, sections, tiles, biomeBytes);
        }
        return new Column(x, z, full, sections, tiles);
    }
}
