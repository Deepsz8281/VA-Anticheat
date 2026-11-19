package dev.vaplus.anticheat.world.mirror;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@DisplayName("BlockPos (immutable block coordinates)")
class BlockPosTest {

    @ParameterizedTest
    @CsvSource({
            "0, 0, 0, 0, 0, 0, 0",
            "5, 7, 0, 0, 5, 7, 0",
            "15, 15, 0, 0, 15, 15, 0",
            "16, 16, 1, 1, 0, 0, 16",
            "31, -17, 1, -2, 15, 15, 31",
            "-1, -1, -1, -1, 15, 15, -1",
            "-16, -16, -1, -1, 0, 0, -16",
            "-17, -33, -2, -3, 15, 15, -17"
    })
    @DisplayName("chunk and local coordinates follow bit masking, including negative world coordinates")
    void chunkAndLocalCoordinates(int x, int z, int expectedChunkX, int expectedChunkZ,
                                  int expectedLocalX, int expectedLocalZ, int y) {
        BlockPos position = new BlockPos(x, y, z);
        assertEquals(expectedChunkX, position.chunkX());
        assertEquals(expectedChunkZ, position.chunkZ());
        assertEquals(expectedLocalX, position.localX(), "localX of " + x);
        assertEquals(expectedLocalZ, position.localZ(), "localZ of " + z);
    }

    @Test
    @DisplayName("y is stored as-is, including values outside any dimension height")
    void yNotPacked() {
        assertEquals(-64, new BlockPos(0, -64, 0).y());
        assertEquals(320, new BlockPos(0, 320, 0).y());
        assertEquals(Integer.MIN_VALUE, new BlockPos(0, Integer.MIN_VALUE, 0).y());
        assertEquals(Integer.MAX_VALUE, new BlockPos(0, Integer.MAX_VALUE, 0).y());
    }

    @Test
    @DisplayName("record equality and hash code are value based")
    void recordEquality() {
        BlockPos first = new BlockPos(10, 64, -3);
        BlockPos second = new BlockPos(10, 64, -3);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, new BlockPos(10, 64, -2));
        assertNotEquals(first, new BlockPos(11, 64, -3));
        assertNotEquals(first, new BlockPos(10, 65, -3));
    }
}
