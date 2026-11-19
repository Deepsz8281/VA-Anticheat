package dev.vaplus.anticheat.check.impl.combat;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateAttributes;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.check.CombatCheck;
import dev.vaplus.anticheat.check.OutboundPacketCheck;
import dev.vaplus.anticheat.check.impl.world.FreecamCheck;
import dev.vaplus.anticheat.check.impl.world.NearbyPlayers;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.MovementState;
import dev.vaplus.anticheat.player.state.PoseState;
import dev.vaplus.anticheat.world.BlockSight;

import java.util.Arrays;
import java.util.List;

/**
 * Hitbox expansion: the attack landed although the crosshair ray does not touch the target.
 *
 * <p>{@link ReachCheck} judges distance and {@link KillauraCheck} a generous angle; neither
 * verifies that the look ray actually intersects the target box. A client with enlarged hitboxes
 * passes both, because the target is close and roughly in front. This check casts the pick ray of
 * the attacking client tick against the target's 0.6 x 1.8 box (1.5 when sneaking) and measures
 * how far the box would have to grow to be hit.
 *
 * <h2>Why legitimate play does not trigger it</h2>
 * Every quantity the server cannot know exactly is replaced by the whole range it can have, and
 * the most favourable candidate wins:
 * <ul>
 *   <li><b>Target position.</b> The client renders the target where the server had it some ticks
 *       ago and interpolates between positions, so the box is tested on every <em>segment</em>
 *       between consecutive recorded positions inside the lag window, each as the union box of its
 *       two end points. The window is the largest of smoothed round trip, last round trip and the
 *       age of the oldest unacknowledged marker, plus the interpolation ticks. A window longer
 *       than {@value #MAX_LAG_TICKS} ticks is not judged at all (never truncated).</li>
 *   <li><b>Eye position.</b> The client picks its target per rendered frame from an eye
 *       interpolated between its last two tick positions. The eye is therefore the whole box swept
 *       by the attacker over the last three ticks, and vertically everything between the crawling
 *       and the standing eye height (a low ceiling forces a pose the server does not see).</li>
 *   <li><b>Look direction.</b> The frame's rotation lies between the rotations of the previous and
 *       the current tick; the whole arc between them is sampled. Ticks with a turn above
 *       {@code max-turn-degrees} are not judged.</li>
 * </ul>
 * A ray that still misses, but by less than {@code min-miss}, is neither evidence nor relief.
 * The price is sensitivity: a strafing attacker widens the eye box by his own speed, and a ray
 * above the target is rarely provable because of the eye height range. Sideways misses of a
 * standing or slowly moving attacker are what the check measures.
 *
 * <p>Unknown never judges: no recorded history for the whole window, a target that spawned,
 * vanished or jumped (teleport) inside the window, targets that ride something (passengers get no
 * movement packets, the tracked position is stale) or carry a non-default scale attribute, an
 * attacker who glides, swims, rides, is scaled or has an unknown position, spectators,
 * join/respawn/world-change/teleport exemptions, targets closer than
 * {@value #MIN_JUDGED_DISTANCE} or farther than {@value #MAX_JUDGED_DISTANCE} blocks (reach is the
 * other check's business) and clients without tick-end packets (before 1.21.2).
 *
 * <h2>Window signal (Meteor-style "Hitboxes")</h2>
 * Hitbox modules such as Meteor's "Hitboxes" only matter when the crosshair is beside the real box;
 * most hits of such a player still land on the body and relieve the buffer above. So every judged
 * hit is also kept in a window of the last {@code window-hits} judged hits: {@code window-misses}
 * impossible hits inside it are a violation of their own, whatever the buffer says. The alert
 * carries the largest measured growth: a lower bound of the expansion the client uses (the
 * candidate ranges always favour the player). Legitimate
 * play produces no impossible hit at all (every uncertainty is folded into the candidate range), so
 * a few of them in a short window are not noise.
 *
 * <h2>Known bypasses</h2>
 * Expansions below {@code min-miss} plus the eye and lag ranges; holding back acknowledgements
 * widens the lag window (and beyond {@value #MAX_LAG_TICKS} ticks disables the check); the first
 * hit on every new target; only player targets are judged.
 *
 * <p>UNCONFIRMED (reasoned from vanilla behaviour, not measured): the pick uses the frame eye
 * between the last two tick positions and a rotation between the last two sent rotations; remote
 * players interpolate over 3 ticks; the server sends {@code SET_PASSENGERS} and a non-default
 * scale attribute to every viewer when it starts tracking the entity. Passengers and scale are
 * read from outbound packets (API verified with {@code javap} on PacketEvents 2.14.0:
 * {@code WrapperPlayServerSetPassengers#getEntityId()/getPassengers()},
 * {@code WrapperPlayServerUpdateAttributes#getEntityId()/getProperties()},
 * {@code Property#getKey()/getValue()/getModifiers()},
 * {@code WrapperPlayServerDestroyEntities#getEntityIds()}).
 *
 * <p>Experimental: the thresholds are reasoned values without live calibration.
 */
