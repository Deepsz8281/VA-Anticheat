package dev.vaplus.anticheat.check.impl.inventory;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerOpenWindow;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.check.OutboundPacketCheck;
import dev.vaplus.anticheat.check.PacketCheck;
import dev.vaplus.anticheat.check.PositionCheck;
import dev.vaplus.anticheat.check.RotationCheck;
import dev.vaplus.anticheat.movement.LookChange;
import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.PoseState;

import java.util.Locale;

/**
 * InventoryMove (GuiMove): moving or turning while a container screen is open.
 *
 * <p>Vanilla fact (client source, UNCONFIRMED against a capture): opening any screen releases every
 * key binding and the mouse cursor, so the movement keys read as released and the camera does not turn
 * while a screen is open. A client that keeps moving or turning inside a GUI is modified.
 *
 * <p>Window tracking is private to this check. A server-opened container is only judged once the
 * outbound open packet is acknowledged (second marker after the packet); the player's own inventory
 * (window 0) is opened client-side without any packet, so it is inferred from a click on window 0 and
 * expires after {@code inferred-idle-ticks} without a click. Every close (either direction) ends the
 * judgement at once. The first {@code grace-ticks} after the proof are never judged.
 *
 * <p>Signals (each adds to one buffer):
 * <ul>
 *   <li>{@code keys}: 1.21.2+ clients only. The player-input packet is sent on change, so after a
 *       legitimate open the stored state turns all-released within a tick; forward/back/left/right
 *       still held after the grace is stuck input (weight 1.0; jump alone 0.5, e.g. a one-tick
 *       auto-jump).</li>
 *   <li>{@code rotation}: a non-teleport rotation change while the screen is open (for the inferred
 *       inventory only within {@code rotation-click-window-ticks} of a click).</li>
 *   <li>{@code motion}: clients without key states. Horizontal speed that does not decay
 *       (at least {@code motion-min-speed}, not below 95% of the previous tick) for
 *       {@code motion-sustain-ticks} ticks on the ground. Friction makes legitimate momentum decay by
 *       at least ~11% per tick (ice 0.89), so momentum, knockback and sliding never qualify.</li>
 * </ul>
 * Vehicles, recent dismounts, teleports, join/respawn/world change, game-mode exemptions and unknown
 * states are never judged.
 */
