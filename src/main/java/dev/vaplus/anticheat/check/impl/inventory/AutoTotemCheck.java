package dev.vaplus.anticheat.check.impl.inventory;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.HashedStack;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow.WindowClickType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientCloseWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.OutboundPacketCheck;
import dev.vaplus.anticheat.check.PacketCheck;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Totem-specific evidence for Hover Totem / Auto Inventory Totem (inventory SWAP) and Totem
 * Offhand (held-slot change + F swap). Original implementation, informed by TotemGuard's
 * AutoTotemB delay-consistency approach; no TotemGuard code is copied.
 *
 * <p>Only transitions from a known non-totem to a totem count. A ten-slot, type-only view follows
 * the hotbar/offhand from acknowledged server updates and client changed-slot reports (including
 * 1.21.5+ hashed stacks). It is NOT an authoritative inventory simulator: client reports are
 * evidence, not proof of a legal click. Unsupported/missing data becomes unknown, never a flag.
 * Container interactions invalidate the view; server grants never count as client replenishments.
 *
 * <p>Two buffered heuristics: repeatedly filling hotbar and offhand within one client tick, and
 * low variance across several pop-to-offhand refill delays with the same input method. New clients
 * use explicit client ticks. Legacy clients use monotonic elapsed time, gated by RTT and excluding
 * sub-10ms burst intervals; jitter can still affect legacy evidence (shadow mode recommended).
 * The existing AutoArmor check retains its fast-reaction check; we do not score that twice here.
 * Legacy inventory screens can suppress movement packets, so their inferred ticks are not used.
 * Own-inventory opening and mouse hover are not observable server-side. Large/random
 * delays, missing acknowledgements or unsupported inventory updates can therefore evade this.
 */
public final class AutoTotemCheck extends Check implements PacketCheck, OutboundPacketCheck {