public final class HitboxCheck extends Check implements CombatCheck, ClientTickCheck, OutboundPacketCheck {

    /** Hit test growth: rays within this distance of the box are plainly legitimate. */
    public static final double DEFAULT_TOLERANCE = 0.1;
    /** Needed box growth above which a hit is counted as impossible (never below the tolerance). */
    public static final double DEFAULT_MIN_MISS = 0.15;
    /** Client-side interpolation of remote players (3 ticks) plus one tick of tick-phase jitter. */
    public static final int DEFAULT_INTERPOLATION_TICKS = 4;
    /** View turn (degrees) between two ticks above which the exact crosshair is unknown. */
    public static final double DEFAULT_MAX_TURN_DEGREES = 12.0;
    /** Judged hits kept in the window signal. */
    public static final int DEFAULT_WINDOW_HITS = 20;
    /** Impossible hits inside the window that are a violation. */
    public static final int DEFAULT_WINDOW_MISSES = 3;
    static final int MAX_WINDOW = 64;

    static final int MAX_LAG_TICKS = 16;
    static final double MIN_JUDGED_DISTANCE = 1.0;
    static final double MAX_JUDGED_DISTANCE = 6.5;
    /** A target that moves farther than this between two ticks teleported; its window is unknown. */
    static final double TELEPORT_JUMP = 4.0;
    /** Largest growth the miss measurement distinguishes. */
    static final double MEASURE_CAP = 1.0;
    static final double HALF_WIDTH = 0.3;
    static final double HEIGHT_STANDING = 1.8;
    static final double HEIGHT_SNEAKING = 1.5;
    private static final int BISECT_STEPS = 6;
    private static final int TARGET_SLOTS = 4;
    private static final int FORGET_AFTER_TICKS = 400;
    /** Lowest eye height of a player pose (crawling / swimming pose, vanilla 0.4). */
    static final double LOWEST_EYE_HEIGHT = 0.4;
    /** Sideways distance between two sampled look rays at the far end of the judged range. */
    private static final double DIRECTION_SAMPLE_SPACING = 0.05;
    private static final double SCALE_EPSILON = 1.0e-6;
    private static final int MAX_PASSENGER_LINKS = 256;
    private static final int MAX_SCALED = 64;

