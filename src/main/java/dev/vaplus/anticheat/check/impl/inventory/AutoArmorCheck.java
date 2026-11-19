package dev.vaplus.anticheat.check.impl.inventory;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow.WindowClickType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.check.OutboundPacketCheck;
import dev.vaplus.anticheat.check.PacketCheck;
import dev.vaplus.anticheat.latency.MarkerTracker;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.PoseState;

import java.util.Map;
import java.util.Optional;

/**
 * AutoArmor / AutoTotem / offhand-swap bots: inventory manipulation faster than a human, or while
 * the inventory cannot be open. Experimental, all tracking is private to the check.
 *
 * <ol>
 *   <li><b>totem</b> (AutoTotem): the player's own totem pop (entity event 35 for the own entity id
 *       taken from the join packet) is tracked until the client provably received it (marker
 *       acknowledged). The first offhand action after that (window-0 click on slot 45, hotbar swap
 *       with button 40, or the swap-with-offhand key) is judged by the number of <em>client ticks</em>
 *       since the acknowledgement: at most {@code max-reaction-ticks} is faster than a human can see
 *       the pop, find a totem and press the key or click. Actions queued before the acknowledgement
 *       precede it in the packet stream and are not judged. Counting client ticks makes lag bursts
 *       harmless: delayed packets keep their relative tick distance.</li>
 *   <li><b>moving</b> (inventory-walk bots): a window-0 click while the client has been holding
 *       movement keys (1.21.2+ player-input packet) or sprinting (older clients) for at least
 *       {@code move-grace-ticks} client ticks. Vanilla releases every key when a screen opens
 *       (UNCONFIRMED against a capture), so a real inventory click cannot coincide with held
 *       movement input. The grace covers the packet that reports the release.</li>
 *   <li><b>attack</b>: an attack in the same client tick segment as a window-0 click (tick-end
 *       clients only; vanilla routes mouse clicks to the screen while it is open). Low buffer
 *       weight, because a very fast close-and-attack can legitimately land in one segment.</li>
 *   <li><b>armor</b> (AutoArmor): at least {@code armor-min-clicks} shift-clicks in window 0 whose
 *       changed slots include an armor slot (5-8), each at most {@code armor-max-gap-ticks} after
 *       the previous one. Moving the cursor to the next piece takes a human several ticks.</li>
 * </ol>
 *
 * <p>Window 0 layout (vanilla player inventory menu): 0 craft result, 1-4 craft grid, 5-8 armor,
 * 9-35 inventory, 36-44 hotbar, 45 offhand. Creative and spectator players are exempt through the
 * INVENTORY category exemption. Clicks of other windows (chests, server menus) are ignored. Known
 * gap: a quick-move of a totem into the offhand through another slot is not recognised as an
 * offhand action.
 */
public final class AutoArmorCheck extends Check implements PacketCheck, OutboundPacketCheck, ClientTickCheck {

    public static final int DEFAULT_MAX_REACTION_TICKS = 2;
    public static final int DEFAULT_REACTION_WINDOW_TICKS = 100;
    public static final int DEFAULT_MOVE_GRACE_TICKS = 3;
    public static final int DEFAULT_ARMOR_MIN_CLICKS = 3;
    public static final int DEFAULT_ARMOR_MAX_GAP_TICKS = 1;
    public static final boolean DEFAULT_CHECK_TOTEM = true;
    public static final boolean DEFAULT_CHECK_MOVING = true;
    public static final boolean DEFAULT_CHECK_ATTACK = true;
    public static final boolean DEFAULT_CHECK_ARMOR = true;

    static final int TOTEM_POP_STATUS = 35;
    static final int UNKNOWN_ENTITY_ID = Integer.MIN_VALUE;
    static final int OFFHAND_SLOT = 45;
    static final int OFFHAND_SWAP_BUTTON = 40;
    static final int FIRST_ARMOR_SLOT = 5;
    static final int LAST_ARMOR_SLOT = 8;
    static final int FIRST_STORAGE_SLOT = 9;

