package dev.vaplus.anticheat.check.impl.movement;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.OutboundPacketCheck;
import dev.vaplus.anticheat.check.PacketCheck;
import dev.vaplus.anticheat.check.PositionCheck;
import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.simulation.SimulationResult;

import java.util.List;

/**
 * NoSlow: moving at full speed while the server knows the player is using an item (eating,
 * drinking, drawing a bow, blocking, ...).
 *
 * <p><b>Proof of item use.</b> The server never has to guess the held item: vanilla synchronises the
 * "hand active" bit (bit 0 of the living-entity flags, entity-data index 8) of the player's own
 * entity to the client, and the local player's slowdown ({@code input * 0.2}) is driven by exactly
 * that flag. The check therefore tracks that bit from the outbound ENTITY_METADATA of the player's
 * own entity. The flag only counts as <em>confirmed</em> once the client acknowledged a marker sent
 * after the packet; it is dropped at once when the server clears it, when the client sends
 * RELEASE_USE_ITEM or changes its held slot. Unknown, unconfirmed or stale state means "do not judge".
 *
 * <p><b>Verdict.</b> While confirmed, a COMPLETE single-tick simulation on the ground is judged
 * after {@code grace-ticks}: the engine enumerates both the slowed and the unslowed input
 * candidates, so the check only reads {@link SimulationResult#candidateIndex()}. A packet counts when
 * the winning candidate has movement input, <em>no</em> item-use slowdown, no server velocity and
 * no jump, fits the reported movement ({@code threshold} plus uncertainty) and the player really
 * moved at least {@code min-speed} blocks. A judged packet that is explained by the slowed (or by
 * no) input pays the buffer back. Skipped simulations (unknown or special blocks, liquids, pending
 * attribute/effect/world changes, vehicles, flight, gliding) are never judged.
 *
 * <p>Sprinting while using an item is covered by the same rule: the engine only has an unslowed
 * candidate for it. UNCONFIRMED: the vanilla index/bit of the flag on every supported version
 * (verified by reading, not by capture) and that the own-entity metadata is always sent to the
 * player; if it is not, the check simply never judges. Tuning values are estimates.
 */
public final class NoSlowCheck extends Check implements PositionCheck, PacketCheck, OutboundPacketCheck {

    /** Entity-data index of the living-entity flags byte (UNCONFIRMED on 26.x). */
    public static final int LIVING_FLAGS_INDEX = 8;
    /** Bit 0 of the living-entity flags: a hand is active (using an item). */
    public static final int USING_ITEM_BIT = 0x01;

