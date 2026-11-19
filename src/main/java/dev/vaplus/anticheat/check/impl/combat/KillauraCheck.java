package dev.vaplus.anticheat.check.impl.combat;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.check.CombatCheck;
import dev.vaplus.anticheat.check.OutboundPacketCheck;
import dev.vaplus.anticheat.check.PacketCheck;
import dev.vaplus.anticheat.check.impl.movement.NoSlowCheck;
import dev.vaplus.anticheat.check.impl.world.FreecamCheck;
import dev.vaplus.anticheat.check.impl.world.NearbyPlayers;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.MovementState;
import dev.vaplus.anticheat.player.state.PoseState;

import java.util.List;

/**
 * Killaura: attacks a vanilla client cannot send.
 *
 * <p>{@code angle}: the angle between the look direction the client itself sent and the direction
 * to the target's hitbox centre. Vanilla needs the crosshair on the hitbox (roughly ±45° for a close
 * target, less for a far one); a killaura that attacks everything in range ignores the rotation. The
 * default limit (75°) is deliberately generous: 180° mouse flicks exist, so it needs three aligned
 * samples before it flags. Rotating auras are the job of {@link AimCheck}.
 *
 * <p>{@code multi-target}: attacks on two <em>different</em> entities within one client tick.
 * Vanilla resolves the crosshair target once per tick, so every click queued in that tick hits the
 * same entity; switching targets inside a tick is the multi-aura signature. It needs the exact tick
 * boundary of 1.21.2+ clients (tick-end packet) and is skipped for older ones.
 *
 * <p>{@code item-use} (Multitask, AutoEat/AutoGap + aura, shield + aura): vanilla discards every
 * attack key press while an item is in use ({@code MinecraftClient#handleInputEvents}). The use is
 * proven by the server's own "hand active" flag (see {@link NoSlowCheck}), counted once the client
 * acknowledged it. Because eating can end on the client a moment before the server notices, the
 * attack is only judged if the server still sees the same use {@code item-use-confirm-ticks} client
 * ticks later; release, slot change or the server ending the use discard it.
 *
 * <p>{@code container} (GuiMove + aura): attacking while a server-opened container screen is open.
 * A screen swallows mouse clicks; the open only counts after the client acknowledged it, every close
 * (either direction) ends it at once. The player's own inventory sends no open packet and is not
 * judged.
 *
 * <p>{@code no-swing}: 1.21.2+ clients always send the arm swing right after the attack in the same
 * tick; an attack without swing before the tick ends is a packet-level attack (NoSwing, crystal
 * auras with swing disabled).
 *
 * <p>Targets behind the attacker are included by construction ({@code acos} is symmetric), which is
 * exactly the multi-aura signature. Unknown targets and close-range hits never judge the angle.
 */
