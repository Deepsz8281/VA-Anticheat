package dev.vaplus.anticheat.obfuscation;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("xray prevention: outgoing chunk rewriting")
class OreObfuscationTest {

    private static final ClientVersion VERSION = ClientVersion.V_1_21;
    private static final int MIN_Y = 0;
    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int ORE = 2;
    private static final int FAKE = 3;

    @Test
    @DisplayName("a fully enclosed ore is sent as the fake block, the original stays in the mirror")
    void enclosedOreIsReplaced() {
        Chunk_v1_18 section = solidSection();
        section.getChunkData().set(5, 5, 5, ORE);
        Column column = column(section);

        Column patched = OreObfuscation.obfuscate(column, MIN_Y, VERSION, states(), view(section), 512);

        assertNotNull(patched);
        assertNotSame(column, patched);
        assertNotSame(column.getChunks()[0], patched.getChunks()[0]);
        assertEquals(FAKE, patched.getChunks()[0].getBlockId(5, 5, 5));
        assertEquals(ORE, column.getChunks()[0].getBlockId(5, 5, 5));
        assertEquals(STONE, patched.getChunks()[0].getBlockId(0, 0, 0));
    }

    @Test
    @DisplayName("column position, chunk flag and tile entities survive the rewrite")
    void keepsColumnMetadata() {
        Chunk_v1_18 section = solidSection();
        section.getChunkData().set(3, 3, 3, ORE);
        Column column = new Column(7, -3, true, new BaseChunk[]{section}, new TileEntity[0]);

        Column patched = OreObfuscation.obfuscate(column, MIN_Y, VERSION, states(), view(section), 512);

        assertNotNull(patched);
        assertEquals(7, patched.getX());
        assertEquals(-3, patched.getZ());
        assertTrue(patched.isFullChunk());
        assertEquals(0, patched.getTileEntities().length);
    }

    @Test
    @DisplayName("an ore with an air neighbour stays visible")
    void exposedOreIsUntouched() {
        Chunk_v1_18 section = solidSection();
        section.getChunkData().set(5, 5, 5, ORE);
        section.getChunkData().set(6, 5, 5, AIR);

        assertNull(OreObfuscation.obfuscate(column(section), MIN_Y, VERSION, states(), view(section), 512));
    }

    @Test
    @DisplayName("an ore next to unmirrored data stays visible instead of guessing")
    void unknownNeighbourIsUntouched() {
        Chunk_v1_18 section = solidSection();
        section.getChunkData().set(5, 5, 5, ORE);

        BlockView unknownAbove = new BlockView() {
            @Override
            public int stateId(int x, int y, int z) {
                return y == 6 ? UNKNOWN_STATE : view(section).stateId(x, y, z);
            }

            @Override
            public boolean opaque(int stateId) {
                return stateId == STONE || stateId == ORE || stateId == FAKE;
            }
        };

        assertNull(OreObfuscation.obfuscate(column(section), MIN_Y, VERSION, states(), unknownAbove, 512));
    }

    @Test
    @DisplayName("sections without an ore state in their palette are skipped without copying")
    void skipsSectionsWithoutOres() {
        Chunk_v1_18 section = solidSection();
        Column column = column(section);

        assertNull(OreObfuscation.obfuscate(column, MIN_Y, VERSION, states(), view(section), 512));
        assertSame(section, column.getChunks()[0]);
    }

    @Test
    @DisplayName("max-per-chunk caps the rewritten blocks")
    void respectsCap() {
        Chunk_v1_18 section = solidSection();
        section.getChunkData().set(2, 2, 2, ORE);
        section.getChunkData().set(9, 9, 9, ORE);

        Column patched = OreObfuscation.obfuscate(column(section), MIN_Y, VERSION, states(), view(section), 1);

        assertNotNull(patched);
        assertEquals(1, count(patched.getChunks()[0], FAKE));
    }

    @Test
    @DisplayName("the cap also holds when several hidden ores share one y layer")
    void respectsCapOnSameLayer() {
        Chunk_v1_18 section = solidSection();
        section.getChunkData().set(2, 2, 2, ORE);
        section.getChunkData().set(9, 2, 9, ORE);

        Column patched = OreObfuscation.obfuscate(column(section), MIN_Y, VERSION, states(), view(section), 1);

        assertNotNull(patched);
        assertEquals(1, count(patched.getChunks()[0], FAKE));
        assertEquals(1, count(patched.getChunks()[0], ORE));
    }

    @Test
    @DisplayName("only fully enclosed ores of the same section are replaced")
    void mixesHiddenAndExposedOres() {
        Chunk_v1_18 section = solidSection();
        section.getChunkData().set(5, 5, 5, ORE);
        section.getChunkData().set(9, 5, 5, ORE);
        section.getChunkData().set(10, 5, 5, AIR);

        Column patched = OreObfuscation.obfuscate(column(section), MIN_Y, VERSION, states(), view(section), 512);

        assertNotNull(patched);
        assertEquals(FAKE, patched.getChunks()[0].getBlockId(5, 5, 5));
        assertEquals(ORE, patched.getChunks()[0].getBlockId(9, 5, 5));
    }

    @Test
    @DisplayName("disabled or unresolvable settings leave the packet alone")
    void ignoresUnusableSettings() {
        Chunk_v1_18 section = solidSection();
        section.getChunkData().set(5, 5, 5, ORE);
        Column column = column(section);

        assertNull(OreObfuscation.obfuscate(column, MIN_Y, VERSION, OreStates.of(new int[]{ORE}, -1),
                view(section), 512));
        assertNull(OreObfuscation.obfuscate(column, MIN_Y, VERSION, states(), view(section), 0));
        assertTrue(states().usable());
        assertFalse(OreStates.of(new int[0], FAKE).usable(), "no resolvable ore disables prevention");
    }

    @Test
    @DisplayName("a non-full chunk is never rewritten")
    void ignoresNonFullChunk() {
        Chunk_v1_18 section = solidSection();
        section.getChunkData().set(5, 5, 5, ORE);
        Column partial = new Column(0, 0, false, new BaseChunk[]{section}, new TileEntity[0]);

        assertNull(OreObfuscation.obfuscate(partial, MIN_Y, VERSION, states(), view(section), 512));
    }

    private static OreStates states() {
        return OreStates.of(new int[]{ORE}, FAKE);
    }

    private static Column column(BaseChunk... sections) {
        return new Column(0, 0, true, sections, new TileEntity[0]);
    }

    private static Chunk_v1_18 solidSection() {
        Chunk_v1_18 section = new Chunk_v1_18(VERSION);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    section.getChunkData().set(x, y, z, STONE);
                }
            }
        }
        return section;
    }

    /** Reads the section itself; everything outside the 16^3 box counts as solid stone. */
    private static BlockView view(Chunk_v1_18 section) {
        return new BlockView() {
            @Override
            public int stateId(int x, int y, int z) {
                if (x < 0 || x > 15 || y < 0 || y > 15 || z < 0 || z > 15) {
                    return STONE;
                }
                return section.getBlockId(x, y, z);
            }

            @Override
            public boolean opaque(int stateId) {
                return stateId == STONE || stateId == ORE || stateId == FAKE;
            }
        };
    }

    private static int count(BaseChunk section, int state) {
        int matches = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (section.getBlockId(x, y, z) == state) {
                        matches++;
                    }
                }
            }
        }
        return matches;
    }
}