    /** TUNING: client ticks after the confirmed start before movement is judged (momentum fades). */
    public static final int DEFAULT_GRACE_TICKS = 8;
    /** TUNING: horizontal blocks per tick; above the slowed maximum (about 0.06 with Speed II). */
    public static final double DEFAULT_MIN_SPEED = 0.12;
    /** TUNING: residual (blocks) the unslowed candidate may have; plus engine uncertainty. */
    public static final double DEFAULT_THRESHOLD = 0.02;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("noslow", CheckCategory.MOVEMENT)
            .displayName("NoSlow")
            .description("Full movement speed while using an item")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.MOVEMENT)
                    .withBufferLimit(4.0)
                    .withBufferDecay(0.5)
                    .withBufferMax(10.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ABILITY_CHANGE,
                    ExemptionCause.ATTRIBUTE_CHANGE, ExemptionCause.VEHICLE, ExemptionCause.KNOCKBACK,
                    ExemptionCause.EXPLOSION, ExemptionCause.PORTAL, ExemptionCause.ELYTRA_BOOST,
                    ExemptionCause.WIND_CHARGE, ExemptionCause.RIPTIDE, ExemptionCause.BOUNCE,
                    ExemptionCause.LUNGE)
            .build();

    private int graceTicks = DEFAULT_GRACE_TICKS;
    private double minSpeed = DEFAULT_MIN_SPEED;
    private double threshold = DEFAULT_THRESHOLD;

    /** Generation counter: invalidates acknowledgement tasks of superseded flag changes. */
    private int epoch;
    private boolean confirmed;
    private int usingTicks;

    public NoSlowCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        graceTicks = Math.max(1, Math.min(200, options.getInt("grace-ticks", DEFAULT_GRACE_TICKS)));
        double speed = options.getDouble("min-speed", DEFAULT_MIN_SPEED);
        minSpeed = Double.isFinite(speed) && speed >= 0.05 && speed <= 2.0 ? speed : DEFAULT_MIN_SPEED;
        double tolerance = options.getDouble("threshold", DEFAULT_THRESHOLD);
        threshold = Double.isFinite(tolerance) && tolerance >= 0.0 && tolerance <= 1.0 ? tolerance : DEFAULT_THRESHOLD;
    }

    @Override
    protected void onReset() {
        stop();
    }

    // --- state tracking ------------------------------------------------------------------------

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
            if (event.getUser() == null) {
                return;
            }
            int own = event.getUser().getEntityId();
            WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(event);
            if (own <= 0 || packet.getEntityId() != own) {
                return;
            }
            for (EntityData<?> data : packet.getEntityMetadata()) {
                if (data.getIndex() == LIVING_FLAGS_INDEX && data.getValue() instanceof Number value) {
                    onUsingFlag((value.intValue() & USING_ITEM_BIT) != 0, event.getTasksAfterSend());
                }
            }
        } else if (event.getPacketType() == PacketType.Play.Server.RESPAWN
                || event.getPacketType() == PacketType.Play.Server.JOIN_GAME) {
            stop();
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!confirmed) {
            return;
        }
        if (event.getPacketType() == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            stop();
        } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING
                && new WrapperPlayClientPlayerDigging(event).getAction() == DiggingAction.RELEASE_USE_ITEM) {
            stop();
        }
    }

    /**
     * Server told the client whether a hand is active. Clearing is immediate (the client may still be
     * slowed for a moment, which only means "not judged"); setting is confirmed after the client
     * acknowledged a marker sent behind the packet.
     */
    void onUsingFlag(boolean using, List<Runnable> tasksAfterSend) {
        if (!using) {
            stop();
            return;
        }
        int generation = ++epoch;
        player.acks().bracket(tasksAfterSend, null, () -> {
            if (generation == epoch) {
                confirmed = true;
                usingTicks = 0;
            }
        });
    }

    private void stop() {
        epoch++;
        confirmed = false;
        usingTicks = 0;
    }

    // --- judging -------------------------------------------------------------------------------

    @Override
    public void onPosition(MovementUpdate update) {
        if (!confirmed) {
            return;
        }
        usingTicks += Math.max(1, update.elapsedTicks());
        SimulationResult result = update.simulation();
        if (!result.complete() || result.ticksSimulated() != 1 || usingTicks <= graceTicks || isExempt()
                || update.teleport() || update.firstPosition()
                || !update.fromGround() || !result.predictedOnGround()
                || player.pose().gliding() || player.pose().swimming() || player.vehicle().inVehicle()
                || player.abilities().flying()) {
            return;
        }
        // Candidate encoding: see PredictionSimulator#candidateIndex.
        int index = result.candidateIndex();
        int rest = index / 9;
        int forward = (index % 9) / 3 - 1;
        int strafe = index % 3 - 1;
        int itemUse = rest & 1;
        int jump = (rest >> 2) & 1;
        int velocities = rest >> 3;
        if (velocities != 0 || jump != 0) {
            return;
        }
        double limit = threshold + result.uncertainty();
        if (result.horizontalOffset() > limit) {
            return;
        }
        if (itemUse == 1 || (forward == 0 && strafe == 0)) {
            lowerBuffer();
            return;
        }
        double moved = update.horizontalDistance();
        if (moved < minSpeed) {
            return;
        }
        if (debugging()) {
            debug("full speed while using: moved=" + MovementOptions.number(moved) + " buffer="
                    + MovementOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation("moved=" + MovementOptions.number(moved)
                    + " using-ticks=" + usingTicks
                    + " offset=" + MovementOptions.number(result.horizontalOffset())
                    + " uncertainty=" + MovementOptions.number(result.uncertainty()));
        }
    }

    /** Whether the item-use flag is currently confirmed (tests, diagnostics). */
    public boolean usingConfirmed() {
        return confirmed;
    }
}
