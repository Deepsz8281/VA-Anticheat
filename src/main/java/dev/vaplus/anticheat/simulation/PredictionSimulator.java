package dev.vaplus.anticheat.simulation;

import com.github.retrooper.packetevents.protocol.player.GameMode;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.module.ModuleSetup;
import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.pipeline.TeleportQueue;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.AttributeState;
import dev.vaplus.anticheat.player.state.EffectState;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.MotionHistory;
import dev.vaplus.anticheat.player.state.MovementState;
import dev.vaplus.anticheat.player.state.PoseState;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import static dev.vaplus.anticheat.simulation.PhysicsConstants.AIR_ACCELERATION;
import static dev.vaplus.anticheat.simulation.PhysicsConstants.AIR_SLIPPERINESS;
import static dev.vaplus.anticheat.simulation.PhysicsConstants.GROUND_ACCEL_NUMERATOR;
import static dev.vaplus.anticheat.simulation.PhysicsConstants.HORIZONTAL_DRAG;
import static dev.vaplus.anticheat.simulation.PhysicsConstants.INPUT_FACTOR;
import static dev.vaplus.anticheat.simulation.PhysicsConstants.ITEM_USE_INPUT_FACTOR;
import static dev.vaplus.anticheat.simulation.PhysicsConstants.JUMP_APEX_MARGIN;
import static dev.vaplus.anticheat.simulation.PhysicsConstants.JUMP_BOOST_PER_LEVEL;
import static dev.vaplus.anticheat.simulation.PhysicsConstants.NEGLIGIBLE_MOMENTUM;
import static dev.vaplus.anticheat.simulation.PhysicsConstants.SLOWNESS_PER_LEVEL;
import static dev.vaplus.anticheat.simulation.PhysicsConstants.SLOW_FALLING_GRAVITY;
import static dev.vaplus.anticheat.simulation.PhysicsConstants.SPEED_PER_LEVEL;
import static dev.vaplus.anticheat.simulation.PhysicsConstants.SPRINT_JUMP_BOOST;
import static dev.vaplus.anticheat.simulation.PhysicsConstants.SPRINT_MULTIPLIER;
import static dev.vaplus.anticheat.simulation.PhysicsConstants.UNCONFIRMED_HALF_WIDTH;
import static dev.vaplus.anticheat.simulation.PhysicsConstants.UNCONFIRMED_HEIGHT;
import static dev.vaplus.anticheat.simulation.PhysicsConstants.UNCONFIRMED_SLOWNESS_EFFECT_MODIFIER_ID;
import static dev.vaplus.anticheat.simulation.PhysicsConstants.UNCONFIRMED_SPEED_EFFECT_MODIFIER_ID;
import static dev.vaplus.anticheat.simulation.PhysicsConstants.UNCONFIRMED_SPRINT_MODIFIER_ID;
import static dev.vaplus.anticheat.simulation.PhysicsConstants.VERTICAL_DRAG;

/**
 * Phase-2 movement engine: predicts vanilla on-foot player movement for the ticks covered by one
 * position packet and reports the best-matching candidate in {@link SimulationResult}.
 *
 * <h2>Model (per client tick, constants and sources in {@link PhysicsConstants})</h2>
 * <ol>
 *   <li>Start motion = the engine-owned seed (previous movement after friction/gravity/drag), with
 *       0..n offered server velocities applied ({@link VelocityState}: SET replaces, ADD adds).</li>
 *   <li>Each axis below {@link PhysicsConstants#NEGLIGIBLE_MOMENTUM} is zeroed.</li>
 *   <li>Jump (on ground only): vy = jump_strength + 0.1 per Jump Boost level; sprinting adds
 *       0.2 along the facing direction.</li>
 *   <li>Input acceleration: input (forward/strafe ∈ {-1,0,1}) × 0.98 (× sneaking_speed while
 *       sneaking), normalised when longer than 1 (MCPK: 45° strafe multiplier 1.0), rotated by yaw
 *       and scaled by {@code movement_speed × E × (0.216 / f³)} on ground or 0.02 (× 1.3 sprinting)
 *       in the air; E = Speed/Slowness effect factor.</li>
 *   <li>Move against a uniform floor ({@link SupportScan}); landing zeroes vy.</li>
 *   <li>vy = (vy − gravity) × 0.98 (Slow Falling: gravity ≤ 0.01 while vy ≤ 0); horizontal × 0.91 × f
 *       when the tick started on ground (f = floor slipperiness), × 0.91 otherwise.</li>
 * </ol>
 * Candidates: every velocity prefix × forward × strafe × sprint (forward only, not sneaking) ×
 * jump × item-use slowdown (input × 0.2); the same input is assumed for all ticks of a multi-tick packet. The candidate with the
 * smallest squared distance to the reported delta wins.
 *
 * <h2>Skips</h2>
 * The engine reports SKIPPED (never a guessed offset) when inputs are unknown or not modelled:
 * vehicle, flight, creative/spectator, elytra/swimming, levitation (formula UNCONFIRMED),
 * unmodelled impulses (riptide, wind charge, lunge, ...), pending teleport, non-default
 * scale/26.2 friction/air-drag/bounciness attributes (exact formulas UNCONFIRMED), unknown rotation,
 * too many ticks, missing motion seed and any environment {@link SupportScan} rejects (unknown or
 * unloaded blocks, walls, edges, liquids, special blocks).
 *
 * <p>Compensation windows (phase 2 round 3): while a movement attribute update
 * ({@link AttributeState#pending()}), a movement effect change ({@link EffectState#pending}) or a
 * world change inside the scanned region ({@link WorldMirror#pending()}, settled here with the
 * last acknowledged marker) may or may not have been applied by the client, the engine reports
 * SKIPPED ({@code attributes-pending}, {@code effects-pending}, {@code world-pending}).
 *
 * <p>Thread safety: one shared instance; all mutable data lives in the per-player
 * {@link PredictionState} (owner thread). The hot path does not allocate.
 */
