package dev.vaplus.anticheat.check.impl.world;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import dev.vaplus.anticheat.check.BlockCheck;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.world.BlockInteraction;

import java.util.Arrays;

/**
 * FastUse: item uses (right click without a block target: snowballs, eggs, experience bottles,
 * splash potions, wind charges, fishing rods, ...) faster than a vanilla client sends them.
 *
 * <p>Vanilla facts (client code, UNCONFIRMED against a capture): holding the use key repeats the use
 * every 4 ticks ({@code rightClickDelay = 4}); separate presses bypass that delay, one use per press.
 * The 4-tick delay exists only on the client, so throwables have no server-side limit (ender pearls
 * and wind charges have their own server cooldown). Eating, drinking and bow charging are ticked by
 * the server itself and cannot be sped up by packets; they are not judged here. One press may send one
 * use packet per hand (main hand first, the off hand when the main hand passes), so a tick counts the
 * larger of its main-hand and off-hand counts.
 *
 * <p>Signals (tick-end clients only, time in client ticks so lag bursts keep their spacing):
 * <ul>
 *   <li><b>streak</b> — a use in {@code max-streak} consecutive ticks. Even butterfly clicking at
 *       25 CPS leaves some ticks empty within a second.</li>
 *   <li><b>burst</b> — {@code max-per-tick} uses (per hand) in one tick.</li>
 *   <li><b>rate</b> — more than {@code max-per-window} uses within {@code window-ticks} ticks.</li>
 * </ul>
 * Defaults are set above fast human clicking (butterfly / drag clicking); a "no item-use delay"
 * client produces a use every tick and trips the streak within one second. Creative/spectator are
 * exempt (WORLD category). All numbers are TUNING values.
 */
public final class FastUseCheck extends Check implements BlockCheck {

    public static final int DEFAULT_MAX_STREAK = 20;
    public static final int DEFAULT_MAX_PER_TICK = 3;
    public static final int DEFAULT_MAX_PER_WINDOW = 30;
    public static final int DEFAULT_WINDOW_TICKS = 20;
    public static final int MAX_WINDOW_TICKS = 100;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("fastuse", CheckCategory.WORLD)
            .displayName("FastUse")
            .description("Item uses faster than a vanilla client can send them")
            .defaults(CheckDefaults.of(CheckCategory.WORLD)
                    .withBufferLimit(3.0)
                    .withBufferDecay(0.1)
                    .withBufferMax(8.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.GAMEMODE_CHANGE)
            .build();

    private int maxStreak = DEFAULT_MAX_STREAK;
    private int maxPerTick = DEFAULT_MAX_PER_TICK;
    private int maxPerWindow = DEFAULT_MAX_PER_WINDOW;
    private int windowTicks = DEFAULT_WINDOW_TICKS;

    private final int[] ring = new int[MAX_WINDOW_TICKS];
    private int windowTotal;
    private long currentTick = Long.MIN_VALUE;
    private int mainCount;
    private int offCount;
    private int countedThisTick;
    private long lastUseTick = Long.MIN_VALUE;
    private int streak;

    public FastUseCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        maxStreak = atLeast(options, "max-streak", DEFAULT_MAX_STREAK, 5);
        maxPerTick = atLeast(options, "max-per-tick", DEFAULT_MAX_PER_TICK, 2);
        maxPerWindow = atLeast(options, "max-per-window", DEFAULT_MAX_PER_WINDOW, 5);
        windowTicks = Math.min(atLeast(options, "window-ticks", DEFAULT_WINDOW_TICKS, 5), MAX_WINDOW_TICKS);
        clearWindow();
    }

    private static int atLeast(CheckOptions options, String key, int fallback, int minimum) {
        int value = options.getInt(key, fallback);
        return value >= minimum ? value : fallback;
    }

    @Override
    protected void onReset() {
        clearWindow();
        streak = 0;
        lastUseTick = Long.MIN_VALUE;
    }

    private void clearWindow() {
        Arrays.fill(ring, 0);
        windowTotal = 0;
        currentTick = Long.MIN_VALUE;
        mainCount = 0;
        offCount = 0;
        countedThisTick = 0;
    }

    @Override
    public void onBlockInteraction(BlockInteraction interaction) {
        if (interaction.kind() != BlockInteraction.Kind.USE_ITEM || !player.tickEndMode() || isExempt()) {
            return;
        }
        onUse(player.clientTicks(), interaction.hand() == InteractionHand.OFF_HAND);
    }

    /** Decision logic on primitives (tests). */
    void onUse(long tick, boolean offHand) {
        advance(tick);
        if (offHand) {
            offCount++;
        } else {
            mainCount++;
        }
        int count = Math.max(mainCount, offCount);
        if (count > countedThisTick) {
            // a new press in this tick (not just the off-hand fallback of the same press)
            countedThisTick = count;
            windowTotal++;
            ring[(int) Math.floorMod(tick, (long) windowTicks)] = count;
            if (count == 1) {
                streak = lastUseTick != Long.MIN_VALUE && lastUseTick == tick - 1 ? streak + 1 : 1;
                lastUseTick = tick;
            }
        } else {
            return;
        }
        String signal = null;
        if (streak >= maxStreak) {
            signal = "streak";
        } else if (count >= maxPerTick) {
            signal = "burst";
        } else if (windowTotal > maxPerWindow) {
            signal = "rate";
        }
        if (signal == null) {
            lowerBuffer();
            return;
        }
        if (debugging()) {
            debug("signal=" + signal + " streak=" + streak + " inTick=" + count + " window=" + windowTotal
                    + " buffer=" + VisibilityOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation("signal=" + signal + " streak=" + streak + " perTick=" + count
                    + " window=" + windowTotal + "/" + windowTicks + "t");
            resetBuffer();
            streak = 0;
        }
    }

    private void advance(long tick) {
        if (tick == currentTick) {
            return;
        }
        long gap = currentTick == Long.MIN_VALUE ? Long.MAX_VALUE : tick - currentTick;
        if (gap < 0 || gap >= windowTicks) {
            Arrays.fill(ring, 0);
            windowTotal = 0;
            if (gap < 0) {
                streak = 0;
                lastUseTick = Long.MIN_VALUE;
            }
        } else {
            for (long t = currentTick + 1; t <= tick; t++) {
                int slot = (int) Math.floorMod(t, (long) windowTicks);
                windowTotal -= ring[slot];
                ring[slot] = 0;
            }
        }
        currentTick = tick;
        mainCount = 0;
        offCount = 0;
        countedThisTick = 0;
    }
}
