package dev.vaplus.anticheat.pipeline;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateValue;
import dev.vaplus.anticheat.world.mirror.BlockKey;
import dev.vaplus.anticheat.world.mirror.BlockProperties;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import dev.vaplus.anticheat.world.mirror.VanillaBlocks;

import java.util.EnumSet;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@link BlockStateResolver} for PacketEvents global block-state ids (the ids inside chunk sections
 * and block-update packets, in the <em>server's</em> protocol: PacketEvents parses outbound packets
 * with the server version, ViaVersion translates afterwards).
 *
 * <p>Mapping: global id → {@code WrappedBlockState} → block type key → {@link VanillaBlocks}
 * metadata. Verified with {@code javap} / a probe against the installed PacketEvents 2.14.0 jar:
 * <ul>
 *   <li>{@code WrappedBlockState.getByGlobalId(ClientVersion, int, boolean)} returns the shared
 *       {@code AIR} state for ids it does not know ({@code Map.getOrDefault(id, AIR)} in its
 *       bytecode). This class therefore accepts a result only if its {@code getGlobalId()} equals the
 *       requested id; anything else resolves to {@link BlockSnapshot#UNKNOWN}, never to air.</li>
 *   <li>{@code StateType.getMapped().getName()} is a namespaced {@code ResourceLocation}
 *       (probe: id 1 → {@code minecraft:stone} for 1.21, 1.21.4 and 26.3 mappings).</li>
 *   <li>{@code getInternalData()} carries {@code StateValue.DRAG} for bubble columns and
 *       {@code StateValue.WATERLOGGED} for waterloggable blocks ({@code isDrag()} would throw for
 *       states without the property, so the map is read instead).</li>
 * </ul>
 * Blocks missing from the {@link VanillaBlocks} table keep their key but get its uncertain FULL
 * fallback, so the engine treats them as complex terrain (skip), never as air. Waterlogged blocks
 * additionally become liquid with {@code UNCONFIRMED_BLOCK_STATE}.
 *
 * <p>Thread safety: one shared instance for all players (netty threads). The cache is a plain
 * array of immutable snapshots; racing writers store equal values (benign race, final-field
 * publication). The server version is looked up lazily from the PacketEvents API.
 */
public final class WorldBlockStates implements BlockStateResolver {

    /** Ids below this are cached (26.3 has fewer than 2^15 states, see the probe above). */
    static final int CACHE_SIZE = 1 << 16;

    private final Supplier<ClientVersion> versionSource;
    private final BlockSnapshot[] cache = new BlockSnapshot[CACHE_SIZE];
    private volatile ClientVersion version;

    /** @param versionSource mapping version; may return {@code null} while unknown (nothing cached then) */
    public WorldBlockStates(Supplier<ClientVersion> versionSource) {
        this.versionSource = versionSource;
    }

    /** Resolver for the running server's protocol, looked up from the PacketEvents API on first use. */
    public static WorldBlockStates forServer() {
        return new WorldBlockStates(WorldBlockStates::serverMappingVersion);
    }

    /** Fixed mapping version (tests, replays). */
    public static WorldBlockStates forVersion(ClientVersion version) {
        return new WorldBlockStates(() -> version);
    }

    static ClientVersion serverMappingVersion() {
        PacketEventsAPI<?> api = PacketEvents.getAPI();
        if (api == null || api.getServerManager() == null) {
            return null;
        }
        ServerVersion server = api.getServerManager().getVersion();
        return server == null ? null : server.toClientVersion();
    }

    @Override
    public BlockSnapshot resolve(int stateId) {
        if (stateId < 0) {
            return BlockSnapshot.UNKNOWN;
        }
        if (stateId < CACHE_SIZE) {
            BlockSnapshot cached = cache[stateId];
            if (cached != null) {
                return cached;
            }
        }
        ClientVersion mappings = mappingVersion();
        if (mappings == null) {
            return BlockSnapshot.UNKNOWN;
        }
        BlockSnapshot snapshot = lookup(mappings, stateId);
        if (stateId < CACHE_SIZE) {
            cache[stateId] = snapshot;
        }
        return snapshot;
    }

    private ClientVersion mappingVersion() {
        ClientVersion known = version;
        if (known == null) {
            known = versionSource.get();
            version = known;
        }
        return known;
    }

    private static BlockSnapshot lookup(ClientVersion mappings, int stateId) {
        WrappedBlockState state;
        try {
            state = WrappedBlockState.getByGlobalId(mappings, stateId, false);
        } catch (RuntimeException | LinkageError failure) {
            return BlockSnapshot.UNKNOWN;
        }
        if (state == null || state.getGlobalId() != stateId) {
            // PacketEvents falls back to AIR for unknown ids; that must not become known air.
            return BlockSnapshot.UNKNOWN;
        }
        return snapshotOf(state);
    }

    /** Snapshot for a known PacketEvents state (package-private for tests). */
    static BlockSnapshot snapshotOf(WrappedBlockState state) {
        BlockKey key;
        try {
            key = new BlockKey(state.getType().getMapped().getName().toString());
        } catch (RuntimeException failure) {
            return BlockSnapshot.UNKNOWN;
        }
        BlockProperties properties = VanillaBlocks.properties(key);
        Map<StateValue, Object> data = state.getInternalData();
        if (VanillaBlocks.BUBBLE_COLUMN.equals(key)) {
            Object drag = data.get(StateValue.DRAG);
            if (Boolean.TRUE.equals(drag)) {
                properties = VanillaBlocks.BUBBLE_COLUMN_DOWN_PROPERTIES;
            } else if (Boolean.FALSE.equals(drag)) {
                properties = VanillaBlocks.BUBBLE_COLUMN_UP_PROPERTIES;
            }
        }
        if (Boolean.TRUE.equals(data.get(StateValue.WATERLOGGED))) {
            properties = waterlogged(properties);
        }
        return new BlockSnapshot(key, properties, true);
    }

    private static BlockProperties waterlogged(BlockProperties base) {
        EnumSet<BlockProperties.Uncertainty> uncertainties = EnumSet.of(BlockProperties.Uncertainty.UNCONFIRMED_BLOCK_STATE);
        uncertainties.addAll(base.uncertainties());
        return new BlockProperties(base.friction(), base.speedFactor(), base.jumpFactor(),
                BlockProperties.Liquid.WATER, base.climbable(), base.collision(), base.flags(), uncertainties);
    }
}