public final class PredictionSimulator implements MovementSimulator {

    public static final String SKIP_VEHICLE = "vehicle";
    public static final String SKIP_FLYING = "flying";
    public static final String SKIP_GAMEMODE = "creative-or-spectator";
    public static final String SKIP_ELYTRA = "elytra";
    public static final String SKIP_SWIMMING = "swimming";
    public static final String SKIP_UNMODELLED_IMPULSE = "unmodelled-impulse";
    public static final String SKIP_LEVITATION = "levitation";
    public static final String SKIP_AWAITING_TELEPORT = "awaiting-teleport";
    public static final String SKIP_ATTRIBUTES = "attribute-unmodelled";
    public static final String SKIP_ROTATION = "rotation-unknown";
    public static final String SKIP_TOO_MANY_TICKS = "too-many-ticks";
    public static final String SKIP_NO_SEED = "no-motion-seed";
    public static final String SKIP_WORLD_UNKNOWN = "world-unknown";
    public static final String SKIP_COMPLEX = "complex-environment";
    public static final String SKIP_EDGE = "edge";
    public static final String SKIP_REGION = "region-too-large";
    public static final String SKIP_UNVERIFIED = "unverified-depth";
    /** A block/chunk/world change in the scanned region is not acknowledged by the client yet. */
    public static final String SKIP_WORLD_PENDING = "world-pending";
    /** A movement-attribute update is between its two bracket acknowledgements. */
    public static final String SKIP_ATTRIBUTES_PENDING = "attributes-pending";
    /** A movement-effect change is between its two bracket acknowledgements. */
    public static final String SKIP_EFFECTS_PENDING = "effects-pending";

    /** Upper bound of client ticks simulated for one packet. */
    public static final int MAX_TICKS = 20;
    /** Feet within this distance of the floor top count as standing on it. */
    public static final double GROUND_EPSILON = 1.0E-5;
    /** Extra horizontal scan margin around the hitbox (keeps near-miss candidates inside). */
    public static final double SCAN_MARGIN = 0.3;
    /**
     * Always reported: Math.sin/cos instead of the client's trig implementation and double vs float
     * rounding. UNCONFIRMED magnitude (engineering estimate, not a sourced number).
     */
    public static final double BASE_UNCERTAINTY = 1.0E-4;
    /** Seed rebuilt from {@link MotionHistory} with an unverified ground claim. UNCONFIRMED estimate. */
    public static final double HISTORY_SEED_UNCERTAINTY = 0.03;
    /**
     * Multi-tick packet with an offered velocity: the engine assumes it applied at the first tick.
     * UNCONFIRMED estimate.
     */
    public static final double MULTI_TICK_VELOCITY_UNCERTAINTY = 0.05;
    /**
     * Slow Falling while rising: the "only while vy ≤ 0" condition is not in the fetched sources
     * (UNCONFIRMED); granted per tick as the gravity difference.
     */
    public static final double SLOW_FALLING_RISE_UNCERTAINTY = (PhysicsConstants.GRAVITY - SLOW_FALLING_GRAVITY)
            * VERTICAL_DRAG;
    /**
     * Position-domain error above which the prediction chain is treated as desynced and restarted.
     * TUNING (not a vanilla value): well above any modelled float drift, well below a teleport.
     */
    public static final double MAX_CUMULATIVE_OFFSET = 0.5;
    /**
     * Per-packet residual below which the next seed stays engine-owned. TUNING: above float drift
     * (1e-6) and below the smallest meaningful hack step; larger residuals fold back into the seed.
     */
    public static final double SEED_RESIDUAL_LIMIT = 0.02;

