package dev.vaplus.anticheat.check.impl.packet;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import dev.vaplus.anticheat.check.BlockCheck;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.check.OutboundPacketCheck;
import dev.vaplus.anticheat.check.PacketCheck;
import dev.vaplus.anticheat.check.impl.movement.NoSlowCheck;
import dev.vaplus.anticheat.check.impl.movement.VehicleCheck;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.PoseState;
import dev.vaplus.anticheat.world.BlockInteraction;

import java.util.List;

/**
 * MultiActions: two actions in combinations the vanilla client never produces (Grim equivalents in
 * brackets). Grim's A (attack while using an item) is the {@code killaura} item-use signal and C
 * (inventory clicks while moving) the {@code autoarmor} moving signal.
 *
 * <ul>
 *   <li>{@code using-break} (MultiActionsB) / {@code using-swing} (MultiActionsE): digging or swinging
 *       while using an item. Vanilla discards attack input while an item is in use. The use is the
 *       server's own "hand active" flag, counted once the client acknowledged it; the action is only
 *       judged if the server still sees the same use {@code use-confirm-ticks} ticks later (eating can
 *       end on the client a moment earlier). The swing that comes with dropping an item is excused.
 *       Experimental.</li>
 *   <li>{@code close-moving} (MultiActionsD, 1.21.2+ clients): closing a container while movement keys
 *       are held or while sprinting (not swimming). Opening a screen releases every key, and the
 *       client reports the release in the same tick.</li>
 *   <li>{@code block-and-entity} (MultiActionsF, 1.21.2+ clients): a block action (dig start/finish,
 *       use on a block) and an entity action in one client tick. The crosshair target is either a
 *       block or an entity for the whole tick. Experimental.</li>
 *   <li>{@code rowing} (MultiActionsG, 1.21.2+ clients): attacking, interacting, using items or
 *       placing while paddling a boat. Vanilla's hands are busy while the boat is rowed.</li>
 * </ul>
 */
public final class MultiActionsCheck extends Check implements PacketCheck, OutboundPacketCheck, BlockCheck, ClientTickCheck {

