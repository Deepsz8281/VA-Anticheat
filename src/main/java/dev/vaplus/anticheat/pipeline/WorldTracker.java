package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.util.Vector3i;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUnloadChunk;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.world.mirror.ColumnStorage;
import dev.vaplus.anticheat.world.mirror.PendingChanges;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.List;

/**
 * World feeder: keeps the per-player {@link WorldMirror} in sync with the chunks and blocks the
 * client was sent. Outbound tracker (MONITOR, {@link OutboundStateListener}; cancelled packets are
 * never mirrored), owner thread.
 *
 * <h2>Packets (all fields verified with {@code javap} on PacketEvents 2.14.0)</h2>
 * <ul>
 *   <li>{@code CHUNK_DATA}: {@code WrapperPlayServerChunkData#getColumn()} →
 *       {@code Column#getX()/getZ()/isFullChunk()/getChunks()}; sections are attached as a
 *       {@link WorldColumn} with {@code User#getMinWorldHeight()} as the lowest block layer (the same
 *       user dimension PacketEvents used to size the section array). A non-full column (legacy
 *       protocols only) drops the chunk instead (UNKNOWN).</li>
 *   <li>{@code UNLOAD_CHUNK}: {@code getChunkX()/getChunkZ()} — removed immediately.</li>
 *   <li>{@code BLOCK_CHANGE}: {@code getBlockPosition()} + {@code getBlockId()} (global id, server
 *       protocol; {@code getBlockState()} is avoided because it maps unknown ids to air).</li>
 *   <li>{@code MULTI_BLOCK_CHANGE}: {@code getBlocks()} → {@code EncodedBlock#getX()/getY()/getZ()}
 *       (absolute: the constructor adds {@code sectionPos << 4}, see its bytecode) and
 *       {@code getBlockId()}.</li>
 *   <li>{@code JOIN_GAME}, {@code RESPAWN}: the mirror is cleared when the packet is sent (the
 *       client starts a new level; server-side world epoch, like {@code GameStateTracker}). It is
 *       UNCONFIRMED from fetched sources whether a same-dimension respawn keeps the client's chunks;
 *       clearing is the conservative choice (only more skips until the server resends them).</li>
 * </ul>
 *
 * <h2>Compensation (uncertainty window)</h2>
 * The mirror holds the newest state the server sent. Every change opens a window in
 * {@link WorldMirror#pending()} that closes once the first marker sent <em>after</em> the packet is
 * acknowledged: chunk loads per chunk, block updates per block (only if the id actually differs),
 * join/respawn for the whole world. The closing marker handle is learned in an after-send task as
 * {@code lastSentMarker() + 1} (handles are consecutive; any marker sent later travels behind the
 * packet — normally the next heartbeat or a bracket marker, so no extra ping per chunk is sent).
 * The engine settles the windows with {@code lastAckedMarker()} and SKIPs ({@code world-pending})
 * while its scan region touches one. Unloads open no window: the chunk reads UNKNOWN at once.
 *
 * <p>Allocation: besides the PacketEvents wrapper itself, one small after-send task per packet that
 * opened a window; no per-block objects.
 */
public final class WorldTracker implements OutboundTracker {

    private final ComponentKey<WorldMirror> worldKey;

    public WorldTracker(ComponentKey<WorldMirror> worldKey) {
        this.worldKey = worldKey;
    }