    /** Effects whose (un)certain state changes the simulated motion. */
    private static final TrackedEffect[] MOVEMENT_EFFECTS = {TrackedEffect.SPEED, TrackedEffect.SLOWNESS,
            TrackedEffect.JUMP_BOOST, TrackedEffect.LEVITATION, TrackedEffect.SLOW_FALLING};

    private static final long UNMODELLED_IMPULSES = ExemptionCause.mask(ExemptionCause.RIPTIDE,
            ExemptionCause.ELYTRA_BOOST, ExemptionCause.WIND_CHARGE, ExemptionCause.LUNGE,
            ExemptionCause.BOUNCE, ExemptionCause.PORTAL, ExemptionCause.VEHICLE);

    private final ComponentKey<PredictionState> stateKey;
    private final ComponentKey<VelocityState> velocityKey;
    private final ComponentKey<WorldMirror> worldKey;
    private volatile LateKeys lateKeys;

    public PredictionSimulator(ComponentKey<PredictionState> stateKey, ComponentKey<VelocityState> velocityKey,
                               ComponentKey<WorldMirror> worldKey) {
        this.stateKey = stateKey;
        this.velocityKey = velocityKey;
        this.worldKey = worldKey;
    }

    @Override
    public void simulate(PlayerData player, MovementUpdate update, SimulationResult out) {
        PredictionState state = player.component(stateKey);
        VelocityState velocity = player.component(velocityKey);
        MovementState movement = player.movement();
        long positionPackets = movement.positionPackets();
        int epoch = movement.worldEpoch();
        velocity.expire(player.clientTicks(), epoch);
        WorldMirror world = player.component(worldKey);
        if (world.pending().any()) {
            world.pending().settle(player.acks().lastAckedMarker());
        }
        String reason = run(player, update, out, state, velocity, positionPackets, epoch);
        if (reason != null) {
            out.skip(reason);
            state.skipped++;
            // A packet the engine could not explain breaks the position chain: the next prediction
            // must start from the reported position again instead of carrying an unknown error.
            state.invalidateChain();
            velocity.taintOfferable();
            velocity.consume(0);
        } else {
            state.completed++;
        }
        state.lastSeenPositionPackets = positionPackets;
        state.lastSeenTicks = update.elapsedTicks();
    }

