package dev.vaplus.anticheat.check;

import dev.vaplus.anticheat.AnticheatContext;
import dev.vaplus.anticheat.player.PlayerData;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Objects;

/**
 * Base class of every detection. One instance exists per player and check type.
 *
 * <p>Threading: all hook methods ({@link PacketCheck}, {@link PositionCheck}, {@link RotationCheck},
 * {@link BlockCheck}, {@link ClientTickCheck}) are invoked on the player's netty event-loop thread,
 * so buffers and other instance state need no synchronisation. The violation level is additionally
 * published through volatile fields so commands on other threads can read it.
 *
 * <p>Hot-path pattern (no string building unless a violation actually happens):
 * <pre>{@code
 * if (offset > threshold) {
 *     if (raiseBuffer(1.0)) {
 *         violation("offset=" + offset);
 *     }
 * } else {
 *     lowerBuffer();
 * }
 * }</pre>
 */
public abstract class Check {

    private static final double NANOS_PER_MINUTE = 60_000_000_000.0;

    protected final PlayerData player;
    private final CheckDescriptor descriptor;
    private int slot = -1;
    /** Volatile because {@link #violationLevel()} reads the decay rate from command threads. */
    private volatile CheckSettings settings;
    private double buffer;

    private volatile double violationLevel;
    private volatile long violationAnchorNanos;
    private volatile int violationCount;

    private long lastAlertNanos;
    private boolean alertedBefore;

    protected Check(PlayerData player, CheckDescriptor descriptor) {
        this.player = Objects.requireNonNull(player, "player");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.settings = CheckSettings.of(descriptor.defaults(), List.of());
    }

    public final CheckDescriptor descriptor() {
        return descriptor;
    }

    public final PlayerData player() {
        return player;
    }

    /** Index of the check type in the {@link CheckRegistry}; stable for the lifetime of the plugin. */
    public final int slot() {
        return slot;
    }

    public final CheckSettings settings() {
        return settings;
    }

    public final boolean enabled() {
        return settings.enabled();
    }

    public final double buffer() {
        return buffer;
    }

    /** Number of violations registered in this session (not decayed). */
    public final int violationCount() {
        return violationCount;
    }

    /** Current violation level including time-based decay. Safe to call from any thread. */
    public final double violationLevel() {
        return decayedLevel(player.context().time().nanoTime());
    }

    /**
     * Whether this check only reports (shadow mode): alerts, log and metrics stay active, setbacks
     * and configured punishments are suppressed. The global performance governor can force this
     * for every check at once.
     */
    public final boolean logOnly() {
        return settings.options().getBoolean("log-only", false);
    }

    /** Called on the owner thread whenever (re)loaded settings are applied. Cache options here. */
    protected void onSettingsApplied(CheckSettings settings) {
    }

    /** Called when the player's state is reset (respawn, world change). Clear per-world state here. */
    protected void onReset() {
    }

    /**
     * Adds to the buffer.
     *
     * @return {@code true} when the buffer now exceeds the configured limit and a violation should
     *         be registered
     */
    protected final boolean raiseBuffer(double amount) {
        CheckSettings current = settings;
        double cap = current.bufferMax();
        double next = buffer + amount;
        buffer = cap > 0 ? Math.min(next, cap) : next;
        return buffer > current.bufferLimit();
    }

    /** Removes the configured decay from the buffer (call on legitimate behaviour). */
    protected final void lowerBuffer() {
        lowerBuffer(settings.bufferDecay());
    }

    protected final void lowerBuffer(double amount) {
        buffer = Math.max(0.0, buffer - amount);
    }

    protected final void resetBuffer() {
        buffer = 0.0;
    }

    /** Registers a violation of weight 1. */
    protected final boolean violation(String details) {
        return violation(1.0, details);
    }

    /**
     * Registers a violation: raises the violation level by {@code weight}, then runs logging, alerts,
     * setbacks and configured punishments.
     *
     * @return {@code false} if an API listener cancelled the violation
     */
    protected final boolean violation(double weight, String details) {
        if (!(weight > 0.0)) {
            throw new IllegalArgumentException("violation weight must be positive: " + weight);
        }
        AnticheatContext context = player.context();
        long now = context.time().nanoTime();
        double before = decayedLevel(now);
        double after = before + weight;
        String safeDetails = details != null ? details : "";
        if (!context.violations().authorize(this, after, safeDetails)) {
            return false;
        }
        violationLevel = after;
        violationAnchorNanos = now;
        violationCount = violationCount + 1;
        context.violations().apply(this, before, after, safeDetails);
        return true;
    }

    /**
     * Game-mode exemption of the category, or any {@link CheckDescriptor#exemptionMask() declared}
     * exemption cause currently active. Checks may add further situational conditions.
     */
    protected final boolean isExempt() {
        return player.isExempt(descriptor.category())
                || player.exemptions().anyActive(descriptor.exemptionMask());
    }

    /** Whether somebody is watching this check for this player via {@code /ac debug}. */
    protected final boolean debugging() {
        return player.context().debug().isWatching(player, descriptor);
    }

    /** Sends a debug line to watchers. Guard with {@link #debugging()} to avoid building strings. */
    protected final void debug(String message) {
        player.context().debug().send(player, descriptor, message);
    }

    /** Rate limiter for alerts, used by the violation processor. */
    @ApiStatus.Internal
    public final boolean claimAlert(long nowNanos, long minIntervalNanos) {
        if (minIntervalNanos > 0 && alertedBefore && nowNanos - lastAlertNanos < minIntervalNanos) {
            return false;
        }
        alertedBefore = true;
        lastAlertNanos = nowNanos;
        return true;
    }

    final void bind(int slot) {
        this.slot = slot;
    }

    final void applySettings(CheckSettings settings) {
        this.settings = settings;
        onSettingsApplied(settings);
    }

    final void reset() {
        buffer = 0.0;
        onReset();
    }

    private double decayedLevel(long nowNanos) {
        double level = violationLevel;
        if (level <= 0.0) {
            return 0.0;
        }
        double perMinute = settings.vlDecayPerMinute();
        if (perMinute <= 0.0) {
            return level;
        }
        double minutes = (nowNanos - violationAnchorNanos) / NANOS_PER_MINUTE;
        return Math.max(0.0, level - perMinute * minutes);
    }

    @Override
    public String toString() {
        return descriptor.id() + "[" + player.name() + "]";
    }
}
