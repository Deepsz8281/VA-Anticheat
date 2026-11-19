package dev.vaplus.anticheat.check.impl.packet;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClientStatus;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientKeepAlive;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerAbilities;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChangeGameState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerKeepAlive;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateHealth;
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
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.MovementState;
import dev.vaplus.anticheat.player.state.PoseState;
import dev.vaplus.anticheat.world.BlockInteraction;

import java.util.List;
import java.util.Locale;

/**
 * BadPackets: protocol-level sanity that vanilla tolerates but no real client produces.
 *
 * <h2>Original signals</h2>
 * <ul>
 *   <li>{@code rotation}: pitch outside [-90, 90] or non-finite values.</li>
 *   <li>{@code rate}: more movement packets per second than any client sends (default 60).</li>
 *   <li>{@code cursor}: a placement hit point outside the block box ([-0.05, 1.05]).</li>
 *   <li>{@code dig}: {@code FINISHED_DIGGING}/{@code CANCELLED_DIGGING} without a matching start.</li>
 *   <li>{@code slot}: a hotbar selection outside 0..8.</li>
 *   <li>{@code pose}: sprinting and sneaking at the same time.</li>
 * </ul>
 *
 * <h2>Grim-equivalent signals</h2>
 * The letters name the Grim check with the same idea; the logic is our own.
 * <ul>
 *   <li>{@code duplicate-slot} (A): a hotbar selection of the slot that is already selected.
 *       The client only sends the slot when it changed.</li>
 *   <li>{@code sleep} (C): "leave bed" without being in bed. The bed state comes from the pose the
 *       server sends for the player's own entity; a short grace after waking up covers latency.</li>
 *   <li>{@code movement-reminder} (E): more than 19 movement packets in a row without a position.
 *       The client sends its position at least every 20 ticks (20 for 1.8 and older).</li>
 *   <li>{@code duplicate-sprint} / {@code duplicate-sneak} (F, G): start sprinting while already
 *       sprinting, stop while not sprinting (same for sneaking). The client only sends changes.</li>
 *   <li>{@code sequence} (H, 1.19+ clients): block placements, item uses and dig start/finish carry
 *       a client counter that only grows; a repeated or smaller value, or a non-zero value on a dig
 *       cancel, is crafted. Only going backwards is judged, so packets other plugins drop can never
 *       cause a flag.</li>
 *   <li>{@code abilities} (I): the client claims to fly although the server does not allow flight.</li>
 *   <li>{@code use-rotation} (J, 1.21.2+ clients): the rotation inside a use-item packet differs from
 *       the rotation the same tick's movement packet reports.</li>
 *   <li>{@code spectate} (K): spectating an entity outside spectator mode.</li>
 *   <li>{@code respawn} (M): respawn request while alive (end credits and a grace after the last
 *       respawn are excused).</li>
 *   <li>{@code keepalive} (O): a keep-alive answer with an id the server never sent.</li>
 *   <li>{@code entity-action} (Q): an entity action for another entity, a horse jump boost outside
 *       [-100, 100], or a boost on an action that has none.</li>
 *   <li>{@code position-starvation} (R): answering latency markers for more than
 *       {@code starvation-millis} of client time without sending a single position (freeze / blink
 *       that keeps the connection alive). Experimental.</li>
 *   <li>{@code slow-move} (V, 1.21.2+ clients): a position packet before the 20-tick reminder that
 *       moved less than the 0.0002 block send threshold. Experimental.</li>
 *   <li>{@code input-actions} (X, 1.21.2+ clients): two sprint state changes in one client tick.</li>
 *   <li>{@code duplicate-input} (Z, 1.21.2+ clients): two player-input packets in one client tick.</li>
 *   <li>{@code duplicate-look} (Grim AimDuplicateLook, 1.21.2+ clients): a rotation packet that
 *       repeats the previous rotation exactly. The client only sends its rotation when it changed;
 *       teleport answers, server-set rotations and riders are excused.</li>
 *   <li>{@code yaw-wrap} (Grim AimModulo360): a yaw jump of more than 320 degrees right after a
 *       calm tick (below 30 degrees), while the yaw stays within +-360. Aim modules that wrap the
 *       yaw instead of turning produce it; a mouse does not.</li>
 * </ul>
 * Not ported: Grim's B and N (empty classes there; ignored teleports are held at the safe position
 * by the movement gate here), P (invalid window clicks: the {@code crasher} check), S and U (packets
 * only pre-1.9 / pre-1.17 clients send, translated away on a 1.21 server).
 *
 * <p>Deterministic signals add 2.0 to the buffer (immediate violation); the experimental ones
 * (sleep, respawn, position-starvation, slow-move) add 1.0 and need a repeat.
 */