    /** @return {@code null} when {@code out} was completed, otherwise the skip reason */
    private String run(PlayerData player, MovementUpdate update, SimulationResult out, PredictionState state,
                       VelocityState velocity, long positionPackets, int epoch) {
        String gate = gate(player, update);
        if (gate != null) {
            state.invalidateSeed();
            return gate;
        }
        int ticks = update.elapsedTicks();
        AttributeState attributes = player.attributes();
        refreshAttributeCache(state, attributes);
        EffectState effects = player.effects();
        boolean slowFalling = effects.has(TrackedEffect.SLOW_FALLING);
        double gravity = attributes.gravity();

        // --- motion seed ---------------------------------------------------------------------
        double uncertainty = BASE_UNCERTAINTY;
        double seedX = 0.0;
        double seedY;
        double seedZ = 0.0;
        boolean fromHistory = false;
        double historyDx = 0.0;
        double historyDz = 0.0;
        boolean historyGround = false;
        if (state.seedValidFor(positionPackets, epoch)) {
            seedX = state.seedX;
            seedY = state.seedY;
            seedZ = state.seedZ;
        } else {
            MotionHistory.TickSnapshot previous = previousSnapshot(player, state, positionPackets, epoch);
            if (previous == null) {
                return reseedAfterSkip(player, update, state, positionPackets, epoch, gravity, slowFalling,
                        SKIP_NO_SEED);
            }
            fromHistory = true;
            historyDx = previous.dx();
            historyDz = previous.dz();
            historyGround = previous.onGround() && previous.dy() <= 0.0;
            seedY = afterGravity(historyGround ? 0.0 : previous.dy(), gravity, slowFalling);
            uncertainty += HISTORY_SEED_UNCERTAINTY;
        }

        // --- environment ---------------------------------------------------------------------
        int offerable = velocity.offerableCount();
        double lowestStartVy = seedY;
        for (int k = 1; k <= offerable; k++) {
            lowestStartVy = Math.min(lowestStartVy, composeY(velocity, k, seedY));
        }
        double fromX = update.fromX();
        double fromY = update.fromY();
        double fromZ = update.fromZ();
        double minFeet = Math.min(fromY, update.toY());
        double maxFeet = Math.max(fromY, update.toY());
        double reach = UNCONFIRMED_HALF_WIDTH + SCAN_MARGIN;
        int scanResult = state.scan.scan(player.component(worldKey),
                Math.min(fromX, update.toX()) - reach, Math.max(fromX, update.toX()) + reach,
                Math.min(fromZ, update.toZ()) - reach, Math.max(fromZ, update.toZ()) + reach,
                minFeet, maxFeet + UNCONFIRMED_HEIGHT + (ticks > 1 ? JUMP_APEX_MARGIN : 0.0),
                minFeet - fallBound(lowestStartVy, gravity, ticks));
        if (scanResult != SupportScan.OK) {
            state.invalidateSeed();
            return switch (scanResult) {
                case SupportScan.UNKNOWN -> SKIP_WORLD_UNKNOWN;
                case SupportScan.PENDING -> SKIP_WORLD_PENDING;
                case SupportScan.EDGE -> SKIP_EDGE;
                case SupportScan.TOO_LARGE -> SKIP_REGION;
                default -> SKIP_COMPLEX;
            };
        }
        SupportScan scan = state.scan;
        boolean floor = scan.floorFound();
        double floorTop = scan.floorTop();
        double lowestVerified = scan.lowestVerifiedFeet();
        boolean startGround = floor && Math.abs(fromY - floorTop) < GROUND_EPSILON;
        if (fromHistory) {
            double factor = horizontalFactor(historyGround, scan.friction(fromX, fromZ));
            seedX = historyDx * factor;
            seedZ = historyDz * factor;
        }

        // --- per-player constants for this packet --------------------------------------------
        PoseState pose = player.pose();
        boolean sneaking = pose.sneaking();
        double sneakFactor = sneaking ? attributes.sneakingSpeed() : 1.0;
        double effectFactor = effectFactor(state, effects);
        double walkSpeed = state.movementSpeedNoSprint * effectFactor;
        double jumpPower = attributes.jumpStrength() + JUMP_BOOST_PER_LEVEL * effects.level(TrackedEffect.JUMP_BOOST);
        float yawRadians = player.movement().yaw() * ((float) Math.PI / 180.0F);
        double sin = Math.sin(yawRadians);
        double cos = Math.cos(yawRadians);
        double dx = update.deltaX();
        double dy = update.deltaY();
        double dz = update.deltaZ();

        // --- candidates ----------------------------------------------------------------------
        double bestError = Double.POSITIVE_INFINITY;
        int bestIndex = -1;
        int bestVelocity = 0;
        double bestX = 0.0;
        double bestY = 0.0;
        double bestZ = 0.0;
        boolean bestGround = false;
        double bestLastX = 0.0;
        double bestLastY = 0.0;
        double bestLastZ = 0.0;
        boolean bestLastStartGround = false;
        double bestLastFriction = AIR_SLIPPERINESS;
        boolean risingWithSlowFalling = false;
        // Velocity bookkeeping: best candidate that honours every acknowledged velocity.
        int acked = velocity.ackedCount();
        double forcedError = Double.POSITIVE_INFINITY;
        double forcedHorizontalError = Double.POSITIVE_INFINITY;

        for (int k = 0; k <= offerable; k++) {
            double startX = composeX(velocity, k, seedX);
            double startY = composeY(velocity, k, seedY);
            double startZ = composeZ(velocity, k, seedZ);
            for (int jump = 0; jump <= 1; jump++) {
                for (int sprint = 0; sprint <= 1; sprint++) {
                    for (int forward = -1; forward <= 1; forward++) {
                        if (sprint == 1 && (forward != 1 || sneaking)) {
                            // Sprinting needs forward input and no sneaking. UNCONFIRMED detail:
                            // MCPK only lists the multipliers, not the sprint preconditions.
                            continue;
                        }
                        for (int slot = 0; slot < 6; slot++) {
                            int strafe = slot % 3 - 1;
                            int use = slot / 3;
                            if (use == 1 && (sprint == 1 || (forward == 0 && strafe == 0))) {
                                // Item-use slowdown only matters with input; no sprinting while using
                                // (UNCONFIRMED precondition, the candidate set only gets smaller).
                                continue;
                            }
                            // Input vector (MCPK "0.98 Default", 45° strafe 1.0 => normalised if > 1).
                            double inputScale = INPUT_FACTOR * sneakFactor * (use == 1 ? ITEM_USE_INPUT_FACTOR : 1.0);
                            double inForward = forward * inputScale;
                            double inStrafe = strafe * inputScale;
                            double lengthSquared = inForward * inForward + inStrafe * inStrafe;
                            double accelX = 0.0;
                            double accelZ = 0.0;
                            if (lengthSquared >= 1.0E-7) {
                                if (lengthSquared > 1.0) {
                                    double norm = 1.0 / Math.sqrt(lengthSquared);
                                    inForward *= norm;
                                    inStrafe *= norm;
                                }
                                accelX = inStrafe * cos - inForward * sin;
                                accelZ = inForward * cos + inStrafe * sin;
                            }

                            double vx = startX;
                            double vy = startY;
                            double vz = startZ;
                            double px = 0.0;
                            double py = 0.0;
                            double pz = 0.0;
                            boolean ground = startGround;
                            boolean verified = true;
                            double lastX = 0.0;
                            double lastY = 0.0;
                            double lastZ = 0.0;
                            boolean lastStartGround = startGround;
                            double lastFriction = AIR_SLIPPERINESS;
                            boolean rose = false;
                            for (int tick = 0; tick < ticks; tick++) {
                                if (Math.abs(vx) < NEGLIGIBLE_MOMENTUM) {
                                    vx = 0.0;
                                }
                                if (Math.abs(vy) < NEGLIGIBLE_MOMENTUM) {
                                    vy = 0.0;
                                }
                                if (Math.abs(vz) < NEGLIGIBLE_MOMENTUM) {
                                    vz = 0.0;
                                }
                                boolean tickGround = ground;
                                double friction = tickGround
                                        ? (float) scan.friction(fromX + px, fromZ + pz) : AIR_SLIPPERINESS;
                                if (jump == 1 && tickGround) {
                                    vy = jumpPower;
                                    if (sprint == 1) {
                                        vx -= sin * SPRINT_JUMP_BOOST;
                                        vz += cos * SPRINT_JUMP_BOOST;
                                    }
                                }
                                double speed;
                                if (tickGround) {
                                    double attribute = sprint == 1 ? walkSpeed * SPRINT_MULTIPLIER : walkSpeed;
                                    speed = attribute * (GROUND_ACCEL_NUMERATOR / (friction * friction * friction));
                                } else {
                                    speed = sprint == 1 ? AIR_ACCELERATION * SPRINT_MULTIPLIER : AIR_ACCELERATION;
                                }
                                vx += accelX * speed;
                                vz += accelZ * speed;

                                double nextY = py + vy;
                                boolean landed = false;
                                if (floor && vy < 0.0 && fromY + nextY < floorTop) {
                                    nextY = floorTop - fromY;
                                    landed = true;
                                } else if (!floor && fromY + nextY < lowestVerified) {
                                    verified = false;
                                    break;
                                }
                                lastX = vx;
                                lastY = nextY - py;
                                lastZ = vz;
                                lastStartGround = tickGround;
                                lastFriction = friction;
                                px += vx;
                                py = nextY;
                                pz += vz;

                                double collidedY = landed ? 0.0 : vy;
                                if (collidedY > 0.0) {
                                    rose = true;
                                }
                                double factor = horizontalFactor(tickGround, friction);
                                vx *= factor;
                                vz *= factor;
                                vy = afterGravity(collidedY, gravity, slowFalling);
                                ground = landed;
                            }
                            if (!verified) {
                                continue;
                            }
                            double ex = dx - px;
                            double ey = dy - py;
                            double ez = dz - pz;
                            double error = ex * ex + ey * ey + ez * ez;
                            if (k >= acked && error < forcedError) {
                                forcedError = error;
                                forcedHorizontalError = ex * ex + ez * ez;
                            }
                            if (error < bestError) {
                                bestError = error;
                                bestIndex = candidateIndex(k, jump, sprint, use, forward, strafe);
                                bestVelocity = k;
                                bestX = px;
                                bestY = py;
                                bestZ = pz;
                                bestGround = ground;
                                bestLastX = lastX;
                                bestLastY = lastY;
                                bestLastZ = lastZ;
                                bestLastStartGround = lastStartGround;
                                bestLastFriction = lastFriction;
                                risingWithSlowFalling = slowFalling && rose;
                            }
                        }
                    }
                }
            }
        }
        if (bestIndex < 0) {
            state.invalidateSeed();
            return SKIP_UNVERIFIED;
        }

        if (ticks > 1 && bestVelocity > 0) {
            uncertainty += MULTI_TICK_VELOCITY_UNCERTAINTY;
        }
        if (risingWithSlowFalling) {
            uncertainty += SLOW_FALLING_RISE_UNCERTAINTY * ticks;
        }
        out.complete(bestX, bestY, bestZ, dx - bestX, dy - bestY, dz - bestZ, bestGround, ticks, bestIndex,
                uncertainty);
        if (acked > 0) {
            int newest = acked - 1;
            double hx = velocity.x(newest);
            double hz = velocity.z(newest);
            boolean forced = forcedError != Double.POSITIVE_INFINITY;
            for (int i = 0; i < acked && forced; i++) {
                // Possibly applied during a skipped movement: whether it must show up now is unknown.
                forced = !velocity.tainted(i);
            }
            out.completeVelocity(acked, bestVelocity,
                    forced ? Math.sqrt(forcedError) : Double.NaN,
                    forced ? Math.sqrt(forcedHorizontalError) : Double.NaN,
                    Math.sqrt(hx * hx + hz * hz), velocity.y(newest));
        } else {
            out.completeVelocity(0, bestVelocity, Double.NaN, Double.NaN, 0.0, 0.0);
        }
        velocity.consume(bestVelocity);
        updateChain(state, update, positionPackets, epoch, ticks,
                bestX, bestY, bestZ, bestIndex >= 0, out);

        // Seed ownership (detection-strength fix): a small residual means the prediction explained
        // the tick, so the next seed stays engine-owned (predicted last-tick motion). A constant
        // excess then keeps showing up as a residual and accumulates in the position chain instead
        // of being absorbed into the seed every tick; only a large residual (unmodelled impulse)
        // is folded back into the seed so the engine follows the client again.
        double residualX = dx - bestX;
        double residualY = dy - bestY;
        double residualZ = dz - bestZ;
        boolean engineOwned = Math.sqrt(residualX * residualX + residualY * residualY
                + residualZ * residualZ) <= SEED_RESIDUAL_LIMIT;
        double moveX = engineOwned ? bestLastX : bestLastX + residualX;
        double moveY = engineOwned ? bestLastY : bestLastY + residualY;
        double moveZ = engineOwned ? bestLastZ : bestLastZ + residualZ;
        seedFromMovement(state, moveX, moveY, moveZ, bestLastStartGround, bestLastFriction,
                floor && moveY <= 0.0 && Math.abs(update.toY() - floorTop) < GROUND_EPSILON,
                gravity, slowFalling, positionPackets, epoch);
        return null;
    }