public final class InventoryMoveCheck extends Check
        implements PacketCheck, OutboundPacketCheck, ClientTickCheck, PositionCheck, RotationCheck {

    public static final int DEFAULT_GRACE_TICKS = 5;
    public static final int DEFAULT_INFERRED_IDLE_TICKS = 100;
    public static final int DEFAULT_ROTATION_CLICK_WINDOW_TICKS = 10;
    public static final double DEFAULT_MIN_ROTATION_DEGREES = 0.05;
    public static final double DEFAULT_MOTION_MIN_SPEED = 0.15;
    public static final int DEFAULT_MOTION_SUSTAIN_TICKS = 8;
    /** A tick qualifies as "not decaying" at this fraction of the previous speed or more. */
    static final double NON_DECAY_RATIO = 0.95;
    static final double KEY_WEIGHT = 1.0;
    static final double JUMP_WEIGHT = 0.5;
    static final int DISMOUNT_GRACE_TICKS = 20;
    static final int NO_WINDOW = -1;

    static final int SIGNAL_KEYS = 1;
    static final int SIGNAL_JUMP = 2;
    static final int SIGNAL_ROTATION = 4;
    static final int SIGNAL_MOTION = 8;

    private static final long MOTION_EXEMPTIONS = ExemptionCause.mask(ExemptionCause.KNOCKBACK,
            ExemptionCause.EXPLOSION, ExemptionCause.ELYTRA_BOOST, ExemptionCause.WIND_CHARGE,
            ExemptionCause.RIPTIDE, ExemptionCause.BOUNCE, ExemptionCause.LUNGE,
            ExemptionCause.ABILITY_CHANGE, ExemptionCause.ATTRIBUTE_CHANGE);

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("inventorymove", CheckCategory.INVENTORY)
            .displayName("InventoryMove")
            .description("Moving or turning while a container screen is open")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.INVENTORY)
                    .withBufferLimit(3.0)
                    .withBufferDecay(0.02)
                    .withBufferMax(8.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.VEHICLE,
                    ExemptionCause.PORTAL)
            .build();

    private boolean checkKeys = true;
    private boolean checkRotation = true;
    private boolean checkMotion = true;
    private int graceTicks = DEFAULT_GRACE_TICKS;
    private int inferredIdleTicks = DEFAULT_INFERRED_IDLE_TICKS;
    private int rotationClickWindow = DEFAULT_ROTATION_CLICK_WINDOW_TICKS;
    private double minRotation = DEFAULT_MIN_ROTATION_DEGREES;
    private double motionMinSpeed = DEFAULT_MOTION_MIN_SPEED;
    private int motionSustain = DEFAULT_MOTION_SUSTAIN_TICKS;

    // window tracking (owner thread)
    private int windowId = NO_WINDOW;
    private boolean inferred;
    private boolean proven;
    private long provenTick;
    private long lastClickTick;
    private int generation;

    // per-tick inputs gathered from hooks
    private boolean rotatedThisTick;
    private double speedThisTick;
    private boolean groundThisTick;

    // motion signal
    private int motionStreak;
    private double previousSpeed;

    public InventoryMoveCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        checkKeys = options.getBoolean("check-keys", true);
        checkRotation = options.getBoolean("check-rotation", true);
        checkMotion = options.getBoolean("check-motion", true);
        graceTicks = clamp(options.getInt("grace-ticks", DEFAULT_GRACE_TICKS), 0, 40, DEFAULT_GRACE_TICKS);
        inferredIdleTicks = clamp(options.getInt("inferred-idle-ticks", DEFAULT_INFERRED_IDLE_TICKS),
                20, 2000, DEFAULT_INFERRED_IDLE_TICKS);
        rotationClickWindow = clamp(options.getInt("rotation-click-window-ticks",
                DEFAULT_ROTATION_CLICK_WINDOW_TICKS), 1, 100, DEFAULT_ROTATION_CLICK_WINDOW_TICKS);
        motionSustain = clamp(options.getInt("motion-sustain-ticks", DEFAULT_MOTION_SUSTAIN_TICKS),
                3, 100, DEFAULT_MOTION_SUSTAIN_TICKS);
        minRotation = inRange(options.getDouble("min-rotation-degrees", DEFAULT_MIN_ROTATION_DEGREES),
                0.0, 30.0, DEFAULT_MIN_ROTATION_DEGREES);
        motionMinSpeed = inRange(options.getDouble("motion-min-speed", DEFAULT_MOTION_MIN_SPEED),
                0.05, 1.0, DEFAULT_MOTION_MIN_SPEED);
    }

    private static int clamp(int value, int min, int max, int fallback) {
        return value < min || value > max ? fallback : value;
    }

    private static double inRange(double value, double min, double max, double fallback) {
        return Double.isFinite(value) && value >= min && value <= max ? value : fallback;
    }

    @Override
    protected void onReset() {
        closeWindow();
        rotatedThisTick = false;
        speedThisTick = 0.0;
        groundThisTick = false;
    }

    // ---- packet hooks -------------------------------------------------------------------------

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.CLICK_WINDOW) {
            onClick(new WrapperPlayClientClickWindow(event).getWindowId(), player.clientTicks());
        } else if (type == PacketType.Play.Client.CLOSE_WINDOW) {
            closeWindow();
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.OPEN_WINDOW) {
            int id = new WrapperPlayServerOpenWindow(event).getContainerId();
            int expected = windowOpened(id);
            // Second marker: after it is echoed the client has processed the open packet.
            player.acks().bracket(event.getTasksAfterSend(), null,
                    () -> openAcknowledged(expected, player.clientTicks()));
        } else if (type == PacketType.Play.Server.CLOSE_WINDOW) {
            closeWindow();
        }
    }

    @Override
    public void onPosition(MovementUpdate update) {
        if (update.teleport() || update.firstPosition() || !update.positionChanged()) {
            return;
        }
        int ticks = Math.max(1, update.elapsedTicks());
        speedThisTick = update.horizontalDistance() / ticks;
        groundThisTick = update.fromGround() && update.toGround();
    }

    @Override
    public void onRotation(LookChange change) {
        if (change.teleport()) {
            return;
        }
        double delta = Math.abs(change.wrappedDeltaYaw()) + Math.abs(change.deltaPitch());
        if (delta > minRotation) {
            rotatedThisTick = true;
        }
    }

    @Override
    public void onClientTick() {
        long tick = player.clientTicks() - 1L;
        boolean rotated = rotatedThisTick;
        double speed = speedThisTick;
        boolean ground = groundThisTick;
        rotatedThisTick = false;
        speedThisTick = 0.0;
        groundThisTick = false;
        if (windowId == NO_WINDOW) {
            return;
        }
        PoseState pose = player.pose();
        boolean excluded = isExempt() || player.vehicle().inVehicle()
                || player.vehicle().ticksSinceDismount() < DISMOUNT_GRACE_TICKS;
        boolean motionUsable = ground && !pose.gliding() && !pose.swimming()
                && !player.exemptions().anyActive(MOTION_EXEMPTIONS);
        boolean inputKnown = pose.inputKnown();
        boolean moveKeys = inputKnown && (pose.forward() || pose.backward() || pose.left() || pose.right());
        evaluate(tick, excluded, inputKnown, moveKeys, inputKnown && pose.jump(), rotated, speed, motionUsable);
    }

    // ---- window tracking (package-private for tests) ---------------------------------------------

    /** Outbound open packet: returns the generation the acknowledgement must still match. */
    int windowOpened(int id) {
        resetTracking();
        generation++;
        windowId = id;
        return generation;
    }

    void openAcknowledged(int expectedGeneration, long tick) {
        if (expectedGeneration == generation && windowId != NO_WINDOW && !proven) {
            proven = true;
            provenTick = tick;
        }
    }

    void onClick(int clickedWindow, long tick) {
        if (windowId == NO_WINDOW) {
            if (clickedWindow == 0) {
                windowId = 0;
                inferred = true;
                proven = true;
                provenTick = tick;
                lastClickTick = tick;
            }
            return;
        }
        if (clickedWindow != windowId) {
            return;
        }
        if (!proven) {
            // The client clicked inside this window, so it is demonstrably open.
            proven = true;
            provenTick = tick;
        }
        lastClickTick = tick;
    }

    void closeWindow() {
        generation++;
        windowId = NO_WINDOW;
        resetTracking();
    }

    private void resetTracking() {
        inferred = false;
        proven = false;
        motionStreak = 0;
        previousSpeed = 0.0;
    }

    int windowId() {
        return windowId;
    }

    boolean windowProven() {
        return windowId != NO_WINDOW && proven;
    }

    // ---- decision ----------------------------------------------------------------------------------

    /** Judges one finished client tick. All inputs are plain values so tests need no packets. */
    void evaluate(long tick, boolean excluded, boolean inputKnown, boolean moveKeys, boolean jumpKey,
                  boolean rotated, double speed, boolean motionUsable) {
        if (windowId == NO_WINDOW) {
            return;
        }
        if (inferred && tick - lastClickTick > inferredIdleTicks) {
            closeWindow();
            return;
        }
        if (excluded || !proven || tick - provenTick < graceTicks) {
            motionStreak = 0;
            previousSpeed = 0.0;
            return;
        }
        double weight = 0.0;
        int signals = 0;
        if (checkKeys && inputKnown) {
            if (moveKeys) {
                weight += KEY_WEIGHT;
                signals |= SIGNAL_KEYS;
            } else if (jumpKey) {
                weight += JUMP_WEIGHT;
                signals |= SIGNAL_JUMP;
            }
        }
        if (checkRotation && rotated && (!inferred || tick - lastClickTick <= rotationClickWindow)) {
            weight += 1.0;
            signals |= SIGNAL_ROTATION;
        }
        if (checkMotion && !inputKnown) {
            if (motionUsable && speed >= motionMinSpeed && speed >= previousSpeed * NON_DECAY_RATIO) {
                motionStreak++;
            } else {
                motionStreak = Math.max(0, motionStreak - 2);
            }
            previousSpeed = motionUsable ? speed : 0.0;
            if (motionStreak >= motionSustain) {
                weight += 1.0;
                signals |= SIGNAL_MOTION;
            }
        } else {
            motionStreak = 0;
            previousSpeed = 0.0;
        }
        if (weight <= 0.0) {
            lowerBuffer();
            return;
        }
        if (raiseBuffer(weight)) {
            violation(details(signals, speed));
            resetBuffer();
        } else if (debugging()) {
            debug("signals=" + signals + " buffer=" + String.format(Locale.ROOT, "%.2f", buffer()));
        }
    }

    private String details(int signals, double speed) {
        StringBuilder text = new StringBuilder(64);
        text.append("window=").append(windowId).append(inferred ? " (inferred)" : "");
        if ((signals & SIGNAL_KEYS) != 0) {
            PoseState pose = player.pose();
            text.append(" keys=")
                    .append(pose.forward() ? "W" : "").append(pose.left() ? "A" : "")
                    .append(pose.backward() ? "S" : "").append(pose.right() ? "D" : "")
                    .append(pose.jump() ? "+jump" : "").append(pose.sprintKey() ? "+sprint" : "");
        }
        if ((signals & SIGNAL_JUMP) != 0) {
            text.append(" jump-held");
        }
        if ((signals & SIGNAL_ROTATION) != 0) {
            text.append(" rotated");
        }
        if ((signals & SIGNAL_MOTION) != 0) {
            text.append(" sustained-speed=").append(String.format(Locale.ROOT, "%.3f", speed))
                    .append(" streak=").append(motionStreak);
        }
        return text.toString();
    }
}
