package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import dev.vaplus.anticheat.testutil.FakePacketEvents;
import dev.vaplus.anticheat.world.mirror.BlockKey;
import dev.vaplus.anticheat.world.mirror.BlockProperties;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("WorldBlockStates (PacketEvents global ids -> mirror snapshots)")
class WorldBlockStatesTest {

    private static final ClientVersion VERSION = ClientVersion.V_1_21_4;

    @BeforeAll
    static void installApi() {
        FakePacketEvents.install(ServerVersion.V_1_21_4);
    }

    @AfterAll
    static void uninstallApi() {
        FakePacketEvents.uninstall();
    }

    private static int id(String state) {
        return WrappedBlockState.getByString(VERSION, state, false).getGlobalId();
    }

    @Test
    @DisplayName("air and stone resolve to their VanillaBlocks metadata, cached per id")
    void knownStates() {
        WorldBlockStates states = WorldBlockStates.forVersion(VERSION);
        BlockSnapshot air = states.resolve(0);
        assertTrue(air.known());
        assertEquals(VanillaBlocks.AIR, air.key());
        assertEquals(BlockProperties.Collision.EMPTY, air.properties().collision());
        BlockSnapshot stone = states.resolve(id("minecraft:stone"));
        assertEquals(VanillaBlocks.STONE, stone.key());
        assertEquals(VanillaBlocks.DEFAULT_PROPERTIES, stone.properties());
        assertSame(stone, states.resolve(id("minecraft:stone")));
    }

    @Test
    @DisplayName("ids PacketEvents does not know stay UNKNOWN (PacketEvents itself would answer air)")
    void unknownIds() {
        assertEquals(0, WrappedBlockState.getByGlobalId(VERSION, 999_999, false).getGlobalId(),
                "precondition: PacketEvents maps unknown ids to air");
        WorldBlockStates states = WorldBlockStates.forVersion(VERSION);
        assertSame(BlockSnapshot.UNKNOWN, states.resolve(999_999));
        assertSame(BlockSnapshot.UNKNOWN, states.resolve(-1));
        assertSame(BlockSnapshot.UNKNOWN, new WorldBlockStates(() -> null).resolve(1),
                "no mapping version yet: unknown, not cached");
    }

    @Test
    @DisplayName("blocks missing from the table keep their key with the uncertain fallback")
    void untabledBlocks() {
        BlockSnapshot torch = WorldBlockStates.forVersion(VERSION).resolve(id("minecraft:torch"));
        assertTrue(torch.known());
        assertEquals(new BlockKey("minecraft:torch"), torch.key());
        assertTrue(torch.uncertain(), "never plain air or a plain full block");
    }

    @Test
    @DisplayName("state-aware: bubble column drag direction and waterlogging")
    void stateAware() {
        WorldBlockStates states = WorldBlockStates.forVersion(VERSION);
        BlockSnapshot down = states.resolve(id("minecraft:bubble_column[drag=true]"));
        assertTrue(down.properties().has(BlockProperties.SpecialFlag.BUBBLE_COLUMN_DOWN));
        BlockSnapshot up = states.resolve(id("minecraft:bubble_column[drag=false]"));
        assertTrue(up.properties().has(BlockProperties.SpecialFlag.BUBBLE_COLUMN_UP));

        BlockSnapshot dry = states.resolve(id("minecraft:ladder[facing=north,waterlogged=false]"));
        assertEquals(BlockProperties.Liquid.NONE, dry.properties().liquid());
        BlockSnapshot wet = states.resolve(id("minecraft:ladder[facing=north,waterlogged=true]"));
        assertEquals(BlockProperties.Liquid.WATER, wet.properties().liquid());
        assertTrue(wet.properties().climbable());
        assertTrue(wet.properties().uncertainties().contains(BlockProperties.Uncertainty.UNCONFIRMED_BLOCK_STATE));
    }

    @Test
    @DisplayName("forServer() takes the mapping version from the PacketEvents server version")
    void serverVersion() {
        // Another test class may have left a different fake API installed; compare with whatever is active.
        assertEquals(PacketEvents.getAPI().getServerManager().getVersion().toClientVersion(),
                WorldBlockStates.serverMappingVersion());
        assertEquals(VanillaBlocks.STONE, WorldBlockStates.forServer().resolve(id("minecraft:stone")).key());
        assertFalse(WorldBlockStates.forServer().resolve(999_999).known());
    }
}