    /**
     * Position-domain accuracy (detection-strength fix, plan item 1): the previous packet's
     * <em>predicted</em> end plus this packet's predicted delta is compared with the reported
     * position. A constant per-tick excess (a speed/fly hack below the per-packet tolerance) then
     * accumulates linearly instead of being reset to the client's value every tick. The chain is
     * only extended while packets are directly consecutive and in the same world epoch; skips,
     * teleports and hard desyncs restart it.
     */
    private static void updateChain(PredictionState state, MovementUpdate update, long positionPackets,
                                    int epoch, int ticks, double predictedX, double predictedY,
                                    double predictedZ, boolean predicted, SimulationResult out) {
        double expectedX;
        double expectedY;
        double expectedZ;
        int chainTicks;
        if (state.chainValidFor(positionPackets, epoch)) {
            expectedX = state.chainX + predictedX;
            expectedY = state.chainY + predictedY;
            expectedZ = state.chainZ + predictedZ;
            chainTicks = state.chainTicks + ticks;
        } else {
            expectedX = update.fromX() + predictedX;
            expectedY = update.fromY() + predictedY;
            expectedZ = update.fromZ() + predictedZ;
            chainTicks = ticks;
        }
        double errorX = update.toX() - expectedX;
        double errorY = update.toY() - expectedY;
        double errorZ = update.toZ() - expectedZ;
        double cumulative = Math.sqrt(errorX * errorX + errorY * errorY + errorZ * errorZ);
        if (cumulative > MAX_CUMULATIVE_OFFSET) {
            // Teleport-like desync (or an unmodelled impulse the gate did not catch): restart the
            // chain, the per-packet checks own this situation.
            state.invalidateChain();
            out.completeCumulative(0.0, 0);
            return;
        }
        state.chain(expectedX, expectedY, expectedZ, positionPackets, epoch, chainTicks);
        out.completeCumulative(cumulative, chainTicks);
    }

