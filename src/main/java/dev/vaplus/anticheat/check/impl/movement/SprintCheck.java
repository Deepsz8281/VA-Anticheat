package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.PoseState;

/**
 * Sprint (omni-sprint, "Rage" sprint): sprinting without holding forward (1.21.2+ clients).
 *
 * <p>Vanilla ({@code LocalPlayer#aiStep}) only starts sprinting with forward input and stops at once
 * when the forward impulse is gone ({@code !input.hasForwardImpulse()}); the stop is sent as
 * {@code STOP_SPRINTING} in the same tick. The server knows both halves: the sprint state from the
 * entity-action packets and the raw movement keys from the 1.21.2+ player-input packet. Sprinting
 * for {@code min-ticks} consecutive client ticks while forward is not the net input (backwards,
 * sideways only, or forward+back) is impossible for a vanilla client.
 *
 * <p>Not judged: clients without the input packet, swimming (separate sprint rules), gliding,
 * vehicles, abilities flight and the usual join/respawn/teleport brackets.
 */
public final class SprintCheck extends Check implements ClientTickCheck {

    public static final int DEFAULT_MIN_TICKS = 3;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("sprint", CheckCategory.MOVEMENT)
            .displayName("Sprint")
            .description("Sprinting without forward input (omni / rage sprint)")
            .defaults(CheckDefaults.of(CheckCategory.MOVEMENT)
                    .withBufferLimit(2.0)
                    .withBufferDecay(0.05)
                    .withBufferMax(6.0)
                    .withSetbackVl(-1.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.VEHICLE, ExemptionCause.GAMEMODE_CHANGE)
            .build();

    private int minTicks = DEFAULT_MIN_TICKS;
    private int streak;

    public SprintCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        int ticks = settings.options().getInt("min-ticks", DEFAULT_MIN_TICKS);
        minTicks = ticks >= 2 && ticks <= 100 ? ticks : DEFAULT_MIN_TICKS;
    }

    @Override
    protected void onReset() {
        streak = 0;
    }

    @Override
    public void onClientTick() {
        PoseState pose = player.pose();
        if (!player.tickEndMode() || !pose.inputKnown() || isExempt() || player.vehicle().inVehicle()
                || pose.swimming() || pose.gliding() || player.abilities().flying()) {
            streak = 0;
            return;
        }
        if (!pose.sprinting()) {
            streak = 0;
            return;
        }
        if (pose.forwardImpulse() > 0) {
            streak = 0;
            lowerBuffer();
            return;
        }
        if (++streak < minTicks) {
            return;
        }
        String details = "sprinting " + streak + " ticks without forward input"
                + (pose.backward() ? " (backward)" : pose.left() || pose.right() ? " (sideways)" : " (no keys)");
        streak = 0;
        if (debugging()) {
            debug(details + " buffer=" + buffer());
        }
        if (raiseBuffer(1.0)) {
            violation(details);
            resetBuffer();
        }
    }
}
