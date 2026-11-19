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
import dev.vaplus.anticheat.player.state.EffectState;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.world.BlockInteraction;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.Map;

/**
 * FastBreak / Nuker: blocks broken faster, or more blocks started per tick, than the vanilla client
 * allows. Time is counted in client ticks ({@code player.clientTicks()}); legacy clients without a
 * tick-end packet are skipped (no reliable tick source).
 *
 * <p>Vanilla client behaviour (client {@code MultiPlayerGameMode}, read from client code, UNCONFIRMED
 * against a capture): the crosshair target is picked once per tick and at most one block is started
 * per tick; a block whose per-tick damage reaches 1 breaks instantly with only a start packet; any
 * other block accumulates its damage once per tick, beginning in the start tick, and sends the finish
 * packet in the tick the progress reaches 1. Three signals follow:
 * <ul>
 *   <li><b>nuker</b> — starts on two or more different blocks within one client tick.</li>
 *   <li><b>instant-finish</b> — a finish in the same tick as its start: a block that needs a finish
 *       packet needs at least two damage ticks.</li>
 *   <li><b>speed</b> — for blocks of known hardness ({@link #HARDNESS}) the finish arrives earlier
 *       than even the fastest legal tool allows: per-tick damage is at most
 *       {@code max-tool-speed × (1 + 0.2 × haste level) / hardness / 30}; the check requires
 *       {@code ceil(1 / damage) - 1 - tolerance-ticks} ticks between start and finish. Unbreakable
 *       blocks (hardness &lt; 0) can never be finished in survival.</li>
 * </ul>
 *
 * <p>Why legit play does not trigger it: the speed bound assumes the best tool the server can hand
 * out ({@code max-tool-speed}, default 35 = netherite pickaxe with Efficiency V) and the tracked Haste
 * or Conduit Power level; anything that only slows breaking (mining fatigue, water, air) is ignored,
 * which keeps the bound a lower bound. Pending effect windows, unknown or pending blocks and
 * creative/spectator (WORLD category exemption) are not judged. Servers that raise mining speed by
 * other means (custom enchantment levels, {@code block_break_speed} attribute plugins) must raise
 * {@code max-tool-speed} or disable {@code check-speed}.
 *
 * <p>Known bypasses: fast break on blocks outside the hardness table, breaking up to the best-tool
 * speed with a worse tool (held items are not tracked), and nukers limited to one block per tick.
 * Hardness values: Minecraft Wiki block pages (not re-fetched this session: UNCONFIRMED).
 */
public final class FastBreakCheck extends Check implements BlockCheck {

    /** Netherite pickaxe (9) with Efficiency V (+ 5² + 1). */
    public static final double DEFAULT_MAX_TOOL_SPEED = 35.0;
    public static final int DEFAULT_TOLERANCE_TICKS = 1;

