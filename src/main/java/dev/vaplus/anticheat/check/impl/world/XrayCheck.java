package dev.vaplus.anticheat.check.impl.world;

import dev.vaplus.anticheat.check.BlockCheck;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.world.BlockInteraction;
import dev.vaplus.anticheat.world.BlockSight;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * X-Ray: mines valuable ores that were completely enclosed by opaque blocks and therefore could not
 * have been seen.
 *
 * <p>A player who digs a tunnel exposes the ores next to it: at the moment a block is broken, at
 * least the block the player came from is air, so any ore in the tunnel wall has an open neighbour.
 * An ore with all six neighbours opaque can only be found by X-Ray, by pure luck while turning a
 * corner, or by digging blindly into a wall. The check counts exactly those finds and ignores:
 * <ul>
 *   <li>ores that are not mirrored,</li>
 *   <li>ores with any non-mirrored neighbour (that neighbour might be air),</li>
 *   <li>ores with a see-through or empty neighbour ({@link BlockSight#obscures}).</li>
 * </ul>
 * The last two rules make the check quiet instead of wrong on incomplete world data. Creative and
 * spectator players are exempt through {@link CheckCategory#WORLD}.
 *
 * <p>{@code DIG_START} is used, not {@code DIG_FINISH}: the client announces the block it is about
 * to break, and the mirror still holds the pre-break state at that moment. Consecutive reports of
 * the same position (client re-starts after a cancel) are ignored.
 *
 * <p>The default ore list and the buffer limits are TUNING values, not vanilla facts.
 */
public final class XrayCheck extends Check implements BlockCheck {

    /** Default "valuable" ores: the blocks an X-Ray client is actually looking for. */
    public static final Set<String> DEFAULT_VALUABLE = normalize(List.of(
            "diamond_ore", "deepslate_diamond_ore",
            "emerald_ore", "deepslate_emerald_ore",
            "ancient_debris",
            "gold_ore", "deepslate_gold_ore", "nether_gold_ore",
            "lapis_ore", "deepslate_lapis_ore",
            "redstone_ore", "deepslate_redstone_ore"));

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("xray", CheckCategory.WORLD)
            .displayName("Xray")
            .description("Mines valuable ores that were completely enclosed and invisible")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.WORLD)
                    .withBufferLimit(2.0)
                    .withBufferDecay(0.1)
                    .withBufferMax(8.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT)
            .build();

    private static final int[] NEIGHBOUR_X = {1, -1, 0, 0, 0, 0};
    private static final int[] NEIGHBOUR_Y = {0, 0, 1, -1, 0, 0};
    private static final int[] NEIGHBOUR_Z = {0, 0, 0, 0, 1, -1};

    private final ComponentKey<WorldMirror> worldKey;

    private Set<String> valuable = DEFAULT_VALUABLE;
    private boolean onlyHidden = true;

    private boolean lastKnown;
    private int lastX;
    private int lastY;
    private int lastZ;

    public XrayCheck(PlayerData player, ComponentKey<WorldMirror> worldKey) {
        super(player, DESCRIPTOR);
        this.worldKey = worldKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        List<String> configured = options.getStringList("valuable-blocks");
        valuable = configured.isEmpty() ? DEFAULT_VALUABLE : valuableBlocks(configured);
        onlyHidden = options.getBoolean("only-hidden", true);
    }

    @Override
    protected void onReset() {
        lastKnown = false;
    }

    @Override
    public void onBlockInteraction(BlockInteraction interaction) {
        if (interaction.kind() != BlockInteraction.Kind.DIG_START) {
            return;
        }
        int x = interaction.blockX();
        int y = interaction.blockY();
        int z = interaction.blockZ();
        if (lastKnown && x == lastX && y == lastY && z == lastZ) {
            return;
        }
        lastKnown = true;
        lastX = x;
        lastY = y;
        lastZ = z;

        if (isExempt()) {
            return;
        }
        WorldMirror world = player.component(worldKey);
        BlockSnapshot block = world.getBlock(x, y, z);
        if (!block.known() || !valuable.contains(block.key().value())) {
            lowerBuffer();
            return;
        }
        if (onlyHidden) {
            int solid = 0;
            int unknown = 0;
            for (int i = 0; i < 6; i++) {
                BlockSnapshot neighbour = world.getBlock(x + NEIGHBOUR_X[i], y + NEIGHBOUR_Y[i],
                        z + NEIGHBOUR_Z[i]);
                if (!neighbour.known()) {
                    unknown++;
                } else if (BlockSight.obscures(neighbour)) {
                    solid++;
                }
            }
            if (unknown > 0 || solid < 6) {
                if (debugging()) {
                    debug("visible ore=" + block.key() + " solid=" + solid + " unknown=" + unknown);
                }
                lowerBuffer();
                return;
            }
        }
        if (debugging()) {
            debug("hidden ore=" + block.key() + " pos=" + x + "," + y + "," + z
                    + " buffer=" + VisibilityOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation("ore=" + block.key().value()
                    + " hidden=" + (onlyHidden ? "yes" : "no")
                    + " pos=" + x + "," + y + "," + z
                    + " buffer=" + VisibilityOptions.number(buffer()));
            // One window of enclosed finds is one alert; the next find starts a fresh window.
            resetBuffer();
        }
    }

    /** Ores currently counted (tests, diagnostics). */
    public Set<String> valuableBlocks() {
        return valuable;
    }

    /** Configured ids, or the defaults when the option is empty; normalized like {@link #normalize}.
     *  Empty input never disables the check silently. */
    static Set<String> valuableBlocks(List<String> ids) {
        Set<String> normalized = normalize(ids);
        return normalized.isEmpty() ? DEFAULT_VALUABLE : normalized;
    }

    /** Trims, lower-cases and adds the missing namespace; may return an empty immutable set. */
    private static Set<String> normalize(List<String> ids) {
        Set<String> result = new LinkedHashSet<>();
        for (String id : ids) {
            if (id == null) {
                continue;
            }
            String normalized = id.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                continue;
            }
            if (!normalized.contains(":")) {
                normalized = "minecraft:" + normalized;
            }
            result.add(normalized);
        }
        return Collections.unmodifiableSet(result);
    }
}
