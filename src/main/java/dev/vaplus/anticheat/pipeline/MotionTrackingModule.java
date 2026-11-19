package dev.vaplus.anticheat.pipeline;

import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.module.ModuleContext;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.MotionHistory;
import dev.vaplus.anticheat.player.state.MovementState;
import dev.vaplus.anticheat.player.state.PoseState;

/**
 * Per-client-tick motion snapshots (Handoff M12): registers the {@link MotionHistory} component and
 * a tick listener that pushes one {@link MotionHistory.TickSnapshot} per client tick. The listener
 * runs before the tick checks (see {@link ClientTickListener}), also for bypassing players.
 *
 * <p>This module only records what the Phase-1 trackers already know; the multi-tick movement
 * engine (Phase 2, engine round) owns the interpretation.
 */
public final class MotionTrackingModule implements AnticheatModule {

    private ComponentKey<MotionHistory> historyKey;

    @Override
    public String name() {
        return "motion-tracking";
    }

    @Override
    public void register(ModuleContext context) {
        historyKey = context.registerComponent("motion-history", MotionHistory.class,
                player -> new MotionHistory());
        context.addTickListener(this::onClientTick);
    }

    /** Key of the per-player {@link MotionHistory} component; usable after registration. */
    public ComponentKey<MotionHistory> historyKey() {
        if (historyKey == null) {
            throw new IllegalStateException("MotionTrackingModule is not registered yet");
        }
        return historyKey;
    }

    private void onClientTick(PlayerData player) {
        player.component(historyKey).push(snapshot(player));
    }

    /**
     * Builds the snapshot of the current tick from the player's movement and pose state. The delta
     * is the movement of the most recent position packet ({@code x - lastX}), which can span more
     * than one tick when the client sends position packets less often than every tick. The very
     * first position after join/respawn also produces a meaningless delta against the 0/0/0 origin
     * ({@link MovementState} has no previous position yet) — the engine must ignore it.
     *
     * <p>TODO(engine round): derive exact per-tick deltas from {@code elapsedTicks} and the queued
     * packets instead of the last-packet delta; also model vehicle movement (Phase 3).
     */
    static MotionHistory.TickSnapshot snapshot(PlayerData player) {
        MovementState movement = player.movement();
        PoseState pose = player.pose();
        double dx = 0.0;
        double dy = 0.0;
        double dz = 0.0;
        if (movement.positionKnown()) {
            dx = movement.x() - movement.lastX();
            dy = movement.y() - movement.lastY();
            dz = movement.z() - movement.lastZ();
        }
        // Jumping is only known from the raw input keys (1.21.2+ clients that sent a player-input
        // packet); everything else reports false, which the engine must tolerate.
        boolean jumping = pose.inputKnown() && pose.jump();
        return new MotionHistory.TickSnapshot(
                player.clientTicks(),
                player.context().time().nanoTime(),
                dx, dy, dz,
                movement.onGround(),
                pose.sprinting(),
                pose.sneaking(),
                jumping,
                pose.gliding(),
                movement.worldEpoch());
    }
}
