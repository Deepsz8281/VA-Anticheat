package dev.vaplus.anticheat.check.impl.world;

import com.github.retrooper.packetevents.protocol.world.BlockFace;
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
 * FastPlace: block placements faster than a vanilla client can click.
 *
 * <p>Vanilla facts (client code, UNCONFIRMED against a capture): holding right-click re-uses the item
 * every 4 ticks ({@code rightClickDelay = 4}); separate presses bypass that delay, so spam clicking
 * can place faster, but a human is limited to roughly 15-20 clicks per second and the client runs
 * at most one placement per click. Within one {@code startUseItem} the main hand is tried first and
 * the off hand only when the main hand passes, so one click yields one placement; a duplicate PLACE
 * for the same block face in the same tick (the hand fallback) is therefore not counted.
 *
 * <p>Time is measured in client ticks ({@code player.clientTicks()}), never wall time, so packets of
 * several ticks arriving together after a lag spike stay in the ticks the client sent them in.
 * Legacy clients without a tick-end packet are skipped (no reliable tick source).
 *
 * <p>Three independent signals (all TUNING values, buffer weight 1 each):
 * <ul>
 *   <li><b>streak</b> - a counted placement in {@code max-streak} consecutive client ticks. Holding
 *       right-click places every 4 ticks; a human spamming 15 CPS does not hit every single tick.</li>
 *   <li><b>burst</b> - {@code max-per-tick} or more counted placements in a single tick.</li>
 *   <li><b>rate</b> - more than {@code max-per-window} counted placements within the last
 *       {@code window-ticks} ticks (default 20, i.e. one second).</li>
 * </ul>
 * A placement counts only when its (block, face) differs from the previously counted one; repeated
 * clicks on an unchanged target are failed or non-placing uses (chests, buttons, blocked spots) and
 * are ignored. The server outcome is unknown at packet level, so this errs on the side of misses.
 * Creative and spectator players are exempt through the WORLD category exemption.
 */
public final class FastPlaceCheck extends Check implements BlockCheck {

    public static final int DEFAULT_MAX_STREAK = 12;
    public static final int DEFAULT_MAX_PER_TICK = 3;
    public static final int DEFAULT_MAX_PER_WINDOW = 20;
    public static final int DEFAULT_WINDOW_TICKS = 20;
    /** Hard upper bound of the sliding window (ring size). */
    public static final int MAX_WINDOW_TICKS = 100;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("fastplace", CheckCategory.WORLD)
            .displayName("FastPlace")
            .description("Places blocks faster than a vanilla client can click")
            .defaults(CheckDefaults.of(CheckCategory.WORLD)
                    .withBufferLimit(3.0)
                    .withBufferDecay(0.1)
                    .withBufferMax(8.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ABILITY_CHANGE,
                    ExemptionCause.VEHICLE)
            .build();

    private int maxStreak = DEFAULT_MAX_STREAK;
    private int maxPerTick = DEFAULT_MAX_PER_TICK;
    private int maxPerWindow = DEFAULT_MAX_PER_WINDOW;
    private int windowTicks = DEFAULT_WINDOW_TICKS;

    /** Placements per tick, indexed by {@code tick % windowTicks}. */
    private final int[] ring = new int[MAX_WINDOW_TICKS];
    private int windowTotal;
    private long currentTick = -1L;
    private int currentCount;
    private long lastPlaceTick = Long.MIN_VALUE;
    private int streak;
    private boolean haveLast;
    private int lastX;
    private int lastY;
    private int lastZ;
    private BlockFace lastFace = BlockFace.OTHER;

    public FastPlaceCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        maxStreak = atLeast(options, "max-streak", DEFAULT_MAX_STREAK, 2);
        maxPerTick = atLeast(options, "max-per-tick", DEFAULT_MAX_PER_TICK, 2);
        maxPerWindow = atLeast(options, "max-per-window", DEFAULT_MAX_PER_WINDOW, 1);
        windowTicks = Math.min(atLeast(options, "window-ticks", DEFAULT_WINDOW_TICKS, 1), MAX_WINDOW_TICKS);
        clearWindow();
    }

    @Override
    protected void onReset() {
        clearWindow();
        streak = 0;
        lastPlaceTick = Long.MIN_VALUE;
        haveLast = false;
    }

    private void clearWindow() {
        Arrays.fill(ring, 0);
        windowTotal = 0;
        currentTick = -1L;
        currentCount = 0;
    }

    private static int atLeast(CheckOptions options, String key, int fallback, int minimum) {
        int value = options.getInt(key, fallback);
        return value >= minimum ? value : fallback;
    }

    @Override
    public void onBlockInteraction(BlockInteraction interaction) {
        if (interaction.kind() != BlockInteraction.Kind.PLACE) {
            return;
        }
        if (!player.tickEndMode() || isExempt()) {
            // Legacy clients have no reliable tick source; exempt players must not build state either.
            return;
        }
        int x = interaction.blockX();
        int y = interaction.blockY();
        int z = interaction.blockZ();
        BlockFace face = interaction.face();
        if (haveLast && x == lastX && y == lastY && z == lastZ && face == lastFace) {
            return; // hand fallback / repeated click on an unchanged target: not a new placement
        }
        haveLast = true;
        lastX = x;
        lastY = y;
        lastZ = z;
        lastFace = face;

        long tick = player.clientTicks();
        advance(tick);
        if (currentCount == 0) {
            streak = lastPlaceTick == tick - 1 ? streak + 1 : 1;
            lastPlaceTick = tick;
        }
        currentCount++;
        ring[(int) (tick % windowTicks)] = currentCount;
        windowTotal++;

        String signal = null;
        if (streak >= maxStreak) {
            signal = "streak";
        } else if (currentCount >= maxPerTick) {
            signal = "burst";
        } else if (windowTotal > maxPerWindow) {
            signal = "rate";
        }
        if (signal == null) {
            lowerBuffer();
            return;
        }
        if (debugging()) {
            debug("signal=" + signal + " streak=" + streak + " inTick=" + currentCount
                    + " window=" + windowTotal + " buffer=" + buffer());
        }
        if (raiseBuffer(1.0)) {
            violation("signal=" + signal + " streak=" + streak + " perTick=" + currentCount
                    + " window=" + windowTotal + "/" + windowTicks + "t");
            resetBuffer();
            streak = 0;
        }
    }

    /** Moves the window to {@code tick}, expiring the slots that fell out of it. */
    private void advance(long tick) {
        if (tick == currentTick) {
            return;
        }
        long gap = tick - currentTick;
        if (currentTick < 0 || gap < 0 || gap >= windowTicks) {
            Arrays.fill(ring, 0);
            windowTotal = 0;
            if (gap < 0) {
                streak = 0;
                lastPlaceTick = Long.MIN_VALUE;
            }
        } else {
            for (long t = currentTick + 1; t <= tick; t++) {
                int slot = (int) (t % windowTicks);
                windowTotal -= ring[slot];
                ring[slot] = 0;
            }
        }
        currentTick = tick;
        currentCount = 0;
    }
}