    public static final int DEFAULT_MIN_SAMPLES = 6;
    public static final int DEFAULT_REACTION_WINDOW_TICKS = 100;
    public static final double DEFAULT_MAX_MEAN_TICKS = 20.0;
    public static final double DEFAULT_MAX_STDDEV_TICKS = 0.35;
    public static final int DEFAULT_PAIR_GAP_TICKS = 1;
    public static final int DEFAULT_LEGACY_MAX_RTT_MILLIS = 150;
    private static final double NANOS_PER_TICK = 50_000_000.0;
    private static final int MAX_SAMPLES = 20;
    private static final int HOTBAR_START = 36;
    private static final int OFFHAND = 9; // index in our compact view; menu slot 45
    private static final byte UNKNOWN = 0;
    private static final byte OTHER = 1;
    private static final byte TOTEM = 2;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("autototem", CheckCategory.INVENTORY)
            .displayName("AutoTotem")
            .description("Repeated rapid dual-hand totem equips and consistent totem refill delays")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.INVENTORY)
                    .withBufferLimit(3.0).withBufferDecay(0.5).withBufferMax(8.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.TELEPORT)
            .build();

    private final byte[] hands = new byte[10];
    private final int[] revisions = new int[10];
    private final double[] delays = new double[MAX_SAMPLES];
    private int generation;
    private int selfEntityId = Integer.MIN_VALUE;
    private int selectedSlot = -1;
    private int selectionRevision;
    private boolean containerOpen;
    private boolean popPending;
    private int popSequence;
    private long popTick;
    private long popNanos;
    private long lastPopTick = -1L;
    private long lastPopNanos;
    private int sampleCount;
    private int sampleIndex;
    private String sampleMethod;
    private long lastEquipTick = -1L;
    private long lastEquipNanos;
    private int lastEquipHand = -1;
    private boolean timingTickEnd;

    private boolean checkConsistency;
    private boolean checkPairs;
    private boolean checkLegacy;
    private int minSamples;
    private int reactionWindow;
    private int pairGap;
    private double maxMean;
    private double maxStddev;
    private int legacyMaxRtt;

    public AutoTotemCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        checkConsistency = options.getBoolean("check-consistency", true);
        checkPairs = options.getBoolean("check-pairs", true);
        checkLegacy = options.getBoolean("check-legacy-timing", true);
        legacyMaxRtt = intIn(options, "legacy-max-rtt-ms", DEFAULT_LEGACY_MAX_RTT_MILLIS, 1, 1000);
        minSamples = intIn(options, "min-samples", DEFAULT_MIN_SAMPLES, 5, MAX_SAMPLES);
        reactionWindow = intIn(options, "reaction-window-ticks", DEFAULT_REACTION_WINDOW_TICKS, 20, 1200);
        pairGap = intIn(options, "pair-gap-ticks", DEFAULT_PAIR_GAP_TICKS, 0, 3);
        maxMean = doubleIn(options, "max-mean-ticks", DEFAULT_MAX_MEAN_TICKS, 0, 40);
        maxStddev = doubleIn(options, "max-stddev-ticks", DEFAULT_MAX_STDDEV_TICKS, 0, 2);
        clearTiming();
    }

    private static int intIn(CheckOptions options, String key, int fallback, int min, int max) {
        int value = options.getInt(key, fallback);
        return value < min || value > max ? fallback : value;
    }

    private static double doubleIn(CheckOptions options, String key, double fallback, double min, double max) {
        double value = options.getDouble(key, fallback);
        return !Double.isFinite(value) || value < min || value > max ? fallback : value;
    }

    private void clearTiming() {
        popPending = false;
        lastPopTick = -1L;
        sampleCount = 0;
        sampleIndex = 0;
        sampleMethod = null;
        lastEquipTick = -1L;
        lastEquipHand = -1;
    }

    @Override
    protected void onReset() {
        generation++;
        Arrays.fill(hands, UNKNOWN);
        selectedSlot = -1;
        containerOpen = false;
        clearTiming();
    }

    private void writeHand(int index, byte value) {
        hands[index] = value;
        revisions[index]++;
    }

    /** An update in flight is uncertain; late acknowledgements must not overwrite newer clicks. */
    private void queueHand(int index, byte value, List<Runnable> tasksAfterSend) {
        writeHand(index, UNKNOWN);
        int revision = revisions[index];
        int mine = generation;
        tasksAfterSend.add(() -> player.acks().whenAcked(player.acks().sendMarker(), () -> {
            if (generation == mine && revisions[index] == revision) {
                writeHand(index, value);
            }
        }));
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.JOIN_GAME) {
            onJoin(new WrapperPlayServerJoinGame(event).getEntityId());
        } else if (type == PacketType.Play.Server.ENTITY_STATUS) {
            WrapperPlayServerEntityStatus status = new WrapperPlayServerEntityStatus(event);
            onStatus(status.getEntityId(), status.getStatus(), event.getTasksAfterSend());
        } else if (type == PacketType.Play.Server.WINDOW_ITEMS) {
            onContents(new WrapperPlayServerWindowItems(event), event.getTasksAfterSend());
        } else if (type == PacketType.Play.Server.SET_SLOT) {
            WrapperPlayServerSetSlot slot = new WrapperPlayServerSetSlot(event);
            onSetSlot(slot.getWindowId(), slot.getSlot(), slot.getItem(), event.getTasksAfterSend());
        } else if (type == PacketType.Play.Server.SET_PLAYER_INVENTORY) {
            WrapperPlayServerSetPlayerInventory slot = new WrapperPlayServerSetPlayerInventory(event);
            onSetSlot(-2, slot.getSlot(), slot.getStack(), event.getTasksAfterSend());
        } else if (type == PacketType.Play.Server.HELD_ITEM_CHANGE) {
            onServerHeldSlot(new WrapperPlayServerHeldItemChange(event).getSlot(), event.getTasksAfterSend());
        } else if (type == PacketType.Play.Server.OPEN_WINDOW) {
            onContainer(true);
        } else if (type == PacketType.Play.Server.CLOSE_WINDOW) {
            onContainer(false);
        }
    }

    void onJoin(int entityId) {
        selfEntityId = entityId;
        onReset();
        selectedSlot = 0;
    }

    void onStatus(int entityId, int status, List<Runnable> tasksAfterSend) {
        if (selfEntityId == Integer.MIN_VALUE || entityId != selfEntityId || status != 35) {
            return;
        }
        int mine = generation;
        int sequence = ++popSequence;
        popPending = false; // a new pop must never refill the previous pop's pending sample
        // Vanilla checks main hand before offhand. Do not invent an empty offhand on a main-hand pop.
        if (selectedSlot >= 0 && hands[selectedSlot] == TOTEM) {
            writeHand(selectedSlot, OTHER);
        } else if (selectedSlot >= 0 && hands[selectedSlot] == OTHER && hands[OFFHAND] == TOTEM) {
            writeHand(OFFHAND, OTHER);
        } else {
            writeHand(OFFHAND, UNKNOWN);
            if (selectedSlot >= 0) {
                writeHand(selectedSlot, UNKNOWN);
            }
        }
        tasksAfterSend.add(() -> player.acks().whenAcked(player.acks().sendMarker(), () -> {
            if (generation == mine && popSequence == sequence) {
                onPopAcked();
            }
        }));
    }

    void onPopAcked() {
        if (!timingAllowed()) {
            clearTiming();
            return;
        }
        syncTimingMode();
        long tick = player.clientTicks();
        long nanos = player.context().time().nanoTime();
        if (lastPopTick >= 0 && (timingTickEnd ? tick - lastPopTick > 1200
                : nanos - lastPopNanos > 60_000_000_000L)) {
            sampleCount = 0;
            sampleIndex = 0;
        }
        lastPopTick = tick;
        lastPopNanos = nanos;
        popPending = true;
        popTick = tick;
        popNanos = nanos;
    }

    private boolean timingAllowed() {
        if (isExempt()) {
            return false;
        }
        if (player.tickEndMode()) {
            return true;
        }
        long rtt = player.acks().rttMillis();
        long ackAge = player.context().time().nanoTime() - player.acks().lastAckedSentAtNanos();
        return checkLegacy && rtt >= 0 && rtt <= legacyMaxRtt && ackAge >= 0 && ackAge <= 5_000_000_000L;
    }

    private void syncTimingMode() {
        if (timingTickEnd != player.tickEndMode()) {
            clearTiming();
            timingTickEnd = player.tickEndMode();
        }
    }

    void onContents(WrapperPlayServerWindowItems contents, List<Runnable> tasksAfterSend) {
        if (contents.getWindowId() != 0) {
            return;
        }
        if (contents.getItems().size() != 46) {
            onReset();
            return;
        }
        byte[] values = new byte[hands.length];
        int[] expected = new int[hands.length];
        int mine = generation;
        for (int i = 0; i < hands.length; i++) {
            values[i] = classify(contents.getItems().get(HOTBAR_START + i));
            writeHand(i, UNKNOWN);
            expected[i] = revisions[i];
        }
        // One marker for the whole snapshot, not one per slot.
        tasksAfterSend.add(() -> player.acks().whenAcked(player.acks().sendMarker(), () -> {
            if (generation == mine) {
                for (int i = 0; i < hands.length; i++) {
                    if (revisions[i] == expected[i]) {
                        writeHand(i, values[i]);
                    }
                }
            }
        }));
    }

    void onSetSlot(int windowId, int slot, ItemStack item, List<Runnable> tasksAfterSend) {
        int index = windowId == 0 ? slot - HOTBAR_START
                : windowId == -2 ? (slot == 40 ? OFFHAND : slot >= 0 && slot <= 8 ? slot : -1) : -1;
        if (index >= 0 && index < hands.length) {
            queueHand(index, classify(item), tasksAfterSend);
        }
    }

    void onContainer(boolean open) {
        // No attempt to map arbitrary container layouts back to player-inventory slots.
        onReset();
        containerOpen = open;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.CLICK_WINDOW) {
            onClick(new WrapperPlayClientClickWindow(event));
        } else if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            onHeldSlot(new WrapperPlayClientHeldItemChange(event).getSlot());
        } else if (type == PacketType.Play.Client.CLOSE_WINDOW) {
            onClose(new WrapperPlayClientCloseWindow(event).getWindowId());
        } else if (type == PacketType.Play.Client.PLAYER_DIGGING
                && new WrapperPlayClientPlayerDigging(event).getAction() == DiggingAction.SWAP_ITEM_WITH_OFFHAND) {
            onSwapKey();
        }
    }

    void onClose(int windowId) {
        if (windowId != 0 || containerOpen) {
            onContainer(false);
        } else {
            lastEquipTick = -1L;
            lastEquipHand = -1;
        }
    }

    void onHeldSlot(int slot) {
        selectedSlot = slot >= 0 && slot <= 8 ? slot : -1;
        selectionRevision++;
    }

    void onServerHeldSlot(int slot, List<Runnable> tasksAfterSend) {
        onHeldSlot(-1);
        int revision = selectionRevision;
        int mine = generation;
        tasksAfterSend.add(() -> player.acks().whenAcked(player.acks().sendMarker(), () -> {
            if (generation == mine && selectionRevision == revision) {
                onHeldSlot(slot);
            }
        }));
    }

    void onSwapKey() {
        if (selectedSlot < 0 || containerOpen) {
            return;
        }
        byte main = hands[selectedSlot];
        byte off = hands[OFFHAND];
        writeHand(selectedSlot, off);
        writeHand(OFFHAND, main);
        if (main == TOTEM && off == OTHER) {
            onEquip(OFFHAND, "swap-key");
        }
    }

    void onClick(WrapperPlayClientClickWindow click) {
        if (click.getWindowId() != 0) {
            onContainer(true);
            return;
        }
        if (containerOpen) {
            // A player-window click proves the old container is no longer being used.
            onContainer(false);
        }
        WindowClickType type = click.getWindowClickType();
        Map<Integer, ItemStack> slots = click.getSlots() == null ? null : click.getSlots().orElse(null);
        Map<Integer, Optional<HashedStack>> hashes = click.getHashedSlots();
        // A destination transition counts only for the actual hotbar/F-key or pickup action.
        int target = type == WindowClickType.SWAP
                ? click.getButton() == 40 ? OFFHAND : click.getButton() >= 0 && click.getButton() <= 8
                ? click.getButton() : -1
                : type == WindowClickType.PICKUP ? click.getSlot() - HOTBAR_START : -1;
        boolean equipped = false;
        for (int i = 0; i < hands.length; i++) {
            int menuSlot = HOTBAR_START + i;
            byte next;
            if (slots != null && slots.containsKey(menuSlot)) {
                next = classify(slots.get(menuSlot));
            } else if (hashes != null && hashes.containsKey(menuSlot)) {
                Optional<HashedStack> stack = hashes.get(menuSlot);
                next = stack == null ? UNKNOWN : stack.isEmpty() ? OTHER
                        : stack.get().getItem() == ItemTypes.TOTEM_OF_UNDYING && stack.get().getCount() > 0
                        ? TOTEM : OTHER;
            } else {
                // Missing changes for a touched slot cannot be assumed to preserve its item.
                if ((slots == null || slots.isEmpty()) && (hashes == null || hashes.isEmpty())
                        || i == target || menuSlot == click.getSlot()) {
                    writeHand(i, UNKNOWN);
                }
                continue;
            }
            if (i == target && hands[i] == OTHER && next == TOTEM) {
                equipped = true;
            }
            writeHand(i, next);
        }
        if (equipped) {
            onEquip(target, type == WindowClickType.SWAP ? "inventory-swap" : "pickup");
        }
    }

    private static byte classify(ItemStack item) {
        return item == null ? UNKNOWN : !item.isEmpty() && item.getType() == ItemTypes.TOTEM_OF_UNDYING
                ? TOTEM : OTHER;
    }

    private void onEquip(int hand, String method) {
        if (!timingAllowed()) {
            clearTiming();
            return;
        }
        syncTimingMode();
        long tick = player.clientTicks();
        long nanos = player.context().time().nanoTime();
        double gap = timingTickEnd ? tick - lastEquipTick : (nanos - lastEquipNanos) / NANOS_PER_TICK;
        boolean usableGap = timingTickEnd || gap > 0.2; // ignore sub-10ms legacy packet bursts
        double gapLimit = timingTickEnd ? pairGap : pairGap + 0.35;
        boolean pair = checkPairs && method.equals("inventory-swap") && lastEquipTick >= 0
                && (hand == OFFHAND) != (lastEquipHand == OFFHAND) && usableGap && gap <= gapLimit;
        if (checkPairs && method.equals("inventory-swap") && lastEquipTick >= 0
                && (hand == OFFHAND) != (lastEquipHand == OFFHAND) && gap > gapLimit) {
            lowerBuffer();
        }
        lastEquipTick = method.equals("inventory-swap") ? tick : -1L;
        lastEquipHand = hand;
        lastEquipNanos = nanos;
        if (pair) {
            lastEquipTick = -1L; // each fill belongs to at most one pair
            if (raiseBuffer(1.0)) {
                violation("rapid hotbar/offhand totem pairs gap<=" + gapLimit + " clock="
                        + (timingTickEnd ? "client-ticks" : "legacy-50ms-units"));
                resetBuffer();
            }
        }
        if (hand != OFFHAND || !popPending) {
            return;
        }
        popPending = false; // one sample per acknowledged own pop, never per spammed click
        double delay = timingTickEnd ? tick - popTick : (nanos - popNanos) / NANOS_PER_TICK;
        if (!checkConsistency || delay < 0 || delay > reactionWindow || !timingTickEnd && delay <= 0.2) {
            sampleCount = 0;
            sampleIndex = 0;
            return;
        }
        if (!method.equals(sampleMethod)) {
            sampleMethod = method;
            sampleCount = 0;
            sampleIndex = 0;
        }
        delays[sampleIndex] = delay;
        sampleIndex = (sampleIndex + 1) % minSamples;
        sampleCount = Math.min(sampleCount + 1, minSamples);
        if (sampleCount < minSamples) {
            if (debugging()) {
                debug("totem refill method=" + method + " delay=" + delay + " samples=" + sampleCount
                        + "/" + minSamples + " clock=" + (timingTickEnd ? "client-ticks" : "legacy-50ms-units"));
            }
            return;
        }
        double mean = 0;
        for (int i = 0; i < minSamples; i++) {
            mean += delays[i];
        }
        mean /= minSamples;
        double variance = 0;
        for (int i = 0; i < minSamples; i++) {
            double offset = delays[i] - mean;
            variance += offset * offset;
        }
        double stddev = Math.sqrt(variance / minSamples);
        if (debugging()) {
            debug("totem refill method=" + method + " mean=" + mean + " stddev=" + stddev
                    + " samples=" + minSamples + " clock=" + (timingTickEnd ? "client-ticks" : "legacy-50ms-units"));
        }
        if (mean <= maxMean && stddev <= maxStddev) {
            if (raiseBuffer(1.5)) {
                violation("consistent totem refill method=" + method + " samples=" + minSamples
                        + " mean=" + mean + " stddev=" + stddev + " clock="
                        + (timingTickEnd ? "client-ticks" : "legacy-50ms-units"));
                resetBuffer();
            }
        } else if (!pair) {
            lowerBuffer();
        }
    }
}