    /** Block hardness (vanilla, Minecraft Wiki; UNCONFIRMED in this session). Negative = unbreakable. */
    static final Map<String, Double> HARDNESS = Map.ofEntries(
            Map.entry("minecraft:bedrock", -1.0),
            Map.entry("minecraft:barrier", -1.0),
            Map.entry("minecraft:end_portal_frame", -1.0),
            Map.entry("minecraft:reinforced_deepslate", 55.0),
            Map.entry("minecraft:obsidian", 50.0),
            Map.entry("minecraft:crying_obsidian", 50.0),
            Map.entry("minecraft:netherite_block", 50.0),
            Map.entry("minecraft:respawn_anchor", 50.0),
            Map.entry("minecraft:ancient_debris", 30.0),
            Map.entry("minecraft:ender_chest", 22.5),
            Map.entry("minecraft:diamond_block", 5.0),
            Map.entry("minecraft:emerald_block", 5.0),
            Map.entry("minecraft:iron_block", 5.0),
            Map.entry("minecraft:coal_ore", 3.0),
            Map.entry("minecraft:iron_ore", 3.0),
            Map.entry("minecraft:copper_ore", 3.0),
            Map.entry("minecraft:gold_ore", 3.0),
            Map.entry("minecraft:diamond_ore", 3.0),
            Map.entry("minecraft:emerald_ore", 3.0),
            Map.entry("minecraft:lapis_ore", 3.0),
            Map.entry("minecraft:redstone_ore", 3.0),
            Map.entry("minecraft:deepslate_coal_ore", 4.5),
            Map.entry("minecraft:deepslate_iron_ore", 4.5),
            Map.entry("minecraft:deepslate_copper_ore", 4.5),
            Map.entry("minecraft:deepslate_gold_ore", 4.5),
            Map.entry("minecraft:deepslate_diamond_ore", 4.5),
            Map.entry("minecraft:deepslate_emerald_ore", 4.5),
            Map.entry("minecraft:deepslate_lapis_ore", 4.5),
            Map.entry("minecraft:deepslate_redstone_ore", 4.5),
            Map.entry("minecraft:end_stone", 3.0),
            Map.entry("minecraft:deepslate", 3.0),
            Map.entry("minecraft:cobbled_deepslate", 3.5));

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("fastbreak", CheckCategory.WORLD)
            .displayName("FastBreak")
            .description("Breaks blocks faster, or starts more blocks per tick, than vanilla allows")
            .defaults(CheckDefaults.of(CheckCategory.WORLD)
                    .withBufferLimit(2.0)
                    .withBufferDecay(0.1)
                    .withBufferMax(6.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ABILITY_CHANGE)
            .build();

    private final ComponentKey<WorldMirror> worldKey;
    private double maxToolSpeed = DEFAULT_MAX_TOOL_SPEED;
    private int toleranceTicks = DEFAULT_TOLERANCE_TICKS;
    private boolean checkNuker = true;
    private boolean checkSpeed = true;

    private boolean digging;
    private int digX;
    private int digY;
    private int digZ;
    private long digStartTick;
    private double digHardness = Double.NaN;

    private long startTick = Long.MIN_VALUE;
    private int startX;
    private int startY;
    private int startZ;

    public FastBreakCheck(PlayerData player, ComponentKey<WorldMirror> worldKey) {
        super(player, DESCRIPTOR);
        this.worldKey = worldKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        double speed = options.getDouble("max-tool-speed", DEFAULT_MAX_TOOL_SPEED);
        maxToolSpeed = Double.isFinite(speed) && speed >= 1.0 ? speed : DEFAULT_MAX_TOOL_SPEED;
        int tolerance = options.getInt("tolerance-ticks", DEFAULT_TOLERANCE_TICKS);
        toleranceTicks = tolerance >= 0 && tolerance <= 20 ? tolerance : DEFAULT_TOLERANCE_TICKS;
        checkNuker = options.getBoolean("check-nuker", true);
        checkSpeed = options.getBoolean("check-speed", true);
    }

    @Override
    protected void onReset() {
        digging = false;
        startTick = Long.MIN_VALUE;
    }

    @Override
    public void onBlockInteraction(BlockInteraction interaction) {
        BlockInteraction.Kind kind = interaction.kind();
        if (kind != BlockInteraction.Kind.DIG_START && kind != BlockInteraction.Kind.DIG_FINISH
                && kind != BlockInteraction.Kind.DIG_CANCEL) {
            return;
        }
        if (!player.tickEndMode()) {
            return;
        }
        long tick = player.clientTicks();
        int x = interaction.blockX();
        int y = interaction.blockY();
        int z = interaction.blockZ();
        if (kind == BlockInteraction.Kind.DIG_CANCEL) {
            digging = false;
            return;
        }
        if (kind == BlockInteraction.Kind.DIG_START) {
            onStart(tick, x, y, z, hardnessAt(x, y, z));
        } else {
            onFinish(tick, x, y, z);
        }
    }

    /** Decision logic on primitives (tests). {@code hardness} is NaN when unknown. */
    void onStart(long tick, int x, int y, int z, double hardness) {
        if (checkNuker && tick == startTick && (x != startX || y != startY || z != startZ)) {
            judge("nuker", "second block started in one tick " + startX + "," + startY + "," + startZ
                    + " -> " + x + "," + y + "," + z);
        }
        startTick = tick;
        startX = x;
        startY = y;
        startZ = z;
        digging = true;
        digX = x;
        digY = y;
        digZ = z;
        digStartTick = tick;
        digHardness = hardness;
    }

    /** Decision logic on primitives (tests). */
    void onFinish(long tick, int x, int y, int z) {
        if (!digging || x != digX || y != digY || z != digZ) {
            digging = false;
            return; // sequence errors belong to badpackets
        }
        digging = false;
        long elapsed = tick - digStartTick;
        if (elapsed <= 0) {
            judge("instant-finish", "finish in the start tick at " + x + "," + y + "," + z);
            return;
        }
        if (!checkSpeed || Double.isNaN(digHardness) || isExempt()) {
            return;
        }
        if (digHardness < 0.0) {
            judge("speed", "unbreakable block finished at " + x + "," + y + "," + z);
            return;
        }
        long required = requiredTicks(digHardness);
        if (required < 0 || elapsed >= required - toleranceTicks) {
            lowerBuffer();
            return;
        }
        judge("speed", "ticks=" + elapsed + " required=" + required + " hardness=" + digHardness
                + " at " + x + "," + y + "," + z);
    }

    /**
     * Fewest ticks between start and finish with the best legal tool; -1 when the block may break
     * instantly (no finish packet expected, nothing to judge) or the effect state is uncertain.
     */
    long requiredTicks(double hardness) {
        EffectState effects = player.effects();
        if (effects.pending(TrackedEffect.HASTE) || effects.pending(TrackedEffect.CONDUIT_POWER)) {
            return -1;
        }
        int haste = Math.max(effects.level(TrackedEffect.HASTE), effects.level(TrackedEffect.CONDUIT_POWER));
        double damage = maxToolSpeed * (1.0 + 0.2 * haste) / hardness / 30.0;
        if (!(damage < 1.0)) {
            return -1;
        }
        // damage accumulates once in the start tick and once per following tick
        return (long) Math.ceil(1.0 / damage) - 1;
    }

    private double hardnessAt(int x, int y, int z) {
        WorldMirror world = player.component(worldKey);
        if (world.pending().intersects(x, y, z, x, y, z)) {
            return Double.NaN;
        }
        BlockSnapshot block = world.getBlock(x, y, z);
        if (!block.known()) {
            return Double.NaN;
        }
        Double hardness = HARDNESS.get(block.key().value());
        return hardness != null ? hardness : Double.NaN;
    }

    private void judge(String signal, String details) {
        if (isExempt()) {
            return;
        }
        if (debugging()) {
            debug(signal + " " + details + " buffer=" + VisibilityOptions.number(buffer()));
        }
        if (raiseBuffer(signal.equals("speed") ? 1.0 : 1.5)) {
            violation(signal + " " + details);
        }
    }
}
