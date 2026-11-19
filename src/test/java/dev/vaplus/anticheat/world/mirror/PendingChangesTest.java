package dev.vaplus.anticheat.world.mirror;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("PendingChanges (world uncertainty windows)")
class PendingChangesTest {

    @Test
    @DisplayName("a fresh instance has no window and intersects nothing")
    void empty() {
        PendingChanges pending = new PendingChanges();
        assertFalse(pending.any());
        assertFalse(pending.allPending());
        assertFalse(pending.intersects(-100, -64, -100, 100, 320, 100));
    }

    @Test
    @DisplayName("unresolved windows never settle; resolved ones settle once their stamp is acknowledged")
    void resolveAndSettle() {
        PendingChanges pending = new PendingChanges();
        pending.markBlock(1, 64, 1);
        pending.settle(Long.MAX_VALUE - 1);
        assertEquals(1, pending.blockCount(), "unresolved: the closing marker is not known yet");

        assertEquals(1, pending.resolve(5));
        pending.settle(4);
        assertTrue(pending.any(), "stamp 5 not acknowledged yet");
        pending.settle(5);
        assertFalse(pending.any());
        assertThrows(IllegalArgumentException.class, () -> pending.resolve(PendingChanges.UNRESOLVED));
        assertThrows(IllegalArgumentException.class, () -> pending.resolve(0));
    }

    @Test
    @DisplayName("resolve only stamps entries created since the last resolve")
    void laterEntriesGetLaterStamps() {
        PendingChanges pending = new PendingChanges();
        pending.markBlock(0, 64, 0);
        pending.resolve(3);
        pending.markChunk(2, 2);
        pending.resolve(7);
        pending.settle(3);
        assertEquals(0, pending.blockCount());
        assertEquals(1, pending.chunkCount());
        pending.settle(7);
        assertEquals(0, pending.chunkCount());
    }

    @Test
    @DisplayName("re-marking the same block or chunk extends its window instead of adding entries")
    void remarkExtends() {
        PendingChanges pending = new PendingChanges();
        pending.markBlock(0, 64, 0);
        pending.resolve(3);
        pending.markBlock(0, 64, 0);
        assertEquals(1, pending.blockCount());
        pending.settle(3);
        assertEquals(1, pending.blockCount(), "the newer change is still unresolved");
        pending.markChunk(1, 1);
        pending.markChunk(1, 1);
        assertEquals(1, pending.chunkCount());
    }

    @Test
    @DisplayName("intersection uses inclusive block boxes; chunk windows cover their whole column")
    void intersects() {
        PendingChanges pending = new PendingChanges();
        pending.markBlock(5, 70, -3);
        assertTrue(pending.intersects(5, 70, -3, 5, 70, -3));
        assertTrue(pending.intersects(0, 0, -10, 10, 100, 0));
        assertFalse(pending.intersects(6, 70, -3, 9, 70, -3));
        assertFalse(pending.intersects(5, 71, -3, 5, 90, -3));

        PendingChanges chunk = new PendingChanges();
        chunk.markChunk(-1, 2);
        assertTrue(chunk.intersects(-16, -64, 32, -16, -64, 32), "corner block of chunk (-1, 2)");
        assertTrue(chunk.intersects(-1, 300, 47, -1, 300, 47));
        assertFalse(chunk.intersects(0, 64, 32, 15, 64, 47), "chunk (0, 2)");
        assertTrue(chunk.intersects(-20, 64, 30, 3, 64, 33), "box spanning several chunks");
    }

    @Test
    @DisplayName("a whole-world window intersects everything until settled")
    void allPending() {
        PendingChanges pending = new PendingChanges();
        pending.markAll();
        assertTrue(pending.allPending());
        assertTrue(pending.intersects(1000, 0, 1000, 1000, 0, 1000));
        pending.resolve(9);
        pending.settle(8);
        assertTrue(pending.allPending());
        pending.settle(9);
        assertFalse(pending.any());
    }

    @Test
    @DisplayName("full lists escalate to a whole-world window (bounded memory, only more skips)")
    void overflow() {
        PendingChanges pending = new PendingChanges();
        for (int i = 0; i < PendingChanges.MAX_BLOCKS; i++) {
            pending.markBlock(i, 64, 0);
        }
        assertFalse(pending.allPending());
        pending.markBlock(-1, 64, 0);
        assertTrue(pending.allPending());
        assertEquals(1, pending.overflows());
        assertEquals(PendingChanges.MAX_BLOCKS, pending.blockCount());

        PendingChanges chunks = new PendingChanges();
        for (int i = 0; i <= PendingChanges.MAX_CHUNKS; i++) {
            chunks.markChunk(i, 0);
        }
        assertTrue(chunks.allPending());
    }

    @Test
    @DisplayName("forgetting a chunk drops its chunk and block windows only")
    void forgetChunk() {
        PendingChanges pending = new PendingChanges();
        pending.markChunk(0, 0);
        pending.markChunk(1, 0);
        pending.markBlock(3, 64, 3);
        pending.markBlock(17, 64, 3);
        pending.forgetChunk(0, 0);
        assertEquals(1, pending.chunkCount());
        assertEquals(1, pending.blockCount());
        assertTrue(pending.intersects(17, 64, 3, 17, 64, 3));
        pending.clearEntries();
        assertFalse(pending.any());
    }
}
