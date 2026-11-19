package dev.vaplus.anticheat.check.impl.packet;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClientStatus;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.check.OutboundPacketCheck;
import dev.vaplus.anticheat.check.PacketCheck;
import dev.vaplus.anticheat.player.PlayerData;

import java.util.ArrayDeque;

/**
 * PacketOrder: packets in an order the vanilla client tick cannot produce (Grim PacketOrderA-P and
 * TransactionOrder).
 *
 * <p>Within one client tick the vanilla client sends, in this order: slot changes, inventory key,
 * offhand swap and drop; then either a release of the used item or attacks, uses and picks; then
 * digging; then sprint/sneak state, input and the movement packet; then (1.21.2+) the tick end. One
 * pick result is shared by everything in a tick, so a tick cannot hit a block and an entity. The
 * "tick" here starts after every movement or tick-end packet (an extra start only hides orders).
 *
 * <ul>
 *   <li>{@code click-order} (A, clients up to 1.12.2): pickup and quick-move clicks in one tick.</li>
 *   <li>{@code interact-order} (C): an entity INTERACT without the INTERACT_AT for the same entity,
 *       hand and sneaking state right before it.</li>
 *   <li>{@code hand-order} (D): an offhand entity interaction without the mainhand one before it.</li>
 *   <li>{@code slot} (E): a slot change after an attack, use, release, sneak, sprint, bed, glide or
 *       mount jump of the same tick.</li>
 *   <li>{@code after-pose} (F): an attack, interaction, use, pick or dig after a sprint or sneak
 *       state change of the same tick.</li>
 *   <li>{@code inventory} (G, K, L): offhand swap or drop after an attack, use, release, pick or dig;
 *       a swap or inventory open after a drop; (up to 1.11) inventory clicks around the open.</li>
 *   <li>{@code pose-order} (H): sprint after sneak (before 1.21.2) or sneak after sprint (1.21.2 to
 *       1.21.5) in one tick.</li>
 *   <li>{@code use-order} (I, J, M, N): interact, place or use while releasing, place or use after
 *       finishing a dig, release after attack/use/pick/dig, attack after use/pick/release/dig, use
 *       after an attack without the entity interaction, entity interaction after a use without one,
 *       block placement after a use without one.</li>
 *   <li>{@code tick-end} (O, 1.21.2+): game actions after the movement packet and before the tick
 *       end (teleport answers, vehicle moves and vehicle sprint toggles excluded).</li>
 *   <li>{@code chunk-ack} (P): a chunk batch acknowledgement before the client can have read the
 *       batch, after it answered the marker behind the batch, or without a batch.</li>
 *   <li>{@code transactions} (TransactionOrder): marker answers that skip earlier markers.</li>
 * </ul>
 * Every signal needs a repeat; experimental like Grim's.
 */
public final class PacketOrderCheck extends Check implements PacketCheck, OutboundPacketCheck, ClientTickCheck {

