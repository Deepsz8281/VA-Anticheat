package dev.vaplus.anticheat.check.impl.inventory;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.OutboundPacketCheck;
import dev.vaplus.anticheat.check.PacketCheck;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;

import java.util.List;
import java.util.Locale;

/**
 * ChestStealer: emptying a container by shift-click faster and more evenly than a human can.
 *
 * <p>The check follows the one container window the player has open (outbound open/close window,
 * inbound close window; window 0 is the player inventory and never judged). A window only becomes
 * judgeable once the client <em>provably</em> has it: the second marker of an ack bracket around
 * the open packet was acknowledged. Everything is counted in <em>client ticks</em>, which keeps lag
 * bursts harmless: a delayed burst still carries the client's own tick packets between the clicks.
 *
 * <p>Three independent signals, all on {@code QUICK_MOVE} (shift-click) only:
 * <ol>
 *   <li><b>open delay</b>: first shift-click fewer than {@code min-open-ticks} client ticks after
 *       the acknowledgement. The ack is sent when the client processes the open, so the measured
 *       delay is never longer than what the player really had to look at the GUI.</li>
 *   <li><b>burst</b>: at least {@code max-moves-per-tick} shift-clicks within one tick for
 *       {@code burst-ticks} consecutive ticks.</li>
 *   <li><b>cadence</b>: over {@code min-samples} successive shift-click intervals the mean is at or
 *       below {@code max-mean-interval-ticks} while the standard deviation is at or below
 *       {@code max-stddev-ticks} (humans jitter; a timed loop does not). Taking slots in strictly
 *       ascending order on top of that weighs heavier.</li>
 * </ol>
 * Not counted on purpose: PICKUP_ALL (double-click collect), QUICK_CRAFT drags, SWAP (hotbar keys)
 * and PICKUP; one packet there moves many items or is not the stealer pattern. Shift-clicks in the
 * player-inventory half of a container window count too: the container size is not tracked
 * (UNCONFIRMED how to read it portably across versions).
 */
public final class ChestStealerCheck extends Check implements PacketCheck, OutboundPacketCheck {

    public static final int DEFAULT_MIN_OPEN_TICKS = 2;
    public static final int DEFAULT_MAX_MOVES_PER_TICK = 3;
    public static final int DEFAULT_BURST_TICKS = 4;
    public static final int DEFAULT_MIN_SAMPLES = 10;
    public static final double DEFAULT_MAX_MEAN_INTERVAL_TICKS = 3.0;
    public static final double DEFAULT_MAX_STDDEV_TICKS = 0.55;
    public static final int DEFAULT_MAX_GAP_TICKS = 10;
    public static final double DEFAULT_SEQUENTIAL_RATIO = 0.8;