    @Override
    public void onSend(PlayerData player, PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.CHUNK_DATA) {
            Column column = new WrapperPlayServerChunkData(event).getColumn();
            if (column == null) {
                return;
            }
            if (!column.isFullChunk() || column.getChunks() == null) {
                onPartialChunk(player, column.getX(), column.getZ());
                return;
            }
            User user = event.getUser();
            BaseChunk[] sections = column.getChunks();
            onChunkData(player, event.getTasksAfterSend(), column.getX(), column.getZ(),
                    new WorldColumn(sections, user.getMinWorldHeight()));
        } else if (type == PacketType.Play.Server.UNLOAD_CHUNK) {
            WrapperPlayServerUnloadChunk packet = new WrapperPlayServerUnloadChunk(event);
            onUnloadChunk(player, packet.getChunkX(), packet.getChunkZ());
        } else if (type == PacketType.Play.Server.BLOCK_CHANGE) {
            WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(event);
            Vector3i position = packet.getBlockPosition();
            if (position != null && onBlockChange(player, position.getX(), position.getY(), position.getZ(),
                    packet.getBlockId())) {
                scheduleResolve(player, event.getTasksAfterSend());
            }
        } else if (type == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            WrapperPlayServerMultiBlockChange.EncodedBlock[] blocks =
                    new WrapperPlayServerMultiBlockChange(event).getBlocks();
            if (blocks == null) {
                return;
            }
            boolean opened = false;
            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : blocks) {
                opened |= onBlockChange(player, block.getX(), block.getY(), block.getZ(), block.getBlockId());
            }
            if (opened) {
                scheduleResolve(player, event.getTasksAfterSend());
            }
        } else if (type == PacketType.Play.Server.JOIN_GAME || type == PacketType.Play.Server.RESPAWN) {
            onWorldReset(player, event.getTasksAfterSend());
        }
    }

    /** Primitive entry point (tests/replays): a full chunk column was sent. */
    public void onChunkData(PlayerData player, List<Runnable> tasksAfterSend, int chunkX, int chunkZ,
                            ColumnStorage column) {
        WorldMirror world = player.component(worldKey);
        settle(player, world);
        world.loadColumn(chunkX, chunkZ, column);
        world.pending().markChunk(chunkX, chunkZ);
        scheduleResolve(player, tasksAfterSend);
    }

    /** Primitive entry point: a column the mirror cannot represent; the chunk becomes UNKNOWN. */
    void onPartialChunk(PlayerData player, int chunkX, int chunkZ) {
        player.component(worldKey).clearChunk(chunkX, chunkZ);
    }

    /** Primitive entry point: the client drops the chunk; it reads UNKNOWN immediately. */
    public void onUnloadChunk(PlayerData player, int chunkX, int chunkZ) {
        WorldMirror world = player.component(worldKey);
        world.clearChunk(chunkX, chunkZ);
        settle(player, world);
    }

    /**
     * Primitive entry point for one block update. The caller schedules the window resolution when
     * this returns {@code true}.
     *
     * @return whether an uncertainty window was opened (the id changed a mirrored block)
     */
    public boolean onBlockChange(PlayerData player, int x, int y, int z, int stateId) {
        WorldMirror world = player.component(worldKey);
        if (stateId < 0 || world.stateId(x, y, z) == stateId) {
            // Same state the client already has (or will have): nothing becomes uncertain.
            return false;
        }
        WorldMirror.UpdateResult result = world.setState(x, y, z, stateId);
        if (result == WorldMirror.UpdateResult.APPLIED) {
            world.pending().markBlock(x, y, z);
            return true;
        }
        // NO_CHUNK / OUT_OF_RANGE: the client ignores it too. DROPPED: the chunk now reads UNKNOWN.
        return false;
    }

    /** Primitive entry point for JOIN_GAME / RESPAWN. */
    public void onWorldReset(PlayerData player, List<Runnable> tasksAfterSend) {
        WorldMirror world = player.component(worldKey);
        world.clear();
        world.pending().markAll();
        scheduleResolve(player, tasksAfterSend);
    }

    /**
     * After the packet left: every still unresolved window closes with the next marker handle.
     * Runs on the owner thread (PacketEvents runs after-send tasks from the write promise listener).
     */
    public void scheduleResolve(PlayerData player, List<Runnable> tasksAfterSend) {
        WorldMirror world = player.component(worldKey);
        tasksAfterSend.add(() -> world.pending().resolve(player.acks().lastSentMarker() + 1));
    }

    private static void settle(PlayerData player, WorldMirror world) {
        PendingChanges pending = world.pending();
        if (pending.any()) {
            pending.settle(player.acks().lastAckedMarker());
        }
    }
}
