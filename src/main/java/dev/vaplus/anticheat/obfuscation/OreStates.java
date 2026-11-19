package dev.vaplus.anticheat.obfuscation;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Protocol state ids of the ores that xray prevention replaces and of the fake block it uses.
 *
 * <p>State ids are <em>server protocol</em> ids (chunk packets are rewritten before Via sees them),
 * so the instance is resolved for the server's mapping version and never for a client version.
 * One small {@code boolean} table gives an allocation-free {@link #isValuable(int)} check on the
 * chunk hot path; ids outside the table (unknown/negative) are never valuable.
 *
 * <p>Resolution failures (unknown block name, unavailable registry) leave the instance unusable
 * instead of throwing: prevention then simply does not rewrite anything.
 */
public final class OreStates {

    /** 1.21/26.x have fewer than 2^16 block states; larger ids are treated as not valuable. */
    public static final int STATE_TABLE_SIZE = 1 << 16;

    private final boolean[] valuable;
    private final int fakeStateId;
    private final int oreCount;

    private OreStates(boolean[] valuable, int fakeStateId, int oreCount) {
        this.valuable = valuable;
        this.fakeStateId = fakeStateId;
        this.oreCount = oreCount;
    }

    /**
     * Resolves the configured ore ids and the fake block for {@code version}.
     *
     * @param oreIds    namespaced block ids, {@code minecraft:} may be omitted
     * @param fakeBlock block that replaces hidden ores ({@code stone} by default)
     */
    public static OreStates resolve(ClientVersion version, List<String> oreIds, String fakeBlock) {
        Objects.requireNonNull(version, "version");
        boolean[] table = new boolean[STATE_TABLE_SIZE];
        int found = 0;
        for (String id : oreIds) {
            int state = stateId(version, id);
            if (state >= 0 && state < table.length && !table[state]) {
                table[state] = true;
                found++;
            }
        }
        return new OreStates(table, stateId(version, fakeBlock), found);
    }

    /** Test/diagnostic factory from raw state ids. */
    public static OreStates of(int[] valuableStateIds, int fakeStateId) {
        boolean[] table = new boolean[STATE_TABLE_SIZE];
        int found = 0;
        for (int state : valuableStateIds) {
            if (state >= 0 && state < table.length && !table[state]) {
                table[state] = true;
                found++;
            }
        }
        return new OreStates(table, fakeStateId, found);
    }

    public boolean isValuable(int stateId) {
        return stateId >= 0 && stateId < valuable.length && valuable[stateId];
    }

    /** Fake block the client sees instead of a hidden ore; negative when unresolved. */
    public int fakeStateId() {
        return fakeStateId;
    }

    /** Number of configured ores that resolved to a state id. */
    public int oreCount() {
        return oreCount;
    }

    /** Whether obfuscation can run at all: at least one ore and a valid fake block. */
    public boolean usable() {
        return oreCount > 0 && fakeStateId >= 0;
    }

    /** Resolves one block name to its default state id for {@code version}, or {@code -1}. */
    public static int stateId(ClientVersion version, String blockId) {
        if (blockId == null) {
            return -1;
        }
        String normalized = blockId.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return -1;
        }
        if (!normalized.contains(":")) {
            normalized = "minecraft:" + normalized;
        }
        try {
            WrappedBlockState state = WrappedBlockState.getByString(version, normalized);
            return state != null ? state.getGlobalId() : -1;
        } catch (RuntimeException | LinkageError failure) {
            return -1;
        }
    }

    @Override
    public String toString() {
        return "OreStates[ores=" + oreCount + ", fake=" + fakeStateId + "]";
    }
}