public final class KillauraCheck extends Check
        implements CombatCheck, PacketCheck, OutboundPacketCheck, ClientTickCheck {

    public static final double DEFAULT_MAX_ANGLE = 75.0;
    public static final double DEFAULT_MAX_DISTANCE = 6.0;
    public static final double MIN_DISTANCE = 0.5;
    public static final int DEFAULT_ITEM_USE_CONFIRM_TICKS = 3;
    static final double ITEM_USE_WEIGHT = 1.5;
    static final double CONTAINER_WEIGHT = 1.5;
    static final double NO_SWING_WEIGHT = 1.0;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("killaura", CheckCategory.COMBAT)
            .displayName("Killaura")
            .description("Attacks outside the look direction, while using items, in GUIs or without swing")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.COMBAT)
                    .withBufferLimit(3.0)
                    .withBufferDecay(0.1)
                    .withBufferMax(8.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT)
            .build();

    private final ComponentKey<NearbyPlayers> trackedKey;
    private final double[] look = new double[3];

    private double maxAngle = DEFAULT_MAX_ANGLE;
    private double maxDistance = DEFAULT_MAX_DISTANCE;
    private boolean checkMultiTarget = true;
    private boolean checkItemUse = true;
    private boolean checkContainer = true;
    private boolean checkNoSwing = true;
    private int itemUseConfirmTicks = DEFAULT_ITEM_USE_CONFIRM_TICKS;

    private long lastAttackTick = -1L;
    private int lastAttackEntity;

    // item use (server "hand active" flag, acknowledged)
    private int useEpoch;
    private boolean usingConfirmed;
    private int pendingUseEpoch = -1;
    private int pendingUseTicks;
    private int pendingUseEntity;

    // server-opened container
    private int containerEpoch;
    private boolean containerOpen;

    // swing after attack (tick-end clients)
    private boolean awaitingSwing;
    private int awaitingSwingEntity;

    public KillauraCheck(PlayerData player, ComponentKey<NearbyPlayers> trackedKey) {
        super(player, DESCRIPTOR);
        this.trackedKey = trackedKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        maxAngle = Math.min(180.0, CombatOptions.positive(options, "max-angle", DEFAULT_MAX_ANGLE));
        maxDistance = CombatOptions.positive(options, "max-distance", DEFAULT_MAX_DISTANCE);
        checkMultiTarget = options.getBoolean("check-multi-target", true);
        checkItemUse = options.getBoolean("check-item-use", true);
        checkContainer = options.getBoolean("check-container", true);
        checkNoSwing = options.getBoolean("check-no-swing", true);
        itemUseConfirmTicks = Math.min(40,
                CombatOptions.atLeast(options, "item-use-confirm-ticks", DEFAULT_ITEM_USE_CONFIRM_TICKS, 1));
    }

    @Override
    protected void onReset() {
        lastAttackTick = -1L;
        stopUsing();
        closeContainer();
        awaitingSwing = false;
    }

    // ---- state tracking ------------------------------------------------------------------------

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.ENTITY_METADATA) {
            if (!checkItemUse || event.getUser() == null) {
                return;
            }
            int own = event.getUser().getEntityId();
            WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(event);
            if (own <= 0 || packet.getEntityId() != own) {
                return;
            }
            for (EntityData<?> data : packet.getEntityMetadata()) {
                if (data.getIndex() == NoSlowCheck.LIVING_FLAGS_INDEX && data.getValue() instanceof Number value) {
                    onUsingFlag((value.intValue() & NoSlowCheck.USING_ITEM_BIT) != 0, event.getTasksAfterSend());
                }
            }
        } else if (type == PacketType.Play.Server.OPEN_WINDOW) {
            if (checkContainer) {
                onContainerOpened(event.getTasksAfterSend());
            }
        } else if (type == PacketType.Play.Server.CLOSE_WINDOW) {
            closeContainer();
        } else if (type == PacketType.Play.Server.RESPAWN || type == PacketType.Play.Server.JOIN_GAME) {
            stopUsing();
            closeContainer();
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.ANIMATION) {
            onSwing();
        } else if (type == PacketType.Play.Client.CLOSE_WINDOW) {
            closeContainer();
        } else if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            stopUsing();
        } else if (type == PacketType.Play.Client.PLAYER_DIGGING && (usingConfirmed || pendingUseEpoch >= 0)
                && new WrapperPlayClientPlayerDigging(event).getAction() == DiggingAction.RELEASE_USE_ITEM) {
            stopUsing();
        }
    }

    /** Server set or cleared the own "hand active" flag; setting counts once acknowledged. */
    void onUsingFlag(boolean using, List<Runnable> tasksAfterSend) {
        if (!using) {
            stopUsing();
            return;
        }
        if (usingConfirmed) {
            return;
        }
        int generation = ++useEpoch;
        player.acks().bracket(tasksAfterSend, null, () -> {
            if (generation == useEpoch) {
                usingConfirmed = true;
            }
        });
    }

    /** Server opened a container; it counts once the client acknowledged it. */
    void onContainerOpened(List<Runnable> tasksAfterSend) {
        int generation = ++containerEpoch;
        containerOpen = false;
        player.acks().bracket(tasksAfterSend, null, () -> {
            if (generation == containerEpoch) {
                containerOpen = true;
            }
        });
    }

    void onSwing() {
        awaitingSwing = false;
    }

    private void stopUsing() {
        useEpoch++;
        usingConfirmed = false;
        pendingUseEpoch = -1;
    }

    private void closeContainer() {
        containerEpoch++;
        containerOpen = false;
    }

    @Override
    public void onClientTick() {
        if (awaitingSwing) {
            awaitingSwing = false;
            if (checkNoSwing && player.tickEndMode() && !isExempt()) {
                flag(NO_SWING_WEIGHT, "no-swing entity=" + awaitingSwingEntity);
            }
        }
        if (pendingUseEpoch >= 0) {
            if (pendingUseEpoch != useEpoch || !usingConfirmed) {
                pendingUseEpoch = -1;
            } else if (++pendingUseTicks >= itemUseConfirmTicks) {
                pendingUseEpoch = -1;
                flag(ITEM_USE_WEIGHT, "item-use entity=" + pendingUseEntity);
            }
        }
    }

    // ---- attacks -------------------------------------------------------------------------------

    @Override
    public void onAttack(EntityAttack attack) {
        if (!attack.attacked()) {
            return;
        }
        long tick = player.clientTicks();
        boolean switchedInTick = tick == lastAttackTick && attack.entityId() != lastAttackEntity;
        lastAttackTick = tick;
        lastAttackEntity = attack.entityId();
        if (isExempt()) {
            lowerBuffer();
            return;
        }
        if (checkNoSwing && player.tickEndMode()) {
            awaitingSwing = true;
            awaitingSwingEntity = attack.entityId();
        }
        if (checkItemUse && usingConfirmed && pendingUseEpoch < 0) {
            pendingUseEpoch = useEpoch;
            pendingUseTicks = 0;
            pendingUseEntity = attack.entityId();
        }
        if (checkContainer && containerOpen) {
            flag(CONTAINER_WEIGHT, "container entity=" + attack.entityId());
            return;
        }
        if (switchedInTick && checkMultiTarget && player.tickEndMode()) {
            // Impossible for a vanilla client, so one occurrence weighs as much as two bad angles.
            flag(2.0, "multi-target entity=" + attack.entityId() + " tick=" + tick);
            return;
        }
        NearbyPlayers tracked = player.component(trackedKey);
        int index = tracked.indexOf(attack.entityId());
        MovementState movement = player.movement();
        if (index < 0 || !movement.positionKnown() || !movement.rotationKnown()) {
            lowerBuffer();
            return;
        }
        PoseState pose = player.pose();
        double eyeY = movement.y() + (pose.sneaking()
                ? FreecamCheck.SNEAKING_EYE_HEIGHT : FreecamCheck.STANDING_EYE_HEIGHT);
        double targetY = tracked.centerY(index);
        double dx = tracked.x(index) - movement.x();
        double dy = targetY - eyeY;
        double dz = tracked.z(index) - movement.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance < MIN_DISTANCE || distance > maxDistance) {
            lowerBuffer();
            return;
        }
        FreecamCheck.direction(movement.yaw(), movement.pitch(), look);
        double dot = (dx * look[0] + dy * look[1] + dz * look[2]) / distance;
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
        if (debugging()) {
            debug("entity=" + attack.entityId() + " angle=" + CombatOptions.number(angle)
                    + " limit=" + CombatOptions.number(maxAngle) + " distance="
                    + CombatOptions.number(distance) + " buffer=" + CombatOptions.number(buffer()));
        }
        if (angle > maxAngle) {
            flag(1.0, "entity=" + attack.entityId()
                    + " angle=" + CombatOptions.number(angle)
                    + " limit=" + CombatOptions.number(maxAngle)
                    + " distance=" + CombatOptions.number(distance));
        } else {
            lowerBuffer();
        }
    }

    private void flag(double weight, String details) {
        if (debugging()) {
            debug(details + " buffer=" + CombatOptions.number(buffer()));
        }
        if (raiseBuffer(weight)) {
            violation(details + " buffer=" + CombatOptions.number(buffer()));
            resetBuffer();
        }
    }
}