    private static final int RING = 32;
    private static final int MASK = RING - 1;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("hitbox", CheckCategory.COMBAT)
            .displayName("Hitbox")
            .description("Hits players whose box the look ray does not touch (hitbox expansion)")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.COMBAT)
                    .withBufferLimit(4.0)
                    .withBufferDecay(0.5)
                    .withBufferMax(8.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT)
            .build();

    private final ComponentKey<NearbyPlayers> trackedKey;

    // Attacker history by tick & MASK: feet position, look direction, validity.
    private final double[] feetX = new double[RING];
    private final double[] feetY = new double[RING];
    private final double[] feetZ = new double[RING];
    private final double[] dirX = new double[RING];
    private final double[] dirY = new double[RING];
    private final double[] dirZ = new double[RING];
    private final boolean[] valid = new boolean[RING];
    private final double[] look = new double[3];

    // Target histories: slot * RING + (tick & MASK). X NaN = target unknown on that tick.
    private final int[] targetId = new int[TARGET_SLOTS];
    private final long[] targetSince = new long[TARGET_SLOTS];
    private final long[] targetUsed = new long[TARGET_SLOTS];
    private final double[] targetX = new double[TARGET_SLOTS * RING];
    private final double[] targetY = new double[TARGET_SLOTS * RING];
    private final double[] targetZ = new double[TARGET_SLOTS * RING];
    private final double[] targetHalf = new double[TARGET_SLOTS * RING];
    private final double[] targetHeight = new double[TARGET_SLOTS * RING];

    private long tick;
    private boolean attackThisTick;
    private int attackEntity;

    // Window state shared with the candidate loops (fields, to stay allocation free).
    private long windowOldest;
    private long windowAt;
    private int windowSlot;
    private int directionSteps;
    // Eye range of the attack: reference eye plus the offsets the real eye may have from it.
    private double eyeX;
    private double eyeY;
    private double eyeZ;
    private double eyeLowX;
    private double eyeHighX;
    private double eyeLowY;
    private double eyeHighY;
    private double eyeLowZ;
    private double eyeHighZ;

    // Entities whose tracked position or box is not the vanilla one (from outbound packets).
    private final int[] linkVehicle = new int[MAX_PASSENGER_LINKS];
    private final int[] linkRider = new int[MAX_PASSENGER_LINKS];
    private int links;
    private final int[] scaled = new int[MAX_SCALED];
    private int scaledCount;
    /** A table overflowed: riders/scales are unknown until the next join or respawn packet. */
    private boolean entityStateLost;

    private int hitSamples;
    private int missSamples;
    private int judged;
    private double lastMiss;

    /** Test hook: replaces the measured latency (milliseconds) when not negative. */
    long rttOverrideMillis = -1L;

    private double tolerance = DEFAULT_TOLERANCE;
    private double minMiss = DEFAULT_MIN_MISS;
    private int interpolationTicks = DEFAULT_INTERPOLATION_TICKS;
    private double maxTurnDegrees = DEFAULT_MAX_TURN_DEGREES;
    private boolean checkWindow = true;
    private int windowHits = DEFAULT_WINDOW_HITS;
    private int windowMisses = DEFAULT_WINDOW_MISSES;

    // window signal: impossible-hit flags and their measured growth, ring of judged hits
    private final boolean[] windowMiss = new boolean[MAX_WINDOW];
    private final double[] windowNeed = new double[MAX_WINDOW];
    private int windowHead;
    private int windowSize;
    private int windowMissCount;
    private int windowFlags;

    public HitboxCheck(PlayerData player, ComponentKey<NearbyPlayers> trackedKey) {
        super(player, DESCRIPTOR);
        this.trackedKey = trackedKey;
        Arrays.fill(targetId, -1);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        tolerance = Math.min(0.5, CombatOptions.tolerance(options, "tolerance", DEFAULT_TOLERANCE));
        minMiss = Math.min(MEASURE_CAP,
                Math.max(tolerance, CombatOptions.tolerance(options, "min-miss", DEFAULT_MIN_MISS)));
        interpolationTicks = Math.min(8,
                CombatOptions.atLeast(options, "interpolation-ticks", DEFAULT_INTERPOLATION_TICKS, 1));
        maxTurnDegrees = Math.min(90.0,
                CombatOptions.positive(options, "max-turn-degrees", DEFAULT_MAX_TURN_DEGREES));
        checkWindow = options.getBoolean("check-window", true);
        int hits = options.getInt("window-hits", DEFAULT_WINDOW_HITS);
        windowHits = hits >= 5 && hits <= MAX_WINDOW ? hits : DEFAULT_WINDOW_HITS;
        int misses = options.getInt("window-misses", DEFAULT_WINDOW_MISSES);
        windowMisses = misses >= 1 && misses <= windowHits ? misses : Math.min(DEFAULT_WINDOW_MISSES, windowHits);
        clearWindow();
    }

    @Override
    protected void onReset() {
        Arrays.fill(targetId, -1);
        Arrays.fill(valid, false);
        attackThisTick = false;
        hitSamples = 0;
        missSamples = 0;
        clearWindow();
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.SET_PASSENGERS) {
            WrapperPlayServerSetPassengers packet = new WrapperPlayServerSetPassengers(event);
            onPassengers(packet.getEntityId(), packet.getPassengers());
        } else if (type == PacketType.Play.Server.UPDATE_ATTRIBUTES) {
            WrapperPlayServerUpdateAttributes packet = new WrapperPlayServerUpdateAttributes(event);
            List<WrapperPlayServerUpdateAttributes.Property> properties = packet.getProperties();
            if (properties == null) {
                return;
            }
            for (int i = 0; i < properties.size(); i++) {
                WrapperPlayServerUpdateAttributes.Property property = properties.get(i);
                String key = property.getKey();
                if (key != null && key.endsWith("scale")) {
                    List<?> modifiers = property.getModifiers();
                    onScale(packet.getEntityId(), Math.abs(property.getValue() - 1.0) > SCALE_EPSILON
                            || (modifiers != null && !modifiers.isEmpty()));
                }
            }
        } else if (type == PacketType.Play.Server.DESTROY_ENTITIES) {
            for (int entityId : new WrapperPlayServerDestroyEntities(event).getEntityIds()) {
                onEntityDestroyed(entityId);
            }
        } else if (type == PacketType.Play.Server.JOIN_GAME || type == PacketType.Play.Server.RESPAWN) {
            onEntitiesCleared();
        }
    }

    /** {@code SET_PASSENGERS}: the vehicle's rider list is replaced. */
    void onPassengers(int vehicle, int[] riders) {
        for (int i = links - 1; i >= 0; i--) {
            if (linkVehicle[i] == vehicle) {
                forgetHistory(linkRider[i]);
                removeLink(i);
            }
        }
        if (riders == null) {
            return;
        }
        for (int rider : riders) {
            forgetHistory(rider);
            if (links == MAX_PASSENGER_LINKS) {
                entityStateLost = true;
                return;
            }
            linkVehicle[links] = vehicle;
            linkRider[links] = rider;
            links++;
        }
    }

    /** Scale attribute of an entity: {@code nonDefault} when its box is not the vanilla size. */
    void onScale(int entityId, boolean nonDefault) {
        int index = -1;
        for (int i = 0; i < scaledCount; i++) {
            if (scaled[i] == entityId) {
                index = i;
                break;
            }
        }
        if (nonDefault == (index >= 0)) {
            return;
        }
        forgetHistory(entityId);
        if (!nonDefault) {
            scaled[index] = scaled[--scaledCount];
        } else if (scaledCount == MAX_SCALED) {
            entityStateLost = true;
        } else {
            scaled[scaledCount++] = entityId;
        }
    }

    void onEntityDestroyed(int entityId) {
        for (int i = links - 1; i >= 0; i--) {
            if (linkVehicle[i] == entityId) {
                forgetHistory(linkRider[i]);
                removeLink(i);
            } else if (linkRider[i] == entityId) {
                removeLink(i);
            }
        }
        for (int i = 0; i < scaledCount; i++) {
            if (scaled[i] == entityId) {
                scaled[i] = scaled[--scaledCount];
                break;
            }
        }
    }

    /** Join/respawn: entity ids of the previous world are meaningless. */
    void onEntitiesCleared() {
        links = 0;
        scaledCount = 0;
        entityStateLost = false;
        Arrays.fill(targetId, -1);
    }

    private void removeLink(int index) {
        links--;
        linkVehicle[index] = linkVehicle[links];
        linkRider[index] = linkRider[links];
    }

    /** The entity's box changed discontinuously: its history restarts after the current tick. */
    private void forgetHistory(int entityId) {
        for (int s = 0; s < TARGET_SLOTS; s++) {
            if (targetId[s] == entityId) {
                targetSince[s] = tick + 2;
            }
        }
    }

    /** Whether the tracked position/box of the entity is not what the client hits. */
    private boolean unreliable(int entityId) {
        if (entityStateLost) {
            return true;
        }
        for (int i = 0; i < links; i++) {
            if (linkRider[i] == entityId) {
                return true;
            }
        }
        for (int i = 0; i < scaledCount; i++) {
            if (scaled[i] == entityId) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onAttack(EntityAttack attack) {
        if (!attack.attacked() || attackThisTick) {
            return;
        }
        attackThisTick = true;
        attackEntity = attack.entityId();
    }

    @Override
    public void onClientTick() {
        long current = ++tick;
        recordAttacker(current);
        recordTargets(current);
        if (attackThisTick) {
            attackThisTick = false;
            int slot = slotFor(attackEntity, current);
            if (slot >= 0) {
                judge(slot, current);
            }
        }
    }

    private void recordAttacker(long current) {
        int slot = (int) (current & MASK);
        MovementState movement = player.movement();
        PoseState pose = player.pose();
        if (!movement.positionKnown() || !movement.rotationKnown() || pose.gliding() || pose.swimming()
                || player.attributes().pending()
                || Math.abs(player.attributes().scale() - 1.0) > SCALE_EPSILON) {
            valid[slot] = false;
            return;
        }
        valid[slot] = true;
        feetX[slot] = movement.x();
        feetY[slot] = movement.y();
        feetZ[slot] = movement.z();
        FreecamCheck.direction(movement.yaw(), movement.pitch(), look);
        dirX[slot] = look[0];
        dirY[slot] = look[1];
        dirZ[slot] = look[2];
    }

    private void recordTargets(long current) {
        NearbyPlayers tracked = null;
        for (int s = 0; s < TARGET_SLOTS; s++) {
            if (targetId[s] < 0) {
                continue;
            }
            if (current - targetUsed[s] > FORGET_AFTER_TICKS) {
                targetId[s] = -1;
                continue;
            }
            if (tracked == null) {
                tracked = player.component(trackedKey);
            }
            writeTarget(s, current, tracked);
        }
    }

    private void writeTarget(int s, long current, NearbyPlayers tracked) {
        int at = s * RING + (int) (current & MASK);
        int index = tracked.indexOf(targetId[s]);
        if (index < 0 || unreliable(targetId[s])) {
            targetX[at] = Double.NaN;
            // History restarts once the entity is back: a respawn is never judged mid-window.
            targetSince[s] = current + 1;
            return;
        }
        targetX[at] = tracked.x(index);
        targetY[at] = tracked.y(index);
        targetZ[at] = tracked.z(index);
        targetHalf[at] = tracked.halfWidth(index);
        targetHeight[at] = tracked.height(index);
    }

    /** Slot of the target, creating one (history starts now) or evicting the stalest; -1 = unknown entity. */
    private int slotFor(int entityId, long current) {
        int free = -1;
        int stalest = 0;
        for (int s = 0; s < TARGET_SLOTS; s++) {
            if (targetId[s] == entityId) {
                targetUsed[s] = current;
                return s;
            }
            if (targetId[s] < 0) {
                free = s;
            } else if (targetId[stalest] < 0 || targetUsed[s] < targetUsed[stalest]) {
                stalest = s;
            }
        }
        NearbyPlayers tracked = player.component(trackedKey);
        if (tracked.indexOf(entityId) < 0) {
            return -1;
        }
        int s = free >= 0 ? free : stalest;
        targetId[s] = entityId;
        targetSince[s] = current;
        targetUsed[s] = current;
        writeTarget(s, current, tracked);
        // No history before this tick: the first hit on a target can never be judged.
        return s;
    }

    private void judge(int slot, long at) {
        if (!player.tickEndMode() || isExempt() || player.vehicle().inVehicle()
                || player.vehicle().ticksSinceDismount() < 10) {
            return;
        }
        int lag = lagTicks();
        if (lag > MAX_LAG_TICKS) {
            // The client may have seen positions older than the recorded window: unknown.
            return;
        }
        long oldest = at - lag;
        if (at < 4 || oldest - 1 < targetSince[slot]) {
            return;
        }
        int now = (int) (at & MASK);
        int before = (int) ((at - 1) & MASK);
        int older = (int) ((at - 2) & MASK);
        if (!valid[now] || !valid[before] || !valid[older]) {
            return;
        }
        if (distance(feetX[now], feetZ[now], feetX[before], feetZ[before]) > TELEPORT_JUMP
                || Math.abs(feetY[now] - feetY[before]) > TELEPORT_JUMP
                || distance(feetX[before], feetZ[before], feetX[older], feetZ[older]) > TELEPORT_JUMP
                || Math.abs(feetY[before] - feetY[older]) > TELEPORT_JUMP) {
            return;
        }
        double dot = dirX[now] * dirX[before] + dirY[now] * dirY[before] + dirZ[now] * dirZ[before];
        double turn = Math.acos(Math.max(-1.0, Math.min(1.0, dot)));
        if (Math.toDegrees(turn) > maxTurnDegrees) {
            return;
        }

        // The whole window must be known, and free of teleports.
        int base = slot * RING;
        for (long i = oldest - 1; i <= at; i++) {
            int index = base + (int) (i & MASK);
            if (Double.isNaN(targetX[index])) {
                return;
            }
            if (i >= oldest) {
                int prev = base + (int) ((i - 1) & MASK);
                if (distance(targetX[index], targetZ[index], targetX[prev], targetZ[prev]) > TELEPORT_JUMP
                        || Math.abs(targetY[index] - targetY[prev]) > TELEPORT_JUMP) {
                    return;
                }
            }
        }
        int current = base + now;
        double centerDistance = distance(targetX[current], targetZ[current], feetX[now], feetZ[now]);
        if (centerDistance < MIN_JUDGED_DISTANCE || centerDistance > MAX_JUDGED_DISTANCE) {
            return;
        }

        windowOldest = oldest;
        windowAt = at;
        windowSlot = slot;
        // Neighbouring sampled rays are at most DIRECTION_SAMPLE_SPACING apart on the target.
        directionSteps = (int) Math.ceil(turn * (MAX_JUDGED_DISTANCE + 1.0) / DIRECTION_SAMPLE_SPACING);
        // Reference eye: standing eye of the previous tick. The real eye may be anywhere in the box
        // the attacker swept over the last three ticks, at any pose's eye height.
        eyeX = feetX[before];
        eyeY = feetY[before] + FreecamCheck.STANDING_EYE_HEIGHT;
        eyeZ = feetZ[before];
        eyeLowX = Math.min(feetX[older], Math.min(feetX[before], feetX[now])) - feetX[before];
        eyeHighX = Math.max(feetX[older], Math.max(feetX[before], feetX[now])) - feetX[before];
        eyeLowZ = Math.min(feetZ[older], Math.min(feetZ[before], feetZ[now])) - feetZ[before];
        eyeHighZ = Math.max(feetZ[older], Math.max(feetZ[before], feetZ[now])) - feetZ[before];
        eyeLowY = Math.min(feetY[older], Math.min(feetY[before], feetY[now])) - feetY[before]
                - (FreecamCheck.STANDING_EYE_HEIGHT - LOWEST_EYE_HEIGHT);
        eyeHighY = Math.max(feetY[older], Math.max(feetY[before], feetY[now])) - feetY[before];
        judged++;
        if (hits(tolerance)) {
            hitSamples++;
            lastMiss = 0.0;
            lowerBuffer();
            recordWindow(false, 0.0, slot, lag);
            return;
        }
        double need = missDistance();
        lastMiss = need;
        if (need <= minMiss) {
            // Close to the box but beyond the tolerance: neither evidence nor proof.
            recordWindow(false, 0.0, slot, lag);
            return;
        }
        missSamples++;
        if (recordWindow(true, need, slot, lag)) {
            return;
        }
        if (debugging()) {
            debug("entity=" + targetId[slot] + " miss=" + CombatOptions.number(need)
                    + " lag=" + lag + "t buffer=" + CombatOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation("entity=" + targetId[slot]
                    + " miss=" + CombatOptions.number(need)
                    + " limit=" + CombatOptions.number(minMiss)
                    + " lag=" + lag + "t"
                    + " hits=" + hitSamples + " misses=" + missSamples
                    + " buffer=" + CombatOptions.number(buffer()));
            resetBuffer();
            hitSamples = 0;
            missSamples = 0;
            clearWindow();
        }
    }

    /**
     * Adds one judged hit to the window; returns {@code true} when the window produced a violation
     * (the buffer path is then skipped for this hit).
     */
    private boolean recordWindow(boolean miss, double need, int slot, int lag) {
        if (!checkWindow) {
            return false;
        }
        if (windowSize == windowHits) {
            int oldest = (windowHead - windowHits + MAX_WINDOW) % MAX_WINDOW;
            if (windowMiss[oldest]) {
                windowMissCount--;
            }
        } else {
            windowSize++;
        }
        windowMiss[windowHead] = miss;
        windowNeed[windowHead] = need;
        windowHead = (windowHead + 1) % MAX_WINDOW;
        if (miss) {
            windowMissCount++;
        }
        if (!miss || windowMissCount < windowMisses) {
            return false;
        }
        double largest = 0.0;
        for (int i = 0; i < windowSize; i++) {
            int index = (windowHead - 1 - i + MAX_WINDOW) % MAX_WINDOW;
            if (windowMiss[index]) {
                largest = Math.max(largest, windowNeed[index]);
            }
        }
        String details = "pattern entity=" + targetId[slot]
                + " misses=" + windowMissCount + "/" + windowSize
                + " expansion>=" + CombatOptions.number(largest)
                + " limit=" + CombatOptions.number(minMiss)
                + " lag=" + lag + "t";
        windowFlags++;
        clearWindow();
        resetBuffer();
        hitSamples = 0;
        missSamples = 0;
        violation(details);
        return true;
    }

    private void clearWindow() {
        windowHead = 0;
        windowSize = 0;
        windowMissCount = 0;
    }

    /** Violations raised by the window signal (tests, diagnostics). */
    int windowFlags() {
        return windowFlags;
    }

    /** Smallest box growth (capped at {@link #MEASURE_CAP}) for which some candidate ray hits. */
    private double missDistance() {
        if (!hits(MEASURE_CAP)) {
            return MEASURE_CAP;
        }
        double low = tolerance;
        double high = MEASURE_CAP;
        for (int step = 0; step < BISECT_STEPS; step++) {
            double mid = 0.5 * (low + high);
            if (hits(mid)) {
                high = mid;
            } else {
                low = mid;
            }
        }
        return high;
    }

    /**
     * Whether any candidate ray hits any segment box grown by {@code margin}. A ray from an eye
     * displaced by {@code o} hits a box exactly when the ray from the reference eye hits the box
     * moved by {@code -o}, so the eye range is folded into the box and one origin is enough. The
     * directions are sampled on the arc between the look of the previous and of the current tick
     * (direction length is irrelevant for the hit test).
     */
    private boolean hits(double margin) {
        int base = windowSlot * RING;
        long at = windowAt;
        int from = (int) ((at - 1) & MASK);
        int to = (int) (at & MASK);
        int steps = directionSteps;
        for (long i = windowOldest; i <= at; i++) {
            int a = base + (int) ((i - 1) & MASK);
            int b = base + (int) (i & MASK);
            double half = Math.max(targetHalf[a], targetHalf[b]);
            double minX = Math.min(targetX[a], targetX[b]) - half - margin - eyeHighX;
            double maxX = Math.max(targetX[a], targetX[b]) + half + margin - eyeLowX;
            double minZ = Math.min(targetZ[a], targetZ[b]) - half - margin - eyeHighZ;
            double maxZ = Math.max(targetZ[a], targetZ[b]) + half + margin - eyeLowZ;
            double minY = Math.min(targetY[a], targetY[b]) - margin - eyeHighY;
            double height = Math.max(targetHeight[a], targetHeight[b]);
            double maxY = Math.max(targetY[a], targetY[b]) + height + margin - eyeLowY;
            for (int step = 0; step <= steps; step++) {
                double t = steps == 0 ? 1.0 : (double) step / steps;
                double x = dirX[from] + (dirX[to] - dirX[from]) * t;
                double y = dirY[from] + (dirY[to] - dirY[from]) * t;
                double z = dirZ[from] + (dirZ[to] - dirZ[from]) * t;
                double entry = BlockSight.rayBoxEntry(eyeX, eyeY, eyeZ, x, y, z,
                        minX, minY, minZ, maxX, maxY, maxZ);
                if (!Double.isNaN(entry)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Ticks of target history the client may be behind: latency (the largest of smoothed round
     * trip, last round trip and the age of the oldest unacknowledged marker) plus interpolation.
     * Not capped; the caller refuses to judge above {@link #MAX_LAG_TICKS}.
     */
    private int lagTicks() {
        long rtt = rttOverrideMillis;
        if (rtt < 0) {
            rtt = Math.max(player.acks().smoothedRttMillis(), player.acks().rttMillis());
            rtt = Math.max(rtt, player.acks().oldestPendingAgeNanos() / 1_000_000L);
        }
        if (rtt > 10_000L) {
            return MAX_LAG_TICKS + 1;
        }
        int network = rtt <= 0 ? 0 : (int) ((rtt + 49) / 50);
        return network + interpolationTicks;
    }

    private static double distance(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return Math.sqrt(dx * dx + dz * dz);
    }

    int judgedSamples() {
        return judged;
    }

    int hitSamples() {
        return hitSamples;
    }

    int missSamples() {
        return missSamples;
    }

    double lastMiss() {
        return lastMiss;
    }
}