    private static final double TOTEM_WEIGHT = 1.5;
    private static final double MOVING_WEIGHT = 1.0;
    private static final double ATTACK_WEIGHT = 0.75;
    private static final double ARMOR_WEIGHT = 2.5;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("autoarmor", CheckCategory.INVENTORY)
            .displayName("AutoArmor")
            .description("Superhuman armor / totem / offhand handling and inventory clicks while moving")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.INVENTORY)
                    .withBufferLimit(2.0)
                    .withBufferDecay(0.25)
                    .withBufferMax(6.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.GAMEMODE_CHANGE)
            .build();

    private boolean checkTotem = DEFAULT_CHECK_TOTEM;
    private boolean checkMoving = DEFAULT_CHECK_MOVING;
    private boolean checkAttack = DEFAULT_CHECK_ATTACK;
    private boolean checkArmor = DEFAULT_CHECK_ARMOR;
    private int maxReactionTicks = DEFAULT_MAX_REACTION_TICKS;
    private int reactionWindowTicks = DEFAULT_REACTION_WINDOW_TICKS;
    private int moveGraceTicks = DEFAULT_MOVE_GRACE_TICKS;
    private int armorMinClicks = DEFAULT_ARMOR_MIN_CLICKS;
    private int armorMaxGapTicks = DEFAULT_ARMOR_MAX_GAP_TICKS;

    private int selfEntityId = UNKNOWN_ENTITY_ID;
    /** Bumped on reset so a late marker acknowledgement of an older pop is dropped. */
    private int popGeneration;
    private boolean popAcked;
    private long popAckTick;

    private int movingTicks;
    private long lastClickTick = -1L;
    private long lastAttackTick = -1L;
    private long lastArmorTick = -1L;
    private int armorStreak;

    public AutoArmorCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        checkTotem = options.getBoolean("check-totem", DEFAULT_CHECK_TOTEM);
        checkMoving = options.getBoolean("check-moving", DEFAULT_CHECK_MOVING);
        checkAttack = options.getBoolean("check-attack", DEFAULT_CHECK_ATTACK);
        checkArmor = options.getBoolean("check-armor", DEFAULT_CHECK_ARMOR);
        maxReactionTicks = inRange(options.getInt("max-reaction-ticks", DEFAULT_MAX_REACTION_TICKS),
                0, 10, DEFAULT_MAX_REACTION_TICKS);
        reactionWindowTicks = inRange(options.getInt("reaction-window-ticks", DEFAULT_REACTION_WINDOW_TICKS),
                maxReactionTicks + 1, 1200, Math.max(DEFAULT_REACTION_WINDOW_TICKS, maxReactionTicks + 1));
        moveGraceTicks = inRange(options.getInt("move-grace-ticks", DEFAULT_MOVE_GRACE_TICKS),
                1, 40, DEFAULT_MOVE_GRACE_TICKS);
        armorMinClicks = inRange(options.getInt("armor-min-clicks", DEFAULT_ARMOR_MIN_CLICKS),
                2, 10, DEFAULT_ARMOR_MIN_CLICKS);
        armorMaxGapTicks = inRange(options.getInt("armor-max-gap-ticks", DEFAULT_ARMOR_MAX_GAP_TICKS),
                0, 10, DEFAULT_ARMOR_MAX_GAP_TICKS);
    }

    private static int inRange(int value, int min, int max, int fallback) {
        return value < min || value > max ? fallback : value;
    }

    @Override
    protected void onReset() {
        popGeneration++;
        popAcked = false;
        movingTicks = 0;
        lastClickTick = -1L;
        lastAttackTick = -1L;
        lastArmorTick = -1L;
        armorStreak = 0;
    }

    // ---- outbound: own entity id and totem pops -------------------------------------------------

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.ENTITY_STATUS) {
            if (!checkTotem || selfEntityId == UNKNOWN_ENTITY_ID) {
                return;
            }
            WrapperPlayServerEntityStatus status = new WrapperPlayServerEntityStatus(event);
            if (status.getEntityId() == selfEntityId && status.getStatus() == TOTEM_POP_STATUS) {
                int generation = popGeneration;
                // The marker goes out after the pop: its acknowledgement proves the pop was received.
                event.getTasksAfterSend().add(() -> {
                    MarkerTracker acks = player.acks();
                    acks.whenAcked(acks.sendMarker(), () -> {
                        if (generation == popGeneration) {
                            onTotemPopAcked();
                        }
                    });
                });
            }
        } else if (type == PacketType.Play.Server.JOIN_GAME) {
            selfEntityId = new WrapperPlayServerJoinGame(event).getEntityId();
            onReset();
        }
    }

    /** The client acknowledged a packet stream that contained its own totem pop. */
    void onTotemPopAcked() {
        popAcked = true;
        popAckTick = player.clientTicks();
    }

    // ---- inbound ----------------------------------------------------------------------------------

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.CLICK_WINDOW) {
            WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);
            if (click.getWindowId() != 0) {
                return;
            }
            onWindowClick(click.getSlot(), click.getButton(), click.getWindowClickType(), armorTarget(click));
        } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            if (checkTotem && new WrapperPlayClientPlayerDigging(event).getAction()
                    == DiggingAction.SWAP_ITEM_WITH_OFFHAND) {
                onOffhandAction();
            }
        } else if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            if (checkAttack && new WrapperPlayClientInteractEntity(event).getAction()
                    == WrapperPlayClientInteractEntity.InteractAction.ATTACK) {
                onAttack();
            }
        }
    }

    private boolean armorTarget(WrapperPlayClientClickWindow click) {
        if (!checkArmor || click.getWindowClickType() != WindowClickType.QUICK_MOVE) {
            return false;
        }
        Optional<Map<Integer, ItemStack>> slots = click.getSlots();
        if (slots != null && slots.isPresent() && touchesArmor(slots.get().keySet())) {
            return true;
        }
        Map<Integer, ?> hashed = click.getHashedSlots();
        return hashed != null && touchesArmor(hashed.keySet());
    }

    static boolean touchesArmor(Iterable<Integer> changedSlots) {
        for (Integer slot : changedSlots) {
            if (slot != null && slot >= FIRST_ARMOR_SLOT && slot <= LAST_ARMOR_SLOT) {
                return true;
            }
        }
        return false;
    }

    /**
     * A click in the player inventory window (id 0).
     *
     * @param armorTarget whether a quick-move reported an armor slot among its changed slots
     */
    void onWindowClick(int slot, int button, WindowClickType type, boolean armorTarget) {
        if (isExempt() || type == WindowClickType.QUICK_CRAFT) {
            return;
        }
        long tick = player.clientTicks();
        lastClickTick = tick;
        if (checkTotem && (slot == OFFHAND_SLOT || (type == WindowClickType.SWAP && button == OFFHAND_SWAP_BUTTON))) {
            onOffhandAction();
        }
        if (checkMoving && movingTicks >= moveGraceTicks && raiseBuffer(MOVING_WEIGHT)) {
            violation("inventory click while moving slot=" + slot + " type=" + type
                    + " movingTicks=" + movingTicks);
            resetBuffer();
        }
        if (checkAttack && player.tickEndMode() && lastAttackTick == tick) {
            flagAttack(tick, "click after attack");
        }
        if (checkArmor && armorTarget && slot >= FIRST_STORAGE_SLOT) {
            onArmorClick(tick);
        }
    }

    /** The first offhand swap/click after an acknowledged totem pop is judged by its reaction time. */
    void onOffhandAction() {
        if (isExempt() || !popAcked) {
            return;
        }
        long delta = player.clientTicks() - popAckTick;
        popAcked = false;
        if (delta > reactionWindowTicks) {
            return;
        }
        if (delta <= maxReactionTicks) {
            if (raiseBuffer(TOTEM_WEIGHT)) {
                violation("offhand action " + delta + " client ticks after totem pop (max "
                        + maxReactionTicks + ")");
                resetBuffer();
            }
        } else {
            lowerBuffer();
        }
    }

    void onAttack() {
        if (isExempt()) {
            return;
        }
        long tick = player.clientTicks();
        lastAttackTick = tick;
        if (checkAttack && player.tickEndMode() && lastClickTick == tick) {
            flagAttack(tick, "attack after click");
        }
    }

    private void flagAttack(long tick, String what) {
        if (raiseBuffer(ATTACK_WEIGHT)) {
            violation(what + " within client tick " + tick);
            resetBuffer();
        }
    }

    private void onArmorClick(long tick) {
        if (armorStreak > 0 && tick - lastArmorTick <= armorMaxGapTicks) {
            armorStreak++;
        } else {
            armorStreak = 1;
        }
        lastArmorTick = tick;
        if (armorStreak >= armorMinClicks) {
            if (raiseBuffer(ARMOR_WEIGHT)) {
                violation("armor shift-clicks=" + armorStreak + " gap<=" + armorMaxGapTicks + " ticks");
                resetBuffer();
            }
            armorStreak = 0;
        }
    }

    // ---- tick -------------------------------------------------------------------------------------

    @Override
    public void onClientTick() {
        if (isExempt()) {
            movingTicks = 0;
            return;
        }
        PoseState pose = player.pose();
        boolean moving = isMoving(pose.inputKnown(),
                pose.forward() || pose.backward() || pose.left() || pose.right(), pose.sprinting());
        movingTicks = moving ? movingTicks + 1 : 0;
    }

    /**
     * Whether the client holds movement input: from the raw key state when the client reports it
     * (1.21.2+), otherwise from the sprint flag, which vanilla clears once the keys are released.
     */
    static boolean isMoving(boolean inputKnown, boolean movementKeys, boolean sprinting) {
        return inputKnown ? movementKeys : sprinting;
    }

    int movingTicks() {
        return movingTicks;
    }
}