    static final int SAMPLES = 16;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("cheststealer", CheckCategory.INVENTORY)
            .displayName("ChestStealer")
            .description("Empties containers inhumanly fast or evenly")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.INVENTORY)
                    .withBufferLimit(3.0)
                    .withBufferDecay(0.25)
                    .withBufferMax(8.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT)
            .build();

    private int minOpenTicks = DEFAULT_MIN_OPEN_TICKS;
    private int maxMovesPerTick = DEFAULT_MAX_MOVES_PER_TICK;
    private int burstTicks = DEFAULT_BURST_TICKS;
    private int minSamples = DEFAULT_MIN_SAMPLES;
    private double maxMeanInterval = DEFAULT_MAX_MEAN_INTERVAL_TICKS;
    private double maxStddev = DEFAULT_MAX_STDDEV_TICKS;
    private int maxGapTicks = DEFAULT_MAX_GAP_TICKS;
    private double sequentialRatio = DEFAULT_SEQUENTIAL_RATIO;

    // Current container window (-1 = none). Owner thread only.
    private int openId = -1;
    private int generation;
    private boolean acked;
    private long ackTick;
    private boolean openJudged;

    // Shift-click run inside the window.
    private final int[] intervals = new int[SAMPLES];
    private int intervalCount;
    private int intervalIndex;
    private int sequentialMoves;
    private long lastMoveTick = -1L;
    private int lastSlot = Integer.MIN_VALUE;

    // Per-tick burst.
    private long burstTick = -1L;
    private int burstCount;
    private long lastQualifiedTick = -2L;
    private int burstStreak;

    public ChestStealerCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        minOpenTicks = intIn(options, "min-open-ticks", DEFAULT_MIN_OPEN_TICKS, 0, 40);
        maxMovesPerTick = intIn(options, "max-moves-per-tick", DEFAULT_MAX_MOVES_PER_TICK, 2, 100);
        burstTicks = intIn(options, "burst-ticks", DEFAULT_BURST_TICKS, 2, 100);
        minSamples = intIn(options, "min-samples", DEFAULT_MIN_SAMPLES, 4, SAMPLES);
        maxMeanInterval = doubleIn(options, "max-mean-interval-ticks", DEFAULT_MAX_MEAN_INTERVAL_TICKS, 0.0, 40.0);
        maxStddev = doubleIn(options, "max-stddev-ticks", DEFAULT_MAX_STDDEV_TICKS, 0.0, 10.0);
        maxGapTicks = intIn(options, "max-gap-ticks", DEFAULT_MAX_GAP_TICKS, 2, 200);
        sequentialRatio = doubleIn(options, "sequential-ratio", DEFAULT_SEQUENTIAL_RATIO, 0.5, 1.0);
    }

    private static int intIn(CheckOptions options, String key, int fallback, int min, int max) {
        int value = options.getInt(key, fallback);
        return value < min || value > max ? fallback : value;
    }

    private static double doubleIn(CheckOptions options, String key, double fallback, double min, double max) {
        double value = options.getDouble(key, fallback);
        return !Double.isFinite(value) || value < min || value > max ? fallback : value;
    }

    @Override
    protected void onReset() {
        closeWindow();
    }

    // --- packets -----------------------------------------------------------------------------

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.OPEN_WINDOW) {
            onOpened(new WrapperPlayServerOpenWindow(event).getContainerId(), event.getTasksAfterSend());
        } else if (event.getPacketType() == PacketType.Play.Server.CLOSE_WINDOW) {
            onClosed(new WrapperPlayServerCloseWindow(event).getWindowId());
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.CLICK_WINDOW) {
            if (!acked) {
                return;
            }
            WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);
            if (click.getWindowClickType() == WrapperPlayClientClickWindow.WindowClickType.QUICK_MOVE) {
                onQuickMove(click.getWindowId(), click.getSlot());
            }
        } else if (event.getPacketType() == PacketType.Play.Client.CLOSE_WINDOW) {
            onClosed(new WrapperPlayClientCloseWindow(event).getWindowId());
        }
    }

    // --- state machine (package-private for tests) -----------------------------------------

    /** A container window is being sent; judgeable once the client acknowledged it. */
    void onOpened(int windowId, List<Runnable> tasksAfterSend) {
        closeWindow();
        if (windowId <= 0) {
            return;
        }
        openId = windowId;
        int mine = generation;
        player.acks().bracket(tasksAfterSend, null, () -> {
            if (generation == mine && openId == windowId) {
                acked = true;
                ackTick = player.clientTicks();
            }
        });
    }

    void onClosed(int windowId) {
        if (windowId == openId) {
            closeWindow();
        }
    }

    private void closeWindow() {
        openId = -1;
        generation++;
        acked = false;
        openJudged = false;
        resetRun();
        burstTick = -1L;
        burstCount = 0;
        lastQualifiedTick = -2L;
        burstStreak = 0;
    }

    private void resetRun() {
        intervalCount = 0;
        intervalIndex = 0;
        sequentialMoves = 0;
        lastMoveTick = -1L;
        lastSlot = Integer.MIN_VALUE;
    }

    /** A shift-click in {@code windowId} on {@code slot}. Unknown or unacknowledged windows are not judged. */
    void onQuickMove(int windowId, int slot) {
        if (!acked || windowId != openId || slot < 0 || isExempt()) {
            return;
        }
        long tick = player.clientTicks();
        boolean suspicious = false;

        if (!openJudged) {
            openJudged = true;
            long delay = tick - ackTick;
            if (delay < minOpenTicks) {
                suspicious = true;
                if (raiseBuffer(1.0)) {
                    violation("first shift-click " + delay + " ticks after the container opened"
                            + " (min " + minOpenTicks + ")");
                    resetBuffer();
                }
            }
        }

        if (burst(tick)) {
            suspicious = true;
            if (raiseBuffer(1.5)) {
                violation(">=" + maxMovesPerTick + " shift-clicks per tick for " + burstTicks + " ticks");
                resetBuffer();
            }
            burstStreak = 0;
        }

        if (cadence(tick, slot)) {
            suspicious = true;
        }
        if (!suspicious && intervalCount >= minSamples) {
            lowerBuffer();
        }
    }

    /** Tracks shift-clicks per tick; true when the streak of busy ticks is long enough. */
    private boolean burst(long tick) {
        if (tick != burstTick) {
            if (burstTick >= 0L) {
                if (burstCount >= maxMovesPerTick) {
                    burstStreak = burstTick == lastQualifiedTick + 1 ? burstStreak + 1 : 1;
                    lastQualifiedTick = burstTick;
                } else {
                    burstStreak = 0;
                }
            }
            burstTick = tick;
            burstCount = 0;
        }
        burstCount++;
        return burstStreak >= burstTicks;
    }

    /** Interval statistics in ticks; true when the pattern matched (violation weight added). */
    private boolean cadence(long tick, int slot) {
        long previous = lastMoveTick;
        int previousSlot = lastSlot;
        lastMoveTick = tick;
        lastSlot = slot;
        if (previous < 0L) {
            return false;
        }
        long gap = tick - previous;
        if (gap < 0L || gap > maxGapTicks) {
            resetRun();
            lastMoveTick = tick;
            lastSlot = slot;
            return false;
        }
        intervals[intervalIndex] = (int) gap;
        intervalIndex = (intervalIndex + 1) % SAMPLES;
        if (intervalCount < SAMPLES) {
            intervalCount++;
        }
        if (slot == previousSlot + 1) {
            sequentialMoves = Math.min(sequentialMoves + 1, SAMPLES);
        } else if (sequentialMoves > 0) {
            sequentialMoves--;
        }
        if (intervalCount < minSamples) {
            return false;
        }
        double mean = 0.0;
        int span = 0;
        for (int i = 0; i < intervalCount; i++) {
            mean += intervals[i];
            span += intervals[i];
        }
        mean /= intervalCount;
        double variance = 0.0;
        for (int i = 0; i < intervalCount; i++) {
            double d = intervals[i] - mean;
            variance += d * d;
        }
        double stddev = Math.sqrt(variance / intervalCount);
        if (debugging()) {
            debug("moves interval mean=" + String.format(Locale.ROOT, "%.2f", mean)
                    + " std=" + String.format(Locale.ROOT, "%.2f", stddev)
                    + " seq=" + sequentialMoves + "/" + intervalCount + " buffer=" + buffer());
        }
        // span >= 2: clicks all inside one tick (stalled tick source) are the burst signal's business.
        if (span < 2 || mean > maxMeanInterval || stddev > maxStddev) {
            return false;
        }
        boolean sequential = sequentialMoves >= sequentialRatio * intervalCount;
        if (raiseBuffer(sequential ? 1.5 : 1.0)) {
            violation("regular shift-clicks mean=" + String.format(Locale.ROOT, "%.2f", mean)
                    + " ticks std=" + String.format(Locale.ROOT, "%.2f", stddev)
                    + " samples=" + intervalCount + (sequential ? " sequential-slots" : ""));
            resetBuffer();
            resetRun();
        }
        return true;
    }

    /** Whether the player has an acknowledged container window open (tests). */
    boolean judging() {
        return openId >= 0 && acked;
    }
}
