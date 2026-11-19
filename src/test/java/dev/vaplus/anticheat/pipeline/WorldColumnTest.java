package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.chunk.palette.DataPalette;
import dev.vaplus.anticheat.testutil.FakePacketEvents;
import dev.vaplus.anticheat.world.mirror.ColumnStorage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("WorldColumn (PacketEvents chunk sections as mirror storage)")
class WorldColumnTest {

    @BeforeAll
    static void installApi() {
        FakePacketEvents.install(ServerVersion.V_1_21_4);
    }

    @AfterAll
    static void uninstallApi() {
        FakePacketEvents.uninstall();
    }

    /** A 1.18+ section filled with air (id 0 registered first, so empty storage means air). */
    private static Chunk_v1_18 airSection() {
        Chunk_v1_18 section = new Chunk_v1_18(ClientVersion.V_1_21_4, 0,
                DataPalette.createForChunk(), DataPalette.createForBiome());
        section.getChunkData().set(0, 0, 0, 0);
        return section;
    }

    private static WorldColumn column(int sections, int minY) {
        BaseChunk[] array = new BaseChunk[sections];
        for (int i = 0; i < sections; i++) {
            array[i] = airSection();
        }
        return new WorldColumn(array, minY);
    }

    @Test
    @DisplayName("section i covers [minY + 16 i, minY + 16 i + 16) with section-local x/y/z")
    void heightMapping() {
        WorldColumn column = column(24, -64);
        assertEquals(-64, column.minY());
        assertEquals(384, column.height());
        assertTrue(column.containsY(-64));
        assertTrue(column.containsY(319));
        assertFalse(column.containsY(320));
        assertFalse(column.containsY(-65));

        assertTrue(column.setStateId(3, -49, 12, 1), "section 0 at local y 15");
        assertTrue(column.setStateId(15, 64, 0, 7), "section 8 at local y 0");
        assertEquals(1, column.stateId(3, -49, 12));
        assertEquals(7, column.stateId(15, 64, 0));
        assertEquals(0, column.stateId(3, -48, 12), "neighbouring section untouched");
        assertEquals(0, column.stateId(12, -49, 3), "x and z are not swapped");
        assertEquals(ColumnStorage.UNKNOWN_STATE, column.stateId(0, 320, 0));
        assertEquals(ColumnStorage.UNKNOWN_STATE, column.stateId(0, -65, 0));
    }

    @Test
    @DisplayName("writes keep ids PacketEvents does not know (no air fallback) and grow the palette")
    void unknownIdsAreKept() {
        WorldColumn column = column(1, 0);
        for (int i = 0; i < 40; i++) {
            assertTrue(column.setStateId(i % 16, i / 16, 0, 100 + i));
        }
        assertTrue(column.setStateId(1, 1, 1, 77777));
        assertEquals(77777, column.stateId(1, 1, 1));
        for (int i = 0; i < 40; i++) {
            assertEquals(100 + i, column.stateId(i % 16, i / 16, 0));
        }
        assertEquals(0, column.stateId(15, 15, 15));
    }

    @Test
    @DisplayName("missing sections read unknown; legacy section formats and bad input are refused")
    void refusals() {
        BaseChunk legacy = new BaseChunk() {
            @Override
            public int getBlockId(int x, int y, int z) {
                return 5;
            }

            @Override
            public void set(int x, int y, int z, int id) {
                throw new AssertionError("must not route through the id->air mapping");
            }

            @Override
            public boolean isEmpty() {
                return false;
            }
        };
        WorldColumn column = new WorldColumn(new BaseChunk[]{null, legacy}, 0);
        assertEquals(ColumnStorage.UNKNOWN_STATE, column.stateId(0, 3, 0));
        assertFalse(column.setStateId(0, 3, 0, 1), "missing section");
        assertEquals(5, column.stateId(0, 20, 0));
        assertFalse(column.setStateId(0, 20, 0, 1), "pre-1.18 section format");
        WorldColumn modern = column(1, 0);
        assertFalse(modern.setStateId(0, 3, 0, -1));
        assertFalse(modern.setStateId(0, 16, 0, 1));
    }
}