    public static final int DEFAULT_USE_CONFIRM_TICKS = 3;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("multiactions", CheckCategory.PACKET)
            .displayName("MultiActions")
            .description("Actions combined in ways vanilla never does: digging/swinging while using items, closing GUIs while moving, block and entity in one tick, acting while rowing")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.PACKET)
                    .withBufferLimit(1.0)
                    .withBufferDecay(0.05)
                    .withBufferMax(4.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE)
            .build();

    private boolean checkUsingBreak = true;
    private boolean checkUsingSwing = true;
    private boolean checkCloseMoving = true;
    private boolean checkBlockAndEntity = true;
    private boolean checkRowing = true;
    private int useConfirmTicks = DEFAULT_USE_CONFIRM_TICKS;

    // item use (server flag, acknowledged)
    private boolean usingConfirmed;
    private int useEpoch;
    private String pendingAction;
    private int pendingEpoch;
    private long pendingTick;
    private boolean droppedThisTick;
    // per tick
    private boolean blockThisTick;
    private boolean entityThisTick;
    private boolean serverOpenedThisTick;

    public MultiActionsCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        checkUsingBreak = options.getBoolean("check-using-break", true);
        checkUsingSwing = options.getBoolean("check-using-swing", true);
        checkCloseMoving = options.getBoolean("check-close-moving", true);
        checkBlockAndEntity = options.getBoolean("check-block-and-entity", true);
        checkRowing = options.getBoolean("check-rowing", true);
        int ticks = options.getInt("use-confirm-ticks", DEFAULT_USE_CONFIRM_TICKS);
        useConfirmTicks = ticks >= 1 && ticks <= 40 ? ticks : DEFAULT_USE_CONFIRM_TICKS;
    }

    @Override
    protected void onReset() {
        stopUsing();
        blockThisTick = false;
        entityThisTick = false;
    }

    // ------------------------------------------------------------------ outbound

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.ENTITY_METADATA) {
            if (event.getUser() == null) {
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
            serverOpenedThisTick = true;
        } else if (type == PacketType.Play.Server.RESPAWN || type == PacketType.Play.Server.JOIN_GAME) {
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

    private void stopUsing() {
        useEpoch++;
        usingConfirmed = false;
        pendingAction = null;
    }

    // ------------------------------------------------------------------ inbound

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.ANIMATION) {
            onSwing();
        } else if (type == PacketType.Play.Client.CLOSE_WINDOW) {
            onCloseWindow();
        } else if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            stopUsing();
        } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            DiggingAction action = new WrapperPlayClientPlayerDigging(event).getAction();
            if (action == DiggingAction.RELEASE_USE_ITEM) {
                stopUsing();
            } else if (action == DiggingAction.DROP_ITEM || action == DiggingAction.DROP_ITEM_STACK) {
                droppedThisTick = true;
            }
        } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            onEntityAction();
        } else if (type == PacketType.Play.Client.USE_ITEM) {
            onRowingAction("use item");
        }
    }

    /** Arm swing (using-swing). */
    void onSwing() {
        if (checkUsingSwing && usingConfirmed && !droppedThisTick) {
            remember("swing");
        }
    }

    /** Container closed by the client (close-moving). */
    void onCloseWindow() {
        if (!checkCloseMoving || serverOpenedThisTick || !player.tickEndMode() || isExempt()
                || player.exemptions().isActive(ExemptionCause.PORTAL)) {
            return;
        }
        PoseState pose = player.pose();
        if (!pose.inputKnown() || player.vehicle().inVehicle()) {
            return;
        }
        boolean keys = pose.forward() || pose.backward() || pose.left() || pose.right();
        boolean sprinting = pose.sprinting() && !pose.swimming() && !player.abilities().flying();
        if (keys || sprinting) {
            flag("close-moving keys=" + keys + " sprinting=" + sprinting);
        }
    }

    /** Attack or interaction with an entity (block-and-entity, rowing). */
    void onEntityAction() {
        entityThisTick = true;
        if (checkBlockAndEntity && player.tickEndMode() && blockThisTick && !isExempt()) {
            flag("block-and-entity entity action after a block action in one tick");
        }
        onRowingAction("entity");
    }

    @Override
    public void onBlockInteraction(BlockInteraction interaction) {
        BlockInteraction.Kind kind = interaction.kind();
        if (kind == BlockInteraction.Kind.DIG_START || kind == BlockInteraction.Kind.DIG_FINISH) {
            if (checkUsingBreak && usingConfirmed) {
                remember("dig");
            }
        }
        if (kind == BlockInteraction.Kind.DIG_START || kind == BlockInteraction.Kind.DIG_FINISH
                || kind == BlockInteraction.Kind.PLACE) {
            blockThisTick = true;
            if (checkBlockAndEntity && player.tickEndMode() && entityThisTick && !isExempt()) {
                flag("block-and-entity block action after an entity action in one tick");
            }
        }
        if (kind == BlockInteraction.Kind.PLACE) {
            onRowingAction("place");
        }
    }

    /** Hands are busy while rowing a boat (rowing). */
    private void onRowingAction(String what) {
        if (!checkRowing || !player.tickEndMode() || isExempt()
                || player.exemptions().isActive(ExemptionCause.VEHICLE)) {
            return;
        }
        PoseState pose = player.pose();
        if (!pose.inputKnown() || !(pose.forward() || pose.backward() || pose.left() || pose.right())) {
            return;
        }
        VehicleCheck vehicle = player.checks().get(VehicleCheck.class);
        if (vehicle != null && vehicle.ridingBoat()) {
            flag("rowing " + what + " while paddling a boat");
        }
    }

    private void remember(String action) {
        if (pendingAction == null) {
            pendingAction = action;
            pendingEpoch = useEpoch;
            pendingTick = player.clientTicks();
        }
    }

    @Override
    public void onClientTick() {
        if (pendingAction != null && player.clientTicks() - pendingTick >= useConfirmTicks) {
            if (usingConfirmed && pendingEpoch == useEpoch && !isExempt()) {
                if (raiseBuffer(1.0)) {
                    violation("using-" + (pendingAction.equals("dig") ? "break" : "swing")
                            + " " + pendingAction + " while using an item");
                    resetBuffer();
                }
            }
            pendingAction = null;
        }
        droppedThisTick = false;
        blockThisTick = false;
        entityThisTick = false;
        serverOpenedThisTick = false;
    }

    private void flag(String details) {
        if (raiseBuffer(2.0)) {
            violation(details);
            resetBuffer();
        }
    }
}
