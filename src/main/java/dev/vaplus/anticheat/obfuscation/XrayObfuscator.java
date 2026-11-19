package dev.vaplus.anticheat.obfuscation;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckRegistry;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.impl.world.XrayCheck;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.pipeline.OutboundTracker;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.world.BlockSight;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.List;
import java.util.Objects;

/**
 * Anti-X-Ray prevention: rewrites outgoing chunk packets so hidden ores are sent as the fake block.
 *
 * <p>Runs as an {@link OutboundTracker} (MONITOR priority, cancelled packets skipped) for two
 * reasons: it needs the player's {@link WorldMirror} for neighbours outside the chunk, and the
 * trackers run in registration order inside {@code OutboundStateListener} — registering this one
 * after {@code WorldTracker} guarantees the mirror has been fed before it is read. It is a
 * <em>transformer</em>, not a state tracker: it never writes player state.
 *
 * <h2>Cost control</h2>
 * The X-Ray check's settings are read once per reload (cached by {@link CheckSettings} identity);
 * the PacketEvents wrapper is taken from {@code getLastUsedWrapper()} when the world tracker already
 * parsed it, so a chunk is not parsed twice; and {@link OreObfuscation} skips sections whose palette
 * contains no ore. A disabled option costs one map lookup per chunk packet.
 *
 * <h2>Configuration</h2>
 * {@code checks.xray.options}: {@code obfuscate} (default on), {@code fake-block} (default
 * {@code stone}), {@code max-per-chunk} (safety valve, {@code <= 0} disables rewriting) and the
 * shared {@code valuable-blocks} list. Detection stays active and always reads the real world.
 */
public final class XrayObfuscator implements OutboundTracker {

    public static final String OPTION_OBFUSCATE = "obfuscate";
    public static final String OPTION_FAKE_BLOCK = "fake-block";
    public static final String OPTION_MAX_PER_CHUNK = "max-per-chunk";

    /** TUNING: upper bound of rewritten blocks per chunk. */
    public static final int DEFAULT_MAX_PER_CHUNK = 512;

    private final ComponentKey<WorldMirror> worldKey;

    /** Registry slot of the xray check; {@code -2} = unresolved, {@code -1} = not registered. */
    private int slot = -2;
    private CheckSettings cachedSettings;
    private ClientVersion cachedVersion;
    private int cachedMaxPerChunk;
    private OreStates cachedStates;

    public XrayObfuscator(ComponentKey<WorldMirror> worldKey) {
        this.worldKey = Objects.requireNonNull(worldKey, "worldKey");
    }

    @Override
    public void onSend(PlayerData player, PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.CHUNK_DATA) {
            return;
        }
        if (player.context().governor().preventionPaused()) {
            // Server under load: vanilla chunks are cheaper than rewriting every chunk packet.
            return;
        }
        CheckSettings settings = xraySettings(player);
        if (settings == null) {
            return;
        }
        OreStates states = states(settings, version(event));
        if (states == null || !states.usable()) {
            return;
        }
        WrapperPlayServerChunkData wrapper = event.getLastUsedWrapper() instanceof WrapperPlayServerChunkData last
                ? last
                : new WrapperPlayServerChunkData(event);
        Column column = wrapper.getColumn();
        if (column == null || !column.isFullChunk() || column.getChunks() == null) {
            return;
        }
        int minY = event.getUser().getMinWorldHeight();
        WorldMirror mirror = player.component(worldKey);
        Column patched = OreObfuscation.obfuscate(column, minY, version(event), states,
                new ChunkBlockView(column, minY, mirror), cachedMaxPerChunk);
        if (patched == null) {
            return;
        }
        wrapper.setColumn(patched);
        event.setLastUsedWrapper(wrapper);
        event.markForReEncode(true);
    }

    /** Reads the xray section once per settings instance; a reload produces a new instance. */
    private OreStates states(CheckSettings settings, ClientVersion version) {
        if (cachedSettings == settings && cachedVersion == version) {
            return cachedStates;
        }
        CheckOptions options = settings.options();
        cachedMaxPerChunk = options.getInt(OPTION_MAX_PER_CHUNK, DEFAULT_MAX_PER_CHUNK);
        cachedSettings = settings;
        cachedVersion = version;
        cachedStates = null;
        if (!options.getBoolean(OPTION_OBFUSCATE, true) || cachedMaxPerChunk <= 0) {
            return null;
        }
        List<String> ores = options.getStringList("valuable-blocks");
        if (ores.isEmpty()) {
            ores = List.copyOf(XrayCheck.DEFAULT_VALUABLE);
        }
        cachedStates = OreStates.resolve(version, ores, options.getString(OPTION_FAKE_BLOCK, "stone"));
        return cachedStates;
    }

    private CheckSettings xraySettings(PlayerData player) {
        CheckRegistry registry = player.context().checks();
        int resolved = slot;
        if (resolved == -2) {
            resolved = -1;
            try {
                for (CheckRegistry.Entry entry : registry.entries()) {
                    if (entry.descriptor() == XrayCheck.DESCRIPTOR) {
                        resolved = entry.slot();
                        break;
                    }
                }
            } catch (IllegalStateException notFrozenYet) {
                // Called before enable completed: the next chunk packet resolves it.
                return null;
            }
            slot = resolved;
        }
        return resolved < 0 ? null : registry.settings(resolved);
    }

    private static ClientVersion version(PacketSendEvent event) {
        ServerVersion server = event.getServerVersion();
        return server != null
                ? server.toClientVersion()
                : com.github.retrooper.packetevents.PacketEvents.getAPI().getServerManager()
                        .getVersion().toClientVersion();
    }

    /**
     * Block states for the neighbour test: the chunk's own sections (original column, not the
     * patched copy) and the player's mirror for everything outside the chunk.
     */
    private static final class ChunkBlockView implements BlockView {

        private final BaseChunk[] sections;
        private final int minY;
        private final int chunkX;
        private final int chunkZ;
        private final WorldMirror mirror;

        private ChunkBlockView(Column column, int minY, WorldMirror mirror) {
            this.sections = column.getChunks();
            this.minY = minY;
            this.chunkX = column.getX();
            this.chunkZ = column.getZ();
            this.mirror = mirror;
        }

        @Override
        public int stateId(int x, int y, int z) {
            if ((x >> 4) == chunkX && (z >> 4) == chunkZ) {
                int offset = y - minY;
                if (offset < 0) {
                    return UNKNOWN_STATE;
                }
                int index = offset >> 4;
                if (index >= sections.length || sections[index] == null) {
                    return UNKNOWN_STATE;
                }
                return sections[index].getBlockId(x & 15, offset & 15, z & 15);
            }
            return mirror.stateId(x, y, z);
        }

        @Override
        public boolean opaque(int stateId) {
            if (stateId < 0) {
                return false;
            }
            BlockSnapshot snapshot = mirror.resolver().resolve(stateId);
            return snapshot != null && snapshot.known() && BlockSight.obscures(snapshot);
        }
    }
}
