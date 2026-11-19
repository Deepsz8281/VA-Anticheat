package dev.vaplus.anticheat.violation;

import dev.vaplus.anticheat.AnticheatContext;
import dev.vaplus.anticheat.alert.AlertSink;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.config.GlobalSettings;
import dev.vaplus.anticheat.movement.SetbackHandler;
import dev.vaplus.anticheat.movement.SetbackHandler.SetbackTarget;
import dev.vaplus.anticheat.pipeline.TeleportQueue;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.MovementState;
import dev.vaplus.anticheat.violation.CheckMetrics;

import java.util.List;
import java.util.Objects;

/**
 * Turns a registered violation into its consequences: API event, log entry, staff alert, movement
 * setback and threshold actions. Runs on the packet thread of the flagged player.
 *
 * <p>Setbacks (Phase 2, plan item 7): when the player has a {@link TeleportQueue} (core-tracking
 * module installed) and a known safe position, the packet-level API
 * {@link SetbackHandler#request(PlayerData, SetbackTarget, String)} is used with the safe position
 * as target and the check id as reason; the handler registers the sync in the queue and the movement
 * gate waits for its confirmation. Without a queue or a safe position the legacy
 * {@link SetbackHandler#request(PlayerData, Check)} path (Bukkit {@code teleportAsync}) is kept.
 */
public final class ViolationDispatcher {

    private final FlagEventPublisher publisher;
    private final ViolationLog log;
    private final AlertSink alerts;
    private final PunishmentExecutor punishments;
    private final SetbackHandler setbacks;
    private final CheckMetrics metrics;
    private final SetbackConfirmations confirmations = new SetbackConfirmations();

    public ViolationDispatcher(FlagEventPublisher publisher, ViolationLog log, AlertSink alerts,
                              PunishmentExecutor punishments, SetbackHandler setbacks) {
        this(publisher, log, alerts, punishments, setbacks, 0);
    }

    public ViolationDispatcher(FlagEventPublisher publisher, ViolationLog log, AlertSink alerts,
                              PunishmentExecutor punishments, SetbackHandler setbacks, int checkSlots) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.log = Objects.requireNonNull(log, "log");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.punishments = Objects.requireNonNull(punishments, "punishments");
        this.setbacks = Objects.requireNonNull(setbacks, "setbacks");
        this.metrics = new CheckMetrics(checkSlots);
    }

    /** Per-check flag counters (shadow-mode tuning, {@code /ac metrics}). */
    public CheckMetrics metrics() {
        return metrics;
    }

    /** Number of (player, check) pairs with a setback confirmation history (diagnostics). */
    public int trackedSetbackConfirmations() {
        return confirmations.tracked();
    }

    /** First stage: gives API listeners the chance to cancel. */
    public boolean authorize(Check check, double newLevel, String details) {
        return publisher.publish(check.player(), check, newLevel, details);
    }

    /** Second stage, after the check committed the new level. */
    public void apply(Check check, double before, double after, String details) {
        PlayerData player = check.player();
        AnticheatContext context = player.context();
        GlobalSettings global = context.settings();
        CheckSettings settings = check.settings();

        metrics.record(check.slot(), player.uuid());

        // Shadow mode: a check with `log-only: true` in its options, or any check while the
        // performance governor is degraded, reports but never punishes.
        boolean shadow = check.logOnly() || context.governor().shadowOnly();
        log.append(new ViolationRecord(
                player.uuid(),
                player.name(),
                check.descriptor().id(),
                check.descriptor().category(),
                after,
                shadow ? "[shadow] " + details : details,
                player.acks().smoothedRttMillis(),
                player.clientVersion().getReleaseName(),
                context.time().currentTimeMillis()));

        if (ActionThreshold.reached(after, settings.alertVl())
                && check.claimAlert(context.time().nanoTime(), global.alertMinIntervalMillis() * 1_000_000L)) {
            alerts.alert(player, check, after, shadow ? "[shadow] " + details : details);
        }

        if (shadow) {
            return;
        }

        // Setback confirmation (plan item 9): a single spike no longer rubber-bands a player. The
        // check itself must have produced several violations inside the configured window; the
        // VL threshold stays in charge of which checks are allowed to set back at all.
        boolean confirmed = confirmations.confirmed(player.uuid(), check.slot(), context.time().nanoTime(),
                global.setbackWindowMillis() * 1_000_000L, global.setbackConfirmations());
        if (global.setbacksEnabled()
                && confirmed
                && check.descriptor().category().setbackCapable()
                && settings.setbackEnabled()
                && ActionThreshold.reached(after, settings.setbackVl())) {
            requestSetback(player, check);
        }

        List<ActionThreshold> thresholds = settings.actions();
        for (int i = 0; i < thresholds.size(); i++) {
            ActionThreshold threshold = thresholds.get(i);
            if (!threshold.triggers(before, after)) {
                continue;
            }
            List<ViolationAction> actions = threshold.actions();
            for (int j = 0; j < actions.size(); j++) {
                punishments.execute(actions.get(j), player, check, after);
            }
        }
    }

    private void requestSetback(PlayerData player, Check check) {
        MovementState movement = player.movement();
        if (movement.safeKnown() && TeleportQueue.resolve(player) != null) {
            // Same destination as the legacy path: last safe position, current rotation.
            SetbackTarget target = new SetbackTarget(movement.safeX(), movement.safeY(), movement.safeZ(),
                    movement.yaw(), movement.pitch());
            if (target.isFinite()) {
                setbacks.request(player, target, check.descriptor().id());
                return;
            }
        }
        setbacks.request(player, check);
    }
}