public final class BadPacketsCheck extends Check implements PacketCheck, BlockCheck, ClientTickCheck, OutboundPacketCheck {

    public static final int DEFAULT_MAX_PACKETS_PER_SECOND = 60;
    /** Tolerance around the unit block box for the placement cursor. */
    public static final double CURSOR_TOLERANCE = 0.05;
    private static final long RATE_WINDOW_NANOS = 1_000_000_000L;
    /** Client time without a position that counts as starvation. */
    public static final long DEFAULT_STARVATION_MILLIS = 2000L;
    /** Grace after a state change (waking up, respawning), plus twice the round trip. */
    static final long STATE_GRACE_MILLIS = 1500L;
    /** Vanilla: a position is sent at least every 20 ticks. */
    static final int MAX_NO_POSITION_PACKETS = 19;
    /** Vanilla 1.18.2+: a position is only sent when it moved more than 2.0E-4 blocks. */
    static final double MOVE_THRESHOLD_SQUARED = 4.0E-8;
    static final int MAX_KEEPALIVES = 32;
    static final double SURE = 2.0;
    static final double LIKELY = 1.0;

    private static final long MOVE_EXEMPT = ExemptionCause.mask(ExemptionCause.JOIN, ExemptionCause.RESPAWN,
            ExemptionCause.WORLD_CHANGE, ExemptionCause.TELEPORT, ExemptionCause.VEHICLE);

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("badpackets", CheckCategory.PACKET)
            .displayName("BadPackets")
            .description("Impossible or duplicate packets, rotations, sequences and packet rates")
            .defaults(CheckDefaults.of(CheckCategory.PACKET)
                    .withBufferLimit(1.0)
                    .withBufferDecay(0.05)
                    .withBufferMax(4.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT)
            .build();

    private boolean checkPitch = true;
    private boolean checkCursor = true;
    private boolean checkDigSequence = true;
    private boolean checkPose = true;
    private boolean checkSlot = true;
    private int maxPacketsPerSecond = DEFAULT_MAX_PACKETS_PER_SECOND;
    private boolean checkDuplicateSlot = true;
    private boolean checkSleep = true;
    private boolean checkMovementReminder = true;
    private boolean checkDuplicateSprint = true;
    private boolean checkDuplicateSneak = true;
    private boolean checkSequence = true;
    private boolean checkAbilities = true;
    private boolean checkUseRotation = true;
    private boolean checkSpectate = true;
    private boolean checkRespawn = true;
    private boolean checkKeepAlive = true;
    private boolean checkEntityAction = true;
    private boolean checkStarvation = true;
    private long starvationMillis = DEFAULT_STARVATION_MILLIS;
    private boolean checkSlowMove = true;
    private boolean checkInputActions = true;
    private boolean checkDuplicateInput = true;

    private long rateWindowStart;
    private int rateWindowCount;

    private boolean digActive;
    private int digX;
    private int digY;
    private int digZ;

    // own entity (join packet)
    private int selfId = Integer.MIN_VALUE;

    // A
    private int lastSlot = -1;
    // C
    private boolean inBed;
    private long wokeAtNanos = Long.MIN_VALUE / 2;
    // E
    private int noPositionPackets;
    // F, G
    private boolean lastSprinting;
    private boolean sprintKnown;
    private boolean lastSneaking;
    private boolean sneakKnown;
    // H
    private int lastSequence;
    private boolean sequenceRestart;
    // J
    private boolean useRotationPending;
    private float useYaw;
    private float usePitch;
    private int useMismatches;
    // M
    private boolean dead;
    private long aliveAtNanos = Long.MIN_VALUE / 2;
    private int creditsRespawns;
    // O
    private final long[] keepAlives = new long[MAX_KEEPALIVES];
    private int keepAliveCount;
    private boolean keepAliveTracked;
    // R
    private long starvationClock = Long.MIN_VALUE;
    private long starvationWall;
    private int starvationPositions;
    // V
    private int ticksSincePosition = Integer.MAX_VALUE / 2;
    private boolean slowMoveBaseline;
    private int teleportSkip;
    // aim
    private boolean checkDuplicateLook = true;
    private boolean checkYawWrap = true;
    private float lastDeltaYaw;
    private long rotationSetAtNanos = Long.MIN_VALUE / 2;
    private boolean rotationAfterTeleport;
    // X, Z
    private int sprintActionsThisTick;
    private int inputsThisTick;

    public BadPacketsCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        checkPitch = options.getBoolean("check-pitch", true);
        checkCursor = options.getBoolean("check-cursor", true);
        checkDigSequence = options.getBoolean("check-dig-sequence", true);
        checkPose = options.getBoolean("check-sprint-sneak", true);
        checkSlot = options.getBoolean("check-held-slot", true);
        int configured = options.getInt("max-movement-packets-per-second", DEFAULT_MAX_PACKETS_PER_SECOND);
        maxPacketsPerSecond = Math.max(20, Math.min(400, configured));
        checkDuplicateSlot = options.getBoolean("check-duplicate-slot", true);
        checkSleep = options.getBoolean("check-sleep", true);
        checkMovementReminder = options.getBoolean("check-movement-reminder", true);
        checkDuplicateSprint = options.getBoolean("check-duplicate-sprint", true);
        checkDuplicateSneak = options.getBoolean("check-duplicate-sneak", true);
        checkSequence = options.getBoolean("check-sequence", true);
        checkAbilities = options.getBoolean("check-abilities", true);
        checkUseRotation = options.getBoolean("check-use-rotation", true);
        checkSpectate = options.getBoolean("check-spectate", true);
        checkRespawn = options.getBoolean("check-respawn", true);
        checkKeepAlive = options.getBoolean("check-keepalive", true);
        checkEntityAction = options.getBoolean("check-entity-action", true);
        checkStarvation = options.getBoolean("check-position-starvation", true);
        long starvation = options.getInt("starvation-millis", (int) DEFAULT_STARVATION_MILLIS);
        starvationMillis = starvation >= 1000L && starvation <= 30000L ? starvation : DEFAULT_STARVATION_MILLIS;
        checkSlowMove = options.getBoolean("check-slow-move", true);
        checkInputActions = options.getBoolean("check-input-actions", true);
        checkDuplicateInput = options.getBoolean("check-duplicate-input", true);
        checkDuplicateLook = options.getBoolean("check-duplicate-look", true);
        checkYawWrap = options.getBoolean("check-yaw-wrap", true);
    }

