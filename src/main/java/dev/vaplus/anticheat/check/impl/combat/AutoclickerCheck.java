package dev.vaplus.anticheat.check.impl.combat;

import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.CombatCheck;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;

/**
 * Autoclicker: constant high click rates.
 *
 * <p>An autoclicker produces intervals that are both <em>fast</em> and <em>almost identical</em>
 * (the scheduler wakes up with sub-millisecond jitter). Humans are noisy: even drag/butterfly
 * clicking that reaches 15-20 CPS has a visible spread. The check therefore needs both conditions
 * at once - mean CPS above {@code max-cps} and a standard deviation below
 * {@code min-stddev-millis} - over at least {@code min-samples} attacks.
 *
 * <p>Marked experimental: hardware macros and high-end butterfly clicking are indistinguishable
 * from a good autoclicker at the packet level. Keep the buffer high on competitive servers.
 */
public final class AutoclickerCheck extends Check implements CombatCheck {

    public static final int SAMPLES = 20;
    public static final double DEFAULT_MAX_CPS = 16.0;
    public static final double DEFAULT_MIN_STDDEV_MILLIS = 2.0;
    public static final int DEFAULT_MIN_SAMPLES = 12;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("autoclicker", CheckCategory.COMBAT)
            .displayName("Autoclicker")
            .description("Clicks at an inhuman constant rate")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.COMBAT)
                    .withBufferLimit(3.0)
                    .withBufferDecay(0.1)
                    .withBufferMax(8.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT)
            .build();

    private final long[] intervals = new long[SAMPLES];
    private int intervalIndex;
    private int intervalFilled;
    private long lastAttackNanos;

    private double maxCps = DEFAULT_MAX_CPS;
    private double minStddevMillis = DEFAULT_MIN_STDDEV_MILLIS;
    private int minSamples = DEFAULT_MIN_SAMPLES;

    public AutoclickerCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        maxCps = CombatOptions.positive(options, "max-cps", DEFAULT_MAX_CPS);
        minStddevMillis = CombatOptions.tolerance(options, "min-stddev-millis", DEFAULT_MIN_STDDEV_MILLIS);
        minSamples = Math.min(SAMPLES, CombatOptions.atLeast(options, "min-samples", DEFAULT_MIN_SAMPLES, 2));
    }

    @Override
    protected void onReset() {
        intervalFilled = 0;
        intervalIndex = 0;
        lastAttackNanos = 0L;
    }

    @Override
    public void onAttack(EntityAttack attack) {
        if (!attack.attacked()) {
            return;
        }
        long now = attack.timestampNanos();
        long previous = lastAttackNanos;
        lastAttackNanos = now;
        if (previous == 0L || now <= previous) {
            return;
        }
        intervals[intervalIndex] = now - previous;
        intervalIndex = (intervalIndex + 1) % SAMPLES;
        if (intervalFilled < SAMPLES) {
            intervalFilled++;
        }
        if (isExempt() || intervalFilled < minSamples) {
            return;
        }
        double mean = 0.0;
        for (int i = 0; i < intervalFilled; i++) {
            mean += intervals[i];
        }
        mean /= intervalFilled;
        if (mean <= 0.0) {
            return;
        }
        double variance = 0.0;
        for (int i = 0; i < intervalFilled; i++) {
            double difference = intervals[i] - mean;
            variance += difference * difference;
        }
        double stddevMillis = Math.sqrt(variance / intervalFilled) / 1_000_000.0;
        double cps = 1_000_000_000.0 / mean;
        if (debugging()) {
            debug("cps=" + CombatOptions.number(cps) + " stddev=" + CombatOptions.number(stddevMillis)
                    + "ms samples=" + intervalFilled + " buffer=" + CombatOptions.number(buffer()));
        }
        if (cps > maxCps && stddevMillis < minStddevMillis) {
            if (raiseBuffer(1.0)) {
                violation("cps=" + CombatOptions.number(cps)
                        + " stddev=" + CombatOptions.number(stddevMillis) + "ms"
                        + " samples=" + intervalFilled
                        + " buffer=" + CombatOptions.number(buffer()));
                resetBuffer();
                intervalFilled = 0;
                intervalIndex = 0;
            }
        } else {
            lowerBuffer();
        }
    }
}