    static final int ATTACK = 1;
    static final int INTERACT = 1 << 1;
    static final int RELEASE = 1 << 2;
    static final int DIG = 1 << 3;
    static final int DIG_FINISH = 1 << 4;
    static final int SPRINT = 1 << 5;
    static final int SNEAK = 1 << 6;
    static final int PLACE = 1 << 7;
    static final int USE = 1 << 8;
    static final int PICK = 1 << 9;
    static final int DROP = 1 << 10;
    static final int CLICK = 1 << 11;
    static final int CLOSE = 1 << 12;
    static final int QUICK_MOVE = 1 << 13;
    static final int PICKUP = 1 << 14;
    static final int LEAVE_BED = 1 << 15;
    static final int GLIDE = 1 << 16;
    static final int MOUNT_JUMP = 1 << 17;
    static final int OPEN_INVENTORY = 1 << 18;
    static final int RIGHT_CLICK = PLACE | USE | INTERACT;
    /** Pending chunk batches remembered at most (a client that stops answering cannot grow it). */
    static final int MAX_BATCHES = 64;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("packetorder", CheckCategory.PACKET)
            .displayName("PacketOrder")
            .description("Packets in an order the vanilla client tick cannot produce")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.PACKET)
                    .withBufferLimit(1.0)
                    .withBufferDecay(0.05)
                    .withBufferMax(4.0))
            .build();

    private boolean checkClickOrder = true;
    private boolean checkInteractOrder = true;
    private boolean checkHandOrder = true;
    private boolean checkSlot = true;
    private boolean checkAfterPose = true;
    private boolean checkInventory = true;
    private boolean checkPoseOrder = true;
    private boolean checkUseOrder = true;
    private boolean checkTickEnd = true;
    private boolean checkChunkAck = true;
    private boolean checkTransactions = true;

    /** Actions of the current tick. */
    private int tick;
    private boolean interactAtPending;
    private int interactAtEntity;
    private InteractionHand interactAtHand;
    private boolean interactAtSneaking;
    private boolean mainhandSent;
    private int mainhandEntity;
    private boolean mainhandSneaking;
    private boolean useWithoutInteract;
    private boolean interactedBeforeUse;
    private boolean useWithoutPlace;
    private boolean placedBeforeUse;
    private boolean movedThisTick;
    private boolean teleportAnswer;
    private boolean joinSeen;
    private long seenSkipped = -1L;

    private static final class Batch {
        boolean ready;
        boolean acked;
    }

    private final ArrayDeque<Batch> batches = new ArrayDeque<>();

    public PacketOrderCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        checkClickOrder = options.getBoolean("check-click-order", true);
        checkInteractOrder = options.getBoolean("check-interact-order", true);
        checkHandOrder = options.getBoolean("check-hand-order", true);
        checkSlot = options.getBoolean("check-slot", true);
        checkAfterPose = options.getBoolean("check-after-pose", true);
        checkInventory = options.getBoolean("check-inventory", true);
        checkPoseOrder = options.getBoolean("check-pose-order", true);
        checkUseOrder = options.getBoolean("check-use-order", true);
        checkTickEnd = options.getBoolean("check-tick-end", true);
        checkChunkAck = options.getBoolean("check-chunk-ack", true);
        checkTransactions = options.getBoolean("check-transactions", true);
    }

    @Override
    protected void onReset() {
        endTick();
        movedThisTick = false;
        teleportAnswer = false;
        batches.clear();
    }

    // ------------------------------------------------------------------ packet dispatch

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (!(type instanceof PacketType.Play.Client)) {
            return;
        }
        if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            onMovement();
            return;
        }
        if (type == PacketType.Play.Client.CLIENT_TICK_END) {
            onTickEnd();
            return;
        }
        if (type == PacketType.Play.Client.TELEPORT_CONFIRM) {
            onTeleportConfirm();
            return;
        }
        if (type == PacketType.Play.Client.CHUNK_BATCH_ACK) {
            onChunkBatchAck();
            return;
        }
        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            WrapperPlayClientInteractEntity.InteractAction action = packet.getAction();
            if (action == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                onAttack();
            } else {
                onInteract(action == WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT, packet.getEntityId(),
                        packet.getHand(), packet.isSneaking().orElse(false));
            }
        } else if (type == PacketType.Play.Client.ATTACK || type == PacketType.Play.Client.SPECTATE_ENTITY) {
            onAttack();
        } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            onDig(new WrapperPlayClientPlayerDigging(event).getAction());
        } else if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            onUse(new WrapperPlayClientPlayerBlockPlacement(event).getFace() != BlockFace.OTHER);
        } else if (type == PacketType.Play.Client.USE_ITEM) {
            onUse(false);
        } else if (type == PacketType.Play.Client.PICK_ITEM || type == PacketType.Play.Client.PICK_ITEM_FROM_BLOCK
                || type == PacketType.Play.Client.PICK_ITEM_FROM_ENTITY) {
            onPick();
        } else if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            onSlotChange();
        } else if (type == PacketType.Play.Client.ENTITY_ACTION) {
            onEntityAction(new WrapperPlayClientEntityAction(event).getAction());
        } else if (type == PacketType.Play.Client.CLIENT_STATUS) {
            if (new WrapperPlayClientClientStatus(event).getAction()
                    == WrapperPlayClientClientStatus.Action.OPEN_INVENTORY_ACHIEVEMENT) {
                onOpenInventory();
            }
        } else if (type == PacketType.Play.Client.CLICK_WINDOW) {
            onClick(new WrapperPlayClientClickWindow(event).getWindowClickType());
        } else if (type == PacketType.Play.Client.CLOSE_WINDOW) {
            onClose();
        } else if (type == PacketType.Play.Client.ANIMATION || type == PacketType.Play.Client.PLAYER_ABILITIES
                || type == PacketType.Play.Client.PLAYER_INPUT) {
            tickEnd(type.getName());
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.CHUNK_BATCH_END) {
            onChunkBatchEnd(event.getTasksAfterSend());
        } else if (type == PacketType.Play.Server.JOIN_GAME) {
            joinSeen = true;
            batches.clear();
        }
    }

    // ------------------------------------------------------------------ tick boundaries

    /** The next movement packet answers a teleport and is not part of a client tick. */
    void onTeleportConfirm() {
        teleportAnswer = true;
    }

    void onMovement() {
        if (teleportAnswer) {
            teleportAnswer = false;
        } else {
            movedThisTick = true;
        }
        endTick();
    }

    void onTickEnd() {
        movedThisTick = false;
        endTick();
    }

    private void endTick() {
        tick = 0;
        interactAtPending = false;
        mainhandSent = false;
        useWithoutInteract = false;
        interactedBeforeUse = false;
        useWithoutPlace = false;
        placedBeforeUse = false;
    }

    private boolean has(int bits) {
        return (tick & bits) != 0;
    }

    // ------------------------------------------------------------------ actions

    void onAttack() {
        tickEnd("attack");
        afterPose("attack");
        if (checkUseOrder && has(RIGHT_CLICK | PICK | RELEASE | DIG)) {
            flag("use-order attack after " + names(tick & (RIGHT_CLICK | PICK | RELEASE | DIG)));
        }
        tick |= ATTACK;
    }

    void onInteract(boolean at, int entity, InteractionHand hand, boolean sneaking) {
        tickEnd("interact");
        afterPose("interact");
        ClientVersion version = player.clientVersion();
        if (checkInteractOrder && !at && version.isNewerThanOrEquals(ClientVersion.V_1_8)
                && version.isOlderThan(ClientVersion.V_26_1)) {
            if (!interactAtPending) {
                flag("interact-order interact without interact-at");
            } else if (entity != interactAtEntity || hand != interactAtHand || sneaking != interactAtSneaking) {
                flag("interact-order interact does not match its interact-at");
            }
        }
        if (at) {
            interactAtPending = true;
            interactAtEntity = entity;
            interactAtHand = hand;
            interactAtSneaking = sneaking;
        } else {
            interactAtPending = false;
        }
        if (hand == InteractionHand.OFF_HAND) {
            if (checkHandOrder && version.isNewerThanOrEquals(ClientVersion.V_1_9)) {
                if (!at && !mainhandSent) {
                    flag("hand-order offhand interaction without mainhand");
                } else if (at && mainhandSent && (entity != mainhandEntity || sneaking != mainhandSneaking)) {
                    flag("hand-order offhand interaction does not match the mainhand one");
                }
            }
            if (!at) {
                mainhandSent = false;
            }
        } else {
            mainhandSent = true;
            mainhandEntity = entity;
            mainhandSneaking = sneaking;
        }
        if (checkUseOrder && has(RELEASE | DIG)) {
            flag("use-order interaction after " + names(tick & (RELEASE | DIG)));
        }
        if (checkUseOrder && useWithoutInteract) {
            flag("use-order interaction after a use without interaction");
        }
        interactedBeforeUse = true;
        tick |= INTERACT;
    }

    /** Block placement ({@code place}) or item use. */
    void onUse(boolean place) {
        tickEnd(place ? "place" : "use");
        afterPose(place ? "place" : "use");
        if (checkUseOrder) {
            if (has(RELEASE | DIG_FINISH)) {
                flag("use-order " + (place ? "place" : "use") + " after " + names(tick & (RELEASE | DIG_FINISH)));
            } else if (has(ATTACK) && !has(INTERACT)) {
                flag("use-order " + (place ? "place" : "use") + " after an attack without interaction");
            } else if (place && useWithoutPlace) {
                flag("use-order place after a use without placement");
            }
        }
        if (place) {
            placedBeforeUse = true;
            tick |= PLACE;
        } else {
            if (!interactedBeforeUse) {
                useWithoutInteract = true;
            }
            if (!placedBeforeUse) {
                useWithoutPlace = true;
            }
            interactedBeforeUse = false;
            placedBeforeUse = false;
            tick |= USE;
        }
    }

    void onPick() {
        tickEnd("pick");
        afterPose("pick");
        tick |= PICK;
    }

    void onDig(DiggingAction action) {
        if (action == null) {
            return;
        }
        tickEnd("dig");
        afterPose("dig");
        switch (action) {
            case SWAP_ITEM_WITH_OFFHAND -> {
                inventoryAfterActions("swap");
                if (checkInventory && has(DROP)) {
                    flag("inventory swap after drop");
                }
            }
            case DROP_ITEM, DROP_ITEM_STACK -> {
                inventoryAfterActions("drop");
                tick |= DROP;
            }
            case RELEASE_USE_ITEM -> {
                if (checkUseOrder && has(ATTACK | RIGHT_CLICK | PICK | DIG)) {
                    flag("use-order release after " + names(tick & (ATTACK | RIGHT_CLICK | PICK | DIG)));
                }
                tick |= RELEASE;
            }
            case START_DIGGING, CANCELLED_DIGGING -> tick |= DIG;
            case FINISHED_DIGGING -> tick |= DIG | DIG_FINISH;
            case STAB -> {
                if (checkUseOrder && has(RIGHT_CLICK | PICK | RELEASE | DIG)) {
                    flag("use-order attack after " + names(tick & (RIGHT_CLICK | PICK | RELEASE | DIG)));
                }
                tick |= ATTACK;
            }
            default -> {
            }
        }
    }

    private void inventoryAfterActions(String what) {
        int conflicting = tick & (ATTACK | RELEASE | RIGHT_CLICK | PICK | DIG);
        if (checkInventory && conflicting != 0) {
            flag("inventory " + what + " after " + names(conflicting));
        }
    }

    void onSlotChange() {
        tickEnd("slot");
        // A mount inventory request does not release the keys, so a slot change may follow it.
        int conflicting = tick & (ATTACK | RIGHT_CLICK | RELEASE | SNEAK | SPRINT | LEAVE_BED | GLIDE | MOUNT_JUMP);
        if (checkSlot && conflicting != 0) {
            flag("slot changed after " + names(conflicting));
        }
    }

    void onEntityAction(WrapperPlayClientEntityAction.Action action) {
        if (action == null) {
            return;
        }
        ClientVersion version = player.clientVersion();
        switch (action) {
            case START_SPRINTING, STOP_SPRINTING -> {
                if (player.vehicle().inVehicle()) {
                    return;
                }
                tickEnd("sprint");
                if (checkPoseOrder && has(SNEAK) && version.isOlderThan(ClientVersion.V_1_21_2)) {
                    flag("pose-order sprint after sneak");
                }
                tick |= SPRINT;
            }
            case START_SNEAKING, STOP_SNEAKING -> {
                tickEnd("sneak");
                if (checkPoseOrder && has(SPRINT) && version.isNewerThanOrEquals(ClientVersion.V_1_21_2)
                        && version.isOlderThan(ClientVersion.V_1_21_6)) {
                    flag("pose-order sneak after sprint");
                }
                tick |= SNEAK;
            }
            case LEAVE_BED -> {
                tickEnd("bed");
                tick |= LEAVE_BED;
            }
            case START_FLYING_WITH_ELYTRA -> {
                tickEnd("glide");
                tick |= GLIDE;
            }
            case OPEN_HORSE_INVENTORY -> {
                tickEnd("mount inventory");
                tick |= OPEN_INVENTORY;
            }
            case START_JUMPING_WITH_HORSE, STOP_JUMPING_WITH_HORSE -> {
                tickEnd("mount jump");
                tick |= MOUNT_JUMP;
            }
            default -> {
            }
        }
    }

    /** Pre-1.12 "open inventory" status (newer clients open it silently). */
    void onOpenInventory() {
        afterPose("open inventory");
        if (checkInventory) {
            if (has(DROP)) {
                flag("inventory open after drop");
            } else if (has(CLICK | CLOSE) && player.clientVersion().isOlderThan(ClientVersion.V_1_12)) {
                flag("inventory open after " + names(tick & (CLICK | CLOSE)));
            } else {
                inventoryAfterActions("open");
            }
        }
        tick |= OPEN_INVENTORY;
    }

    void onClick(WrapperPlayClientClickWindow.WindowClickType click) {
        if (checkInventory && has(OPEN_INVENTORY) && player.clientVersion().isOlderThan(ClientVersion.V_1_12)) {
            flag("inventory click right after opening");
        }
        boolean old = player.clientVersion().isOlderThanOrEquals(ClientVersion.V_1_12_2);
        if (click == WrapperPlayClientClickWindow.WindowClickType.QUICK_MOVE) {
            if (checkClickOrder && old && has(PICKUP)) {
                flag("click-order quick-move after pickup");
            }
            tick |= QUICK_MOVE;
        } else if (click == WrapperPlayClientClickWindow.WindowClickType.PICKUP
                || click == WrapperPlayClientClickWindow.WindowClickType.PICKUP_ALL) {
            if (checkClickOrder && old && has(QUICK_MOVE)) {
                flag("click-order pickup after quick-move");
            }
            tick |= PICKUP;
        }
        tick |= CLICK;
    }

    void onClose() {
        if (checkInventory && has(OPEN_INVENTORY) && player.clientVersion().isOlderThan(ClientVersion.V_1_12)) {
            flag("inventory close right after opening");
        }
        tick |= CLOSE;
    }

    /** F: a game action after a sprint or sneak state change of the same tick. */
    private void afterPose(String what) {
        if (checkAfterPose && has(SPRINT | SNEAK)) {
            flag("after-pose " + what + " after " + names(tick & (SPRINT | SNEAK)));
        }
    }

    /** O: a game action between the movement packet and the tick end (1.21.2+). */
    private void tickEnd(String what) {
        if (checkTickEnd && movedThisTick && player.tickEndMode()) {
            flag("tick-end " + what + " after the movement packet");
        }
    }

    // ------------------------------------------------------------------ chunk batches and markers

    void onChunkBatchEnd(java.util.List<Runnable> tasksAfterSend) {
        if (!checkChunkAck) {
            return;
        }
        if (batches.size() >= MAX_BATCHES) {
            batches.clear();
        }
        Batch batch = new Batch();
        batches.addLast(batch);
        player.acks().bracket(tasksAfterSend, () -> batch.ready = true, () -> {
            if (!batch.acked && batches.contains(batch)) {
                flag("chunk-ack answered the marker behind a chunk batch before acknowledging it");
            }
            batches.remove(batch);
        });
    }

    void onChunkBatchAck() {
        if (!checkChunkAck) {
            return;
        }
        Batch batch = null;
        for (Batch candidate : batches) {
            if (!candidate.acked) {
                batch = candidate;
                break;
            }
        }
        if (batch == null) {
            if (joinSeen) {
                flag("chunk-ack acknowledged a chunk batch that was never sent");
            }
            return;
        }
        batch.acked = true;
        if (!batch.ready) {
            flag("chunk-ack acknowledged a chunk batch before receiving it");
        }
    }

    @Override
    public void onClientTick() {
        long skipped = player.acks().skipped();
        if (seenSkipped >= 0 && skipped > seenSkipped && checkTransactions) {
            flag("transactions answered " + (skipped - seenSkipped) + " marker(s) out of order");
        }
        seenSkipped = skipped;
    }

    // ------------------------------------------------------------------ helpers

    static String names(int bits) {
        StringBuilder out = new StringBuilder();
        String[] names = {"attack", "interact", "release", "dig", "dig-finish", "sprint", "sneak", "place", "use",
                "pick", "drop", "click", "close", "quick-move", "pickup", "bed", "glide", "mount-jump",
                "open-inventory"};
        for (int i = 0; i < names.length; i++) {
            if ((bits & (1 << i)) != 0) {
                if (out.length() > 0) {
                    out.append('+');
                }
                out.append(names[i]);
            }
        }
        return out.toString();
    }

    private void flag(String details) {
        if (isExempt()) {
            return;
        }
        if (debugging()) {
            debug(details);
        }
        if (raiseBuffer(1.0)) {
            violation(details);
            resetBuffer();
        }
    }
}