    @Override
    protected void onReset() {
        digActive = false;
        rateWindowCount = 0;
        rateWindowStart = 0L;
        onNewLife(true);
    }

    // ================================================================== inbound

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            onHeldSlot(new WrapperPlayClientHeldItemChange(event).getSlot());
        } else if (type == PacketType.Play.Client.PLAYER_FLYING || type == PacketType.Play.Client.PLAYER_ROTATION) {
            onMovementPacket(event, false);
        } else if (type == PacketType.Play.Client.PLAYER_POSITION
                || type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            onMovementPacket(event, true);
        } else if (type == PacketType.Play.Client.ENTITY_ACTION) {
            WrapperPlayClientEntityAction packet = new WrapperPlayClientEntityAction(event);
            onEntityAction(packet.getAction(), packet.getEntityId(), packet.getJumpBoost());
        } else if (type == PacketType.Play.Client.PLAYER_ABILITIES) {
            onAbilities(new WrapperPlayClientPlayerAbilities(event).isFlying());
        } else if (type == PacketType.Play.Client.SPECTATE) {
            onSpectate();
        } else if (type == PacketType.Play.Client.CLIENT_STATUS) {
            if (new WrapperPlayClientClientStatus(event).getAction() == WrapperPlayClientClientStatus.Action.PERFORM_RESPAWN) {
                onRespawnRequest();
            }
        } else if (type == PacketType.Play.Client.KEEP_ALIVE) {
            onKeepAliveReceived(new WrapperPlayClientKeepAlive(event).getId());
        } else if (type == PacketType.Play.Client.USE_ITEM) {
            if (checkUseRotation && player.tickEndMode()
                    && player.clientVersion().isNewerThanOrEquals(ClientVersion.V_1_21)) {
                WrapperPlayClientUseItem packet = new WrapperPlayClientUseItem(event);
                onUseItemRotation(packet.getYaw(), packet.getPitch());
            }
        } else if (type == PacketType.Play.Client.PLAYER_INPUT) {
            onPlayerInput();
        } else if (type == PacketType.Play.Client.TELEPORT_CONFIRM) {
            onTeleportConfirm();
        } else if (type == PacketType.Play.Client.PONG) {
            onMarkerAnswered();
        } else if (type == PacketType.Play.Client.VEHICLE_MOVE || type == PacketType.Play.Client.STEER_VEHICLE) {
            if (player.vehicle().inVehicle()) {
                starvationPositions++;
            }
        }
    }

    private void onMovementPacket(PacketReceiveEvent event, boolean hasPosition) {
        WrapperPlayClientPlayerFlying packet = null;
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_FLYING
                && event.getPacketType() != PacketType.Play.Client.PLAYER_POSITION) {
            packet = new WrapperPlayClientPlayerFlying(event);
            Location location = packet.getLocation();
            float pitch = location.getPitch();
            float yaw = location.getYaw();
            if (checkPitch && (!Float.isFinite(pitch) || !Float.isFinite(yaw) || pitch < -90.0F || pitch > 90.0F)) {
                flag(SURE, "impossible rotation pitch=" + pitch + " yaw=" + yaw);
                return;
            }
            onRotation(yaw, pitch);
        }
        countRate();
        if (hasPosition) {
            if (packet == null) {
                packet = new WrapperPlayClientPlayerFlying(event);
            }
            Location location = packet.getLocation();
            onPosition(location.getX(), location.getY(), location.getZ());
        } else {
            onNoPosition(event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION);
        }
    }

    private void countRate() {
        if (maxPacketsPerSecond <= 0) {
            return;
        }
        long now = player.context().time().nanoTime();
        if (rateWindowStart == 0L || now - rateWindowStart >= RATE_WINDOW_NANOS) {
            rateWindowStart = now;
            rateWindowCount = 0;
        }
        rateWindowCount++;
        if (rateWindowCount > maxPacketsPerSecond) {
            rateWindowCount = 0;
            rateWindowStart = now;
            flag(LIKELY, "movement-rate>" + maxPacketsPerSecond + "/s");
        }
    }

    /** Hotbar selection: outside 0..8, or the slot that is already selected (A). */
    void onHeldSlot(int slot) {
        if (checkSlot && (slot < 0 || slot > 8)) {
            flag(SURE, "held-slot=" + slot);
        } else if (checkDuplicateSlot && slot == lastSlot) {
            flag(SURE, "duplicate-slot=" + slot);
        }
        lastSlot = slot;
    }

    /** A position packet (E, R, V). Called before the pipeline applies it. */
    void onPosition(double x, double y, double z) {
        noPositionPackets = 0;
        if (!player.vehicle().inVehicle()) {
            starvationPositions++;
        }
        if (teleportSkip > 0) {
            teleportSkip--;
        } else if (checkSlowMove && slowMoveBaseline && player.tickEndMode() && player.movement().positionKnown()
                && ticksSincePosition < MAX_NO_POSITION_PACKETS && !player.exemptions().anyActive(MOVE_EXEMPT)) {
            MovementState movement = player.movement();
            double dx = x - movement.x();
            double dy = y - movement.y();
            double dz = z - movement.z();
            double squared = dx * dx + dy * dy + dz * dz;
            if (squared <= MOVE_THRESHOLD_SQUARED) {
                flag(LIKELY, "slow-move delta=" + String.format(Locale.ROOT, "%.6f", Math.sqrt(squared))
                        + " ticks=" + ticksSincePosition);
            }
        }
        // the client's reference position is only ours again after the post-teleport positions
        slowMoveBaseline = teleportSkip == 0;
        ticksSincePosition = 0;
    }

    /** A movement packet without a position (E). */
    void onNoPosition(boolean rotation) {
        if (player.vehicle().inVehicle()) {
            noPositionPackets = 0;
            if (rotation) {
                starvationPositions++;
            }
            return;
        }
        if (!checkMovementReminder || player.exemptions().anyActive(MOVE_EXEMPT)) {
            noPositionPackets = 0;
            return;
        }
        int limit = player.clientVersion().isOlderThanOrEquals(ClientVersion.V_1_8)
                ? MAX_NO_POSITION_PACKETS + 1 : MAX_NO_POSITION_PACKETS;
        if (++noPositionPackets > limit) {
            flag(SURE, "movement-reminder packets=" + noPositionPackets + " without a position");
            noPositionPackets = 0;
        }
    }

    /** A rotation-bearing movement packet (aim signals). Called before the pipeline applies it. */
    void onRotation(float yaw, float pitch) {
        MovementState movement = player.movement();
        boolean excused = rotationAfterTeleport || player.vehicle().inVehicle() || !movement.rotationKnown()
                || isExempt() || player.exemptions().isActive(ExemptionCause.VEHICLE)
                || (player.context().time().nanoTime() - rotationSetAtNanos) / 1_000_000L <= graceMillis();
        rotationAfterTeleport = false;
        float deltaYaw = yaw - movement.yaw();
        if (excused) {
            lastDeltaYaw = deltaYaw;
            return;
        }
        if (checkDuplicateLook && player.tickEndMode() && yaw == movement.yaw() && pitch == movement.pitch()) {
            flag(SURE, "duplicate-look yaw=" + yaw + " pitch=" + pitch);
        }
        if (checkYawWrap && Math.abs(yaw) < 360.0F && Math.abs(deltaYaw) > 320.0F && Math.abs(lastDeltaYaw) < 30.0F) {
            flag(LIKELY, "yaw-wrap delta=" + deltaYaw + " previous=" + lastDeltaYaw);
        }
        lastDeltaYaw = deltaYaw;
    }

    void onTeleportConfirm() {
        rotationAfterTeleport = true;
        // the confirming position and the one after it are compared against a position the
        // client does not use as its own reference
        teleportSkip = 2;
        slowMoveBaseline = false;
        noPositionPackets = 0;
    }

    /** Entity actions: sleep (C), duplicate sprint/sneak (F, G), per-tick sprint changes (X), fields (Q). */
    void onEntityAction(WrapperPlayClientEntityAction.Action action, int entityId, int boost) {
        if (checkEntityAction) {
            boolean wrongEntity = selfId != Integer.MIN_VALUE && entityId != selfId;
            boolean badBoost = boost < -100 || boost > 100
                    || (action != WrapperPlayClientEntityAction.Action.START_JUMPING_WITH_HORSE && boost != 0);
            if (wrongEntity || badBoost) {
                flag(SURE, "entity-action action=" + action + " entity=" + entityId + " self=" + selfId + " boost=" + boost);
                return;
            }
        }
        switch (action) {
            case START_SPRINTING, STOP_SPRINTING -> onSprintAction(action == WrapperPlayClientEntityAction.Action.START_SPRINTING);
            case START_SNEAKING, STOP_SNEAKING -> onSneakAction(action == WrapperPlayClientEntityAction.Action.START_SNEAKING);
            case LEAVE_BED -> onLeaveBed();
            default -> {
            }
        }
    }

    private void onSprintAction(boolean start) {
        if (checkDuplicateSprint && sprintKnown && lastSprinting == start) {
            flag(SURE, "duplicate-sprint " + (start ? "start" : "stop"));
        }
        lastSprinting = start;
        sprintKnown = true;
        if (checkInputActions && player.tickEndMode() && !player.vehicle().inVehicle()) {
            if (++sprintActionsThisTick > 1) {
                flag(SURE, "input-actions sprint changes=" + sprintActionsThisTick + " in one tick");
            }
        }
    }

    private void onSneakAction(boolean start) {
        if (checkDuplicateSneak && sneakKnown && lastSneaking == start) {
            flag(SURE, "duplicate-sneak " + (start ? "start" : "stop"));
        }
        lastSneaking = start;
        sneakKnown = true;
    }

    private void onLeaveBed() {
        if (!checkSleep || inBed || !joined() || isExempt()) {
            return;
        }
        long sinceWake = (player.context().time().nanoTime() - wokeAtNanos) / 1_000_000L;
        if (sinceWake > graceMillis()) {
            flag(LIKELY, "leave-bed while not sleeping");
        }
    }

    /** Client claims flight (I). */
    void onAbilities(boolean flying) {
        if (!checkAbilities || !flying || !joined() || player.abilities().flightAllowed()) {
            return;
        }
        if (player.exemptions().isActive(ExemptionCause.ABILITY_CHANGE)
                || player.exemptions().isActive(ExemptionCause.GAMEMODE_CHANGE) || isExempt()) {
            return;
        }
        flag(SURE, "abilities flying without flight permission (gamemode=" + player.abilities().gameMode() + ")");
    }

    /** Spectate packet (K). */
    void onSpectate() {
        if (!checkSpectate || !joined() || player.abilities().gameMode() == GameMode.SPECTATOR
                || player.exemptions().isActive(ExemptionCause.GAMEMODE_CHANGE) || isExempt()) {
            return;
        }
        flag(SURE, "spectate outside spectator mode (gamemode=" + player.abilities().gameMode() + ")");
    }

    /** Respawn request (M). */
    void onRespawnRequest() {
        if (!checkRespawn || !joined()) {
            return;
        }
        if (creditsRespawns > 0) {
            creditsRespawns--;
            return;
        }
        long sinceAlive = (player.context().time().nanoTime() - aliveAtNanos) / 1_000_000L;
        if (!dead && sinceAlive > graceMillis()) {
            flag(LIKELY, "respawn while alive");
        }
    }

    /** Keep-alive answer (O). */
    void onKeepAliveReceived(long id) {
        if (!checkKeepAlive || !keepAliveTracked) {
            // nothing recorded yet (plugin enabled mid-session): the answer may be for an older id
            return;
        }
        for (int i = 0; i < keepAliveCount; i++) {
            if (keepAlives[i] == id) {
                // answered: drop it and every older one (they will not be answered any more)
                int remaining = keepAliveCount - i - 1;
                System.arraycopy(keepAlives, i + 1, keepAlives, 0, remaining);
                keepAliveCount = remaining;
                return;
            }
        }
        flag(SURE, "keepalive id=" + id + " was never sent");
    }

    /** Rotation inside a use-item packet (J); compared at the end of the tick. */
    void onUseItemRotation(float yaw, float pitch) {
        if (useRotationPending && (yaw != useYaw || pitch != usePitch)) {
            useMismatches++;
        }
        useRotationPending = true;
        useYaw = yaw;
        usePitch = pitch;
    }

    /** Player-input packet (Z). */
    void onPlayerInput() {
        if (checkDuplicateInput && player.tickEndMode() && ++inputsThisTick > 1) {
            flag(SURE, "duplicate-input packets=" + inputsThisTick + " in one tick");
        }
    }

    /** A latency marker answer: closes a position-starvation window when it is long enough (R). */
    void onMarkerAnswered() {
        if (!checkStarvation) {
            return;
        }
        long clock = player.acks().lastAckedSentAtNanos();
        long wall = player.context().time().nanoTime();
        if (starvationClock == Long.MIN_VALUE || !starvationJudgeable()) {
            restartStarvation(clock, wall);
            return;
        }
        long clientMillis = (clock - starvationClock) / 1_000_000L;
        long wallMillis = (wall - starvationWall) / 1_000_000L;
        if (clientMillis <= starvationMillis || wallMillis <= starvationMillis) {
            return;
        }
        if (starvationPositions == 0) {
            flag(LIKELY, "position-starvation for " + clientMillis + "ms while answering markers");
        } else {
            lowerBuffer();
        }
        restartStarvation(clock, wall);
    }

    private boolean starvationJudgeable() {
        return !dead && !isExempt() && !player.exemptions().anyActive(MOVE_EXEMPT)
                && player.abilities().gameMode() != GameMode.SPECTATOR;
    }

    private void restartStarvation(long clock, long wall) {
        starvationClock = clock;
        starvationWall = wall;
        starvationPositions = 0;
    }

    // ================================================================== outbound

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.PLAYER_ROTATION || type == PacketType.Play.Server.FACE_PLAYER
                || type == PacketType.Play.Server.PLAYER_POSITION_AND_LOOK) {
            onRotationSet();
        } else if (type == PacketType.Play.Server.KEEP_ALIVE) {
            onKeepAliveSent(new WrapperPlayServerKeepAlive(event).getId());
        } else if (type == PacketType.Play.Server.JOIN_GAME) {
            onJoin(new WrapperPlayServerJoinGame(event).getEntityId());
        } else if (type == PacketType.Play.Server.RESPAWN) {
            onRespawn();
        } else if (type == PacketType.Play.Server.UPDATE_HEALTH) {
            onHealth(new WrapperPlayServerUpdateHealth(event).getHealth());
        } else if (type == PacketType.Play.Server.CHANGE_GAME_STATE) {
            WrapperPlayServerChangeGameState packet = new WrapperPlayServerChangeGameState(event);
            if (packet.getReason() == WrapperPlayServerChangeGameState.Reason.WIN_GAME) {
                onWinGame(packet.getValue());
            }
        } else if (type == PacketType.Play.Server.ENTITY_METADATA && selfId != Integer.MIN_VALUE && checkSleep) {
            WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(event);
            if (packet.getEntityId() == selfId) {
                onOwnMetadata(packet.getEntityMetadata());
            }
        }
    }

    /** The server turned the player (teleport, set rotation, face): the next rotations may repeat. */
    void onRotationSet() {
        rotationSetAtNanos = player.context().time().nanoTime();
    }

    void onKeepAliveSent(long id) {
        keepAliveTracked = true;
        if (keepAliveCount == MAX_KEEPALIVES) {
            System.arraycopy(keepAlives, 1, keepAlives, 0, MAX_KEEPALIVES - 1);
            keepAliveCount--;
        }
        keepAlives[keepAliveCount++] = id;
    }

    void onJoin(int entityId) {
        selfId = entityId;
        lastSlot = -1;
        lastSequence = 0;
        sequenceRestart = false;
        onNewLife(true);
    }

    void onRespawn() {
        // same-dimension respawns keep the client's world and its sequence counter; others restart it
        sequenceRestart = true;
        onNewLife(false);
    }

    /** A new client player entity: its sprint/sneak/bed state starts over. */
    private void onNewLife(boolean join) {
        sprintKnown = false;
        sneakKnown = false;
        inBed = false;
        noPositionPackets = 0;
        slowMoveBaseline = false;
        useRotationPending = false;
        useMismatches = 0;
        starvationClock = Long.MIN_VALUE;
        if (dead || join) {
            aliveAtNanos = player.context().time().nanoTime();
        }
        dead = false;
    }

    void onHealth(float health) {
        boolean nowDead = health <= 0.0F;
        if (dead && !nowDead) {
            aliveAtNanos = player.context().time().nanoTime();
        }
        dead = nowDead;
    }

    void onWinGame(float value) {
        // 0: respawn at once, 1: roll the credits first; both end with a respawn request while alive
        if (value == 0.0F || value == 1.0F) {
            creditsRespawns++;
        }
    }

    void onOwnMetadata(List<EntityData<?>> entries) {
        for (EntityData<?> entry : entries) {
            if (entry.getType() == EntityDataTypes.ENTITY_POSE && entry.getValue() instanceof EntityPose pose) {
                onOwnPose(pose == EntityPose.SLEEPING);
            }
        }
    }

    void onOwnPose(boolean sleeping) {
        if (inBed && !sleeping) {
            wokeAtNanos = player.context().time().nanoTime();
        }
        inBed = sleeping;
    }

    // ================================================================== block interactions

    @Override
    public void onBlockInteraction(BlockInteraction interaction) {
        if (checkCursor && interaction.kind() == BlockInteraction.Kind.PLACE) {
            if (!withinUnitBox(interaction.cursorX()) || !withinUnitBox(interaction.cursorY())
                    || !withinUnitBox(interaction.cursorZ())) {
                flag(LIKELY, "placement-cursor=" + interaction.cursorX() + "," + interaction.cursorY()
                        + "," + interaction.cursorZ());
            }
        }
        checkSequence(interaction);
        if (!checkDigSequence) {
            return;
        }
        switch (interaction.kind()) {
            case DIG_START -> {
                digActive = true;
                digX = interaction.blockX();
                digY = interaction.blockY();
                digZ = interaction.blockZ();
            }
            case DIG_CANCEL, DIG_FINISH -> {
                boolean sameBlock = digX == interaction.blockX() && digY == interaction.blockY()
                        && digZ == interaction.blockZ();
                if (!digActive || !sameBlock) {
                    flag(LIKELY, "dig-" + interaction.kind().name().toLowerCase(Locale.ROOT)
                            + " without matching start at " + interaction.blockX() + ","
                            + interaction.blockY() + "," + interaction.blockZ());
                }
                digActive = false;
            }
            default -> {
            }
        }
    }

    /** Prediction sequence (H): only grows for predicted actions; dig cancels carry 0. */
    private void checkSequence(BlockInteraction interaction) {
        if (!checkSequence || !player.clientVersion().isNewerThanOrEquals(ClientVersion.V_1_19)) {
            return;
        }
        int sequence = interaction.sequence();
        if (interaction.kind() == BlockInteraction.Kind.DIG_CANCEL) {
            if (sequence != 0) {
                flag(SURE, "sequence dig-cancel carries " + sequence + " (expected 0)");
            }
            return;
        }
        if (sequenceRestart) {
            sequenceRestart = false;
            if (sequence <= lastSequence) {
                // a new client world restarts its counter at 1
                lastSequence = sequence;
                return;
            }
        }
        if (sequence <= lastSequence) {
            flag(SURE, "sequence " + sequence + " after " + lastSequence + " (" +
                    interaction.kind().name().toLowerCase(Locale.ROOT) + ")");
        }
        lastSequence = Math.max(lastSequence, sequence);
    }

    // ================================================================== ticks

    @Override
    public void onClientTick() {
        if (checkPose) {
            PoseState pose = player.pose();
            if (pose.sprinting() && pose.sneaking()) {
                flag(LIKELY, "sprinting while sneaking");
            } else {
                lowerBuffer();
            }
        }
        if (useRotationPending) {
            MovementState movement = player.movement();
            if (useYaw != movement.yaw() || usePitch != movement.pitch()) {
                useMismatches++;
            }
            if (useMismatches > 0 && !isExempt() && player.abilities().gameMode() != GameMode.SPECTATOR) {
                flag(SURE, "use-rotation use=" + useYaw + "/" + usePitch
                        + " tick=" + movement.yaw() + "/" + movement.pitch() + " mismatches=" + useMismatches);
            }
            useRotationPending = false;
            useMismatches = 0;
        }
        if (ticksSincePosition < Integer.MAX_VALUE / 2) {
            ticksSincePosition++;
        }
        sprintActionsThisTick = 0;
        inputsThisTick = 0;
    }

    // ================================================================== helpers

    /** The join packet was seen: game mode, abilities and the own entity id are tracked. */
    private boolean joined() {
        return selfId != Integer.MIN_VALUE;
    }

    private long graceMillis() {
        return STATE_GRACE_MILLIS + 2L * Math.max(0L, player.acks().smoothedRttMillis());
    }

    private void flag(double weight, String details) {
        if (raiseBuffer(weight)) {
            violation(details);
            resetBuffer();
        }
    }

    private static boolean withinUnitBox(float value) {
        return value >= -CURSOR_TOLERANCE && value <= 1.0F + CURSOR_TOLERANCE;
    }
}