    /** Per-player conditions the engine does not model. */
    private String gate(PlayerData player, MovementUpdate update) {
        if (player.vehicle().inVehicle()) {
            return SKIP_VEHICLE;
        }
        if (player.abilities().flying()) {
            return SKIP_FLYING;
        }
        GameMode mode = player.abilities().gameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            return SKIP_GAMEMODE;
        }
        PoseState pose = player.pose();
        if (pose.gliding() || pose.glideRequested()) {
            return SKIP_ELYTRA;
        }
        if (pose.swimming()) {
            return SKIP_SWIMMING;
        }
        if (player.exemptions().anyActive(UNMODELLED_IMPULSES)) {
            return SKIP_UNMODELLED_IMPULSE;
        }
        if (player.effects().has(TrackedEffect.LEVITATION)) {
            return SKIP_LEVITATION;
        }
        TeleportQueue queue = teleportQueue(player);
        if (queue != null && queue.hasPending()) {
            return SKIP_AWAITING_TELEPORT;
        }
        AttributeState attributes = player.attributes();
        if (attributes.pending()) {
            return SKIP_ATTRIBUTES_PENDING;
        }
        EffectState effects = player.effects();
        if (effects.anyPending()) {
            for (TrackedEffect effect : MOVEMENT_EFFECTS) {
                if (effects.pending(effect)) {
                    return SKIP_EFFECTS_PENDING;
                }
            }
        }
        if (attributes.scale() != AttributeState.DEFAULT_SCALE
                || attributes.frictionModifier() != AttributeState.DEFAULT_FRICTION_MODIFIER
                || attributes.airDragModifier() != AttributeState.DEFAULT_AIR_DRAG_MODIFIER
                || attributes.bounciness() != AttributeState.DEFAULT_BOUNCINESS) {
            return SKIP_ATTRIBUTES;
        }
        if (!player.movement().rotationKnown()) {
            return SKIP_ROTATION;
        }
        if (update.elapsedTicks() > MAX_TICKS) {
            return SKIP_TOO_MANY_TICKS;
        }
        return null;
    }

    /**
     * Without a seed, a single-tick packet can still seed the next one if the environment is
     * simple: the reported movement is the motion of that tick.
     */
    private String reseedAfterSkip(PlayerData player, MovementUpdate update, PredictionState state,
                                   long positionPackets, int epoch, double gravity, boolean slowFalling,
                                   String reason) {
        state.invalidateSeed();
        if (update.elapsedTicks() != 1) {
            return reason;
        }
        double fromY = update.fromY();
        double minFeet = Math.min(fromY, update.toY());
        double reach = UNCONFIRMED_HALF_WIDTH + SCAN_MARGIN;
        int scanResult = state.scan.scan(player.component(worldKey),
                Math.min(update.fromX(), update.toX()) - reach, Math.max(update.fromX(), update.toX()) + reach,
                Math.min(update.fromZ(), update.toZ()) - reach, Math.max(update.fromZ(), update.toZ()) + reach,
                minFeet, Math.max(fromY, update.toY()) + UNCONFIRMED_HEIGHT, minFeet);
        if (scanResult != SupportScan.OK) {
            return reason;
        }
        SupportScan scan = state.scan;
        boolean floor = scan.floorFound();
        boolean startGround = floor && Math.abs(fromY - scan.floorTop()) < GROUND_EPSILON;
        double dy = update.deltaY();
        seedFromMovement(state, update.deltaX(), dy, update.deltaZ(), startGround,
                startGround ? (float) scan.friction(update.fromX(), update.fromZ()) : AIR_SLIPPERINESS,
                floor && dy <= 0.0 && Math.abs(update.toY() - scan.floorTop()) < GROUND_EPSILON,
                gravity, slowFalling, positionPackets, epoch);
        return reason;
    }

    private static void seedFromMovement(PredictionState state, double moveX, double moveY, double moveZ,
                                         boolean startGround, double friction, boolean endGround,
                                         double gravity, boolean slowFalling, long positionPackets, int epoch) {
        double factor = horizontalFactor(startGround, friction);
        state.seed(moveX * factor, afterGravity(endGround ? 0.0 : moveY, gravity, slowFalling),
                moveZ * factor, positionPackets, epoch);
    }

    /**
     * Newest {@link MotionHistory} snapshot as a fallback seed, only when the previous packet was a
     * single tick that the engine saw (otherwise its delta may be a first-position/teleport jump).
     */
    private MotionHistory.TickSnapshot previousSnapshot(PlayerData player, PredictionState state,
                                                        long positionPackets, int epoch) {
        ComponentKey<MotionHistory> historyKey = lateKeys(player).history;
        if (historyKey == null || state.lastSeenPositionPackets != positionPackets - 1 || state.lastSeenTicks != 1) {
            return null;
        }
        MotionHistory.TickSnapshot newest = player.component(historyKey).newest();
        if (newest == null || newest.worldEpoch() != epoch) {
            return null;
        }
        return newest;
    }

    private static void refreshAttributeCache(PredictionState state, AttributeState attributes) {
        if (state.attributeVersion == attributes.version()) {
            return;
        }
        state.attributeVersion = attributes.version();
        // Sprint is a per-candidate decision, so a sprint modifier echoed by the server is removed.
        state.movementSpeedNoSprint = attributes.valueWithout(AttributeState.MOVEMENT_SPEED,
                UNCONFIRMED_SPRINT_MODIFIER_ID);
        state.speedEffectInAttribute = attributes.hasModifier(AttributeState.MOVEMENT_SPEED,
                UNCONFIRMED_SPEED_EFFECT_MODIFIER_ID);
        state.slownessEffectInAttribute = attributes.hasModifier(AttributeState.MOVEMENT_SPEED,
                UNCONFIRMED_SLOWNESS_EFFECT_MODIFIER_ID);
    }

    /** MCPK "E = (1 + 0.2 × Speed) × (1 − 0.15 × Slowness)", at least 0. */
    private static double effectFactor(PredictionState state, EffectState effects) {
        double speed = state.speedEffectInAttribute ? 0 : effects.level(TrackedEffect.SPEED);
        double slowness = state.slownessEffectInAttribute ? 0 : effects.level(TrackedEffect.SLOWNESS);
        return Math.max(0.0, (1.0 + SPEED_PER_LEVEL * speed) * (1.0 - SLOWNESS_PER_LEVEL * slowness));
    }

    /** MCPK/Wiki Entity: horizontal drag 0.91, multiplied by the block slipperiness on ground. */
    private static double horizontalFactor(boolean startGround, double friction) {
        return startGround ? (float) friction * (float) HORIZONTAL_DRAG : HORIZONTAL_DRAG;
    }

    /** Wiki Entity / MCPK-V: (vy − gravity) × 0.98; Slow Falling caps gravity at 0.01 while vy ≤ 0. */
    private static double afterGravity(double vy, double gravity, boolean slowFalling) {
        double effective = slowFalling && vy <= 0.0 ? Math.min(gravity, SLOW_FALLING_GRAVITY) : gravity;
        return (vy - effective) * VERTICAL_DRAG;
    }

    /** Largest downward distance over {@code ticks} starting with {@code vy}, plus one block. */
    private static double fallBound(double vy, double gravity, int ticks) {
        double fall = 0.0;
        double motion = vy;
        for (int i = 0; i < ticks; i++) {
            if (motion < 0.0) {
                fall -= motion;
            }
            motion = (motion - Math.max(0.0, gravity)) * VERTICAL_DRAG;
        }
        return fall + 1.0;
    }

    private static double composeX(VelocityState velocity, int applied, double seed) {
        double value = seed;
        for (int i = 0; i < applied; i++) {
            value = velocity.kind(i) == VelocityState.Kind.SET ? velocity.x(i) : value + velocity.x(i);
        }
        return value;
    }

    private static double composeY(VelocityState velocity, int applied, double seed) {
        double value = seed;
        for (int i = 0; i < applied; i++) {
            value = velocity.kind(i) == VelocityState.Kind.SET ? velocity.y(i) : value + velocity.y(i);
        }
        return value;
    }

    private static double composeZ(VelocityState velocity, int applied, double seed) {
        double value = seed;
        for (int i = 0; i < applied; i++) {
            value = velocity.kind(i) == VelocityState.Kind.SET ? velocity.z(i) : value + velocity.z(i);
        }
        return value;
    }

    /** Candidate index without item-use slowdown, see {@link #candidateIndex(int, int, int, int, int, int)}. */
    public static int candidateIndex(int velocities, int jump, int sprint, int forward, int strafe) {
        return candidateIndex(velocities, jump, sprint, 0, forward, strafe);
    }

    /**
     * Candidate encoding:
     * {@code (((velocities * 2 + jump) * 2 + sprint) * 2 + itemUse) * 9 + (forward + 1) * 3 + (strafe + 1)},
     * where {@code velocities} is the number of offered server velocities applied.
     */
    public static int candidateIndex(int velocities, int jump, int sprint, int itemUse, int forward, int strafe) {
        return (((velocities * 2 + jump) * 2 + sprint) * 2 + itemUse) * 9 + (forward + 1) * 3 + (strafe + 1);
    }

    private TeleportQueue teleportQueue(PlayerData player) {
        ComponentKey<TeleportQueue> key = lateKeys(player).teleport;
        return key == null ? null : player.component(key);
    }

    /** Keys of components other modules register; resolved once from the immutable setup. */
    private LateKeys lateKeys(PlayerData player) {
        LateKeys keys = lateKeys;
        if (keys == null) {
            ModuleSetup setup = player.context().setup();
            keys = new LateKeys(TeleportQueue.keyOf(setup), historyKeyOf(setup));
            lateKeys = keys;
        }
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static ComponentKey<MotionHistory> historyKeyOf(ModuleSetup setup) {
        for (ComponentKey<?> key : setup.components()) {
            if (key.type() == MotionHistory.class) {
                return (ComponentKey<MotionHistory>) key;
            }
        }
        return null;
    }

    private record LateKeys(ComponentKey<TeleportQueue> teleport, ComponentKey<MotionHistory> history) {
    }
}
