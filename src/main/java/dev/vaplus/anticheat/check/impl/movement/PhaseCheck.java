package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.check.BlockCheck;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.PositionCheck;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.AttributeState;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.world.BlockInteraction;
import dev.vaplus.anticheat.world.mirror.BlockProperties;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.Objects;

/**
 * Phase / NoClip / VClip: the player's collision box moved into or through blocks that are known,
 * settled, full solid cubes.
 *
 * <h2>Signal</h2>
 * Two geometric tests against the per-player {@link WorldMirror}, both on a box that is shrunk by
 * {@code options.shrink} on every side:
 * <ul>
 *   <li><b>overlap</b> — the box at the new position intersects a solid full cube. Vanilla collision
 *       resolves every move before the client reports it, so a legit client never ends a move inside
 *       a full cube it started outside of (weight {@code options.overlap-weight}).</li>
 *   <li><b>sweep</b> — vanilla moves a box axis by axis (one axis at a time, each fully resolved), so
 *       a one-tick move is legit only if at least one of the six axis orders (X/Y/Z permutations)
 *       sweeps through free space. Each single-axis sweep of a box is exactly an axis-aligned box, so
 *       the test is exact and cheap. Only packets that cover one client tick are swept; a packet that
 *       covers several ticks can follow a curved path and is judged by the overlap test only. When
 *       every order crosses a solid cube the move tunnelled through it — the classic vclip down
 *       through a floor, up through a ceiling or a horizontal clip through a wall (weight
 *       {@code options.sweep-weight}, default high enough that one tunnelling packet flags).</li>
 * </ul>
 *
 * <h2>Why legit play does not trigger it</h2>
 * <ul>
 *   <li>Only blocks that the {@code VanillaBlocks} metadata marks as known {@code FULL}
 *       collision, height 1.0, not liquid/waterlogged and without a shape/state uncertainty count as
 *       solid ({@link #solid}). Unknown chunks, unlisted blocks (doors, trapdoors, fence gates,
 *       slabs, stairs, moving pistons, soul sand, honey, beds, ...) are never solid: a {@code BlockKey}
 *       carries no state properties, so their shape is unknown.</li>
 *   <li>Nothing is judged while a world uncertainty window touches the region (WorldMirror pending
 *       changes: unacknowledged block/chunk updates or a whole-world window after join/respawn), nor
 *       within {@code options.interaction-grace-millis} + smoothed RTT of the player's own dig or
 *       placement next to the path (client-predicted breaking removes a block before the server's
 *       update exists; a rejected break can leave a short-lived ghost block).</li>
 *   <li>A start position that already overlaps a solid block is not judged (vanilla pushes players
 *       out of blocks that appeared around them: falling blocks, growth, pistons, plugins) — unless
 *       the previous packet itself was a judged clip into that wall, so a phase client cannot keep
 *       walking inside the wall unjudged after its first flagged step.</li>
 *   <li>Teleports (incl. ender pearl/chorus fruit, server setbacks), the first position, vehicles
 *       and {@code options.dismount-grace-ticks} after a dismount, creative/spectator (category
 *       exemption), join/respawn/world change and pending attribute windows are skipped.</li>
 *   <li>Box size: width {@code 0.6 x scale}; height is always the smallest pose height
 *       {@code 0.6 x scale} (crawl/swim/glide), because {@code PoseState} only knows the sneak flag
 *       and not the entity pose — a crawling player in a one-block tunnel must never overlap the
 *       ceiling. Sneaking (1.5) and standing (1.8) are therefore covered by the smaller box too.</li>
 * </ul>
 *
 * <h2>Known bypasses / limits</h2>
 * Clipping only the upper body (head into a ceiling) is invisible to the 0.6-high box; clips
 * through anything that is not a listed full cube (slabs, doors, glass panes, unlisted blocks) are
 * not seen; packets covering several ticks are only overlap-tested, so a client that batches ticks
 * can tunnel through a thin wall in a multi-tick packet without ending inside it; a clip done while
 * a world window is open next to the player is not judged. The 0.6/1.8 box sizes are vanilla player
 * dimensions (Minecraft Wiki "Player", hitbox); the axis-by-axis collision order is how vanilla's
 * {@code Entity.collide} resolves moves — the exact order in each version is UNCONFIRMED, which is
 * why all six orders are accepted. All numeric option defaults are TUNING values.
 */
public final class PhaseCheck extends Check implements PositionCheck, BlockCheck {

    /** Vanilla player hitbox width (Minecraft Wiki, Player). */
    public static final double PLAYER_WIDTH = 0.6;
    /** Smallest vanilla player pose height (swimming/crawling/gliding, Minecraft Wiki, Player). */
    public static final double MIN_POSE_HEIGHT = 0.6;

    /** TUNING: the box is shrunk by this much on every side (float noise, touching faces). */
    public static final double DEFAULT_SHRINK = 0.05;
    /** TUNING: buffer weight of ending inside a solid cube. */
    public static final double DEFAULT_OVERLAP_WEIGHT = 1.0;
    /** TUNING: buffer weight of tunnelling through a solid cube in one tick. */
    public static final double DEFAULT_SWEEP_WEIGHT = 2.0;
    /** TUNING: own digs/placements near the path suppress judging for this long (plus smoothed RTT). */
    public static final long DEFAULT_INTERACTION_GRACE_MILLIS = 3000L;
    /** TUNING: ticks after leaving a vehicle (dismount positions are chosen by the game, not the player). */
    public static final int DEFAULT_DISMOUNT_GRACE_TICKS = 20;
    /** TUNING: largest block volume scanned per packet; larger moves are only overlap-tested. */
    public static final int DEFAULT_MAX_SCAN_BLOCKS = 4096;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("phase", CheckCategory.MOVEMENT)
            .displayName("Phase")
            .description("Moves into or through known solid full blocks")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.MOVEMENT)
                    .withBufferLimit(1.0)
                    .withBufferDecay(0.05)
                    .withBufferMax(4.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ATTRIBUTE_CHANGE,
                    ExemptionCause.VEHICLE, ExemptionCause.PORTAL)
            .build();

    /** Recent own block interactions remembered (ring). */
    static final int INTERACTION_SLOTS = 16;
    /** Interactions within this many blocks of the scanned region suppress judging. */
    static final int INTERACTION_MARGIN = 2;

    /** Axis orders: 0 = X, 1 = Y, 2 = Z. */
    private static final int[][] ORDERS = {
            {1, 0, 2}, {1, 2, 0}, {0, 1, 2}, {0, 2, 1}, {2, 1, 0}, {2, 0, 1}
    };

    private final ComponentKey<WorldMirror> worldKey;

    private double shrink = DEFAULT_SHRINK;
    private double overlapWeight = DEFAULT_OVERLAP_WEIGHT;
    private double sweepWeight = DEFAULT_SWEEP_WEIGHT;
    private long interactionGraceNanos = DEFAULT_INTERACTION_GRACE_MILLIS * 1_000_000L;
    private int dismountGraceTicks = DEFAULT_DISMOUNT_GRACE_TICKS;
    private int maxScanBlocks = DEFAULT_MAX_SCAN_BLOCKS;
    private boolean checkSweep = true;

    private final int[] interactionX = new int[INTERACTION_SLOTS];
    private final int[] interactionY = new int[INTERACTION_SLOTS];
    private final int[] interactionZ = new int[INTERACTION_SLOTS];
    private final long[] interactionAt = new long[INTERACTION_SLOTS];
    private int interactionCount;
    private int interactionNext;

    /** The previous packet was judged as ending inside a solid cube. */
    private boolean clipped;

    // Per-packet scratch box (half extents and the current position), no allocation.
    private double half;
    private double height;
    private double curX;
    private double curY;
    private double curZ;

    public PhaseCheck(PlayerData player, ComponentKey<WorldMirror> worldKey) {
        super(player, DESCRIPTOR);
        this.worldKey = Objects.requireNonNull(worldKey, "worldKey");
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        double s = MovementOptions.tolerance(options, "shrink", DEFAULT_SHRINK);
        shrink = s < 0.25 ? s : DEFAULT_SHRINK;
        overlapWeight = positive(options, "overlap-weight", DEFAULT_OVERLAP_WEIGHT);
        sweepWeight = positive(options, "sweep-weight", DEFAULT_SWEEP_WEIGHT);
        long grace = options.getLong("interaction-grace-millis", DEFAULT_INTERACTION_GRACE_MILLIS);
        interactionGraceNanos = (grace >= 0 && grace <= 60_000L ? grace : DEFAULT_INTERACTION_GRACE_MILLIS) * 1_000_000L;
        int dismount = options.getInt("dismount-grace-ticks", DEFAULT_DISMOUNT_GRACE_TICKS);
        dismountGraceTicks = dismount >= 0 && dismount <= 1200 ? dismount : DEFAULT_DISMOUNT_GRACE_TICKS;
        int scan = options.getInt("max-scan-blocks", DEFAULT_MAX_SCAN_BLOCKS);
        maxScanBlocks = scan >= 64 && scan <= 65_536 ? scan : DEFAULT_MAX_SCAN_BLOCKS;
        checkSweep = options.getBoolean("check-sweep", true);
    }

    private static double positive(CheckOptions options, String key, double fallback) {
        double value = options.getDouble(key, fallback);
        return Double.isFinite(value) && value > 0.0 ? value : fallback;
    }

    @Override
    protected void onReset() {
        interactionCount = 0;
        interactionNext = 0;
        clipped = false;
    }

    @Override
    public void onBlockInteraction(BlockInteraction interaction) {
        BlockInteraction.Kind kind = interaction.kind();
        if (kind != BlockInteraction.Kind.PLACE && kind != BlockInteraction.Kind.DIG_START
                && kind != BlockInteraction.Kind.DIG_FINISH && kind != BlockInteraction.Kind.DIG_CANCEL) {
            return;
        }
        int slot = interactionNext;
        interactionX[slot] = interaction.blockX();
        interactionY[slot] = interaction.blockY();
        interactionZ[slot] = interaction.blockZ();
        interactionAt[slot] = interaction.timestampNanos();
        interactionNext = (slot + 1) % INTERACTION_SLOTS;
        if (interactionCount < INTERACTION_SLOTS) {
            interactionCount++;
        }
    }

    @Override
    public void onPosition(MovementUpdate update) {
        if (!update.positionChanged() || update.firstPosition() || update.teleport()) {
            clipped = false;
            return;
        }
        if (isExempt() || player.vehicle().inVehicle()
                || player.vehicle().ticksSinceDismount() < dismountGraceTicks
                || player.attributes().pending()) {
            clipped = false;
            return;
        }
        WorldMirror world = player.component(worldKey);
        if (world == null) {
            return;
        }
        double scale = player.attributes().value(AttributeState.SCALE);
        if (!(scale > 0.0) || !Double.isFinite(scale)) {
            return;
        }
        half = PLAYER_WIDTH * scale * 0.5 - shrink;
        height = MIN_POSE_HEIGHT * scale - 2.0 * shrink;
        if (!(half > 0.01) || !(height > 0.01)) {
            return;
        }
        double fromX = update.fromX();
        double fromY = update.fromY();
        double fromZ = update.fromZ();
        double toX = update.toX();
        double toY = update.toY();
        double toZ = update.toZ();

        // Region touched by the whole move (union of the start and end boxes), in block coordinates.
        int minX = floor(Math.min(fromX, toX) - half);
        int maxX = floor(Math.max(fromX, toX) + half);
        int minY = floor(Math.min(fromY, toY) + shrink);
        int maxY = floor(Math.max(fromY, toY) + shrink + height);
        int minZ = floor(Math.min(fromZ, toZ) - half);
        int maxZ = floor(Math.max(fromZ, toZ) + half);

        if (world.pending().intersects(minX - 1, minY - 1, minZ - 1, maxX + 1, maxY + 1, maxZ + 1)) {
            clipped = false;
            return;
        }
        if (recentInteractionNear(update.timestampNanos(), minX, minY, minZ, maxX, maxY, maxZ)) {
            clipped = false;
            return;
        }

        boolean startInside = boxHitsSolid(world, fromX, fromY, fromZ, fromX, fromY, fromZ);
        boolean endInside = boxHitsSolid(world, toX, toY, toZ, toX, toY, toZ);
        if (startInside && !clipped) {
            // Legit push-out from a block that appeared around the player: not judged.
            return;
        }

        boolean tunnelled = false;
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (checkSweep && !startInside && !endInside && update.elapsedTicks() <= 1 && volume <= maxScanBlocks) {
            tunnelled = tunnelled(world, fromX, fromY, fromZ, toX, toY, toZ);
        }

        if (!endInside && !tunnelled) {
            clipped = false;
            lowerBuffer();
            return;
        }
        clipped = endInside;
        double weight = tunnelled ? sweepWeight : overlapWeight;
        String kind = tunnelled ? "through" : startInside ? "inside" : "into";
        if (debugging()) {
            debug(kind + " dx=" + MovementOptions.number(update.deltaX())
                    + " dy=" + MovementOptions.number(update.deltaY())
                    + " dz=" + MovementOptions.number(update.deltaZ())
                    + " buffer=" + MovementOptions.number(buffer()));
        }
        if (raiseBuffer(weight)) {
            violation(kind
                    + " dx=" + MovementOptions.number(update.deltaX())
                    + " dy=" + MovementOptions.number(update.deltaY())
                    + " dz=" + MovementOptions.number(update.deltaZ())
                    + " to=" + MovementOptions.number(toX) + "," + MovementOptions.number(toY) + ","
                    + MovementOptions.number(toZ)
                    + " ticks=" + update.elapsedTicks()
                    + " scale=" + MovementOptions.number(scale));
        }
    }

    /**
     * Whether every axis order of the move crosses a solid cube. Starts with the union box of the
     * whole move: when it holds no solid cube at all (the common case) no order can be blocked.
     */
    private boolean tunnelled(WorldMirror world, double fromX, double fromY, double fromZ,
                              double toX, double toY, double toZ) {
        if (!boxHitsSolid(world, fromX, fromY, fromZ, toX, toY, toZ)) {
            return false;
        }
        for (int[] order : ORDERS) {
            curX = fromX;
            curY = fromY;
            curZ = fromZ;
            boolean blocked = false;
            for (int axis : order) {
                double nextX = axis == 0 ? toX : curX;
                double nextY = axis == 1 ? toY : curY;
                double nextZ = axis == 2 ? toZ : curZ;
                if (boxHitsSolid(world, curX, curY, curZ, nextX, nextY, nextZ)) {
                    blocked = true;
                    break;
                }
                curX = nextX;
                curY = nextY;
                curZ = nextZ;
            }
            if (!blocked) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the union of the (shrunk) boxes at the two feet positions intersects a solid cube.
     * With equal positions this is the plain box test; with positions differing on one axis it is
     * the exact swept volume of that single-axis move.
     */
    private boolean boxHitsSolid(WorldMirror world, double ax, double ay, double az,
                                 double bx, double by, double bz) {
        double lowX = Math.min(ax, bx) - half;
        double highX = Math.max(ax, bx) + half;
        double lowY = Math.min(ay, by) + shrink;
        double highY = Math.max(ay, by) + shrink + height;
        double lowZ = Math.min(az, bz) - half;
        double highZ = Math.max(az, bz) + half;
        int minX = floor(lowX);
        int maxX = ceil(highX) - 1;
        int minY = floor(lowY);
        int maxY = ceil(highY) - 1;
        int minZ = floor(lowZ);
        int maxZ = ceil(highZ) - 1;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (solid(world.getBlock(x, y, z))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean recentInteractionNear(long nowNanos, int minX, int minY, int minZ,
                                          int maxX, int maxY, int maxZ) {
        if (interactionCount == 0) {
            return false;
        }
        long rttMillis = player.acks().smoothedRttMillis();
        long grace = interactionGraceNanos + (rttMillis > 0 ? rttMillis * 1_000_000L : 0L);
        for (int i = 0; i < interactionCount; i++) {
            if (nowNanos - interactionAt[i] > grace) {
                continue;
            }
            int x = interactionX[i];
            int y = interactionY[i];
            int z = interactionZ[i];
            if (x >= minX - INTERACTION_MARGIN && x <= maxX + INTERACTION_MARGIN
                    && y >= minY - INTERACTION_MARGIN && y <= maxY + INTERACTION_MARGIN
                    && z >= minZ - INTERACTION_MARGIN && z <= maxZ + INTERACTION_MARGIN) {
                return true;
            }
        }
        return false;
    }

    /**
     * The only blocks this check treats as walls: mirrored, full-height {@code FULL} collision, no
     * liquid (waterlogged states are liquid) and no shape/state/metadata uncertainty. Friction-only
     * uncertainties (packed ice) do not change the collision shape and are accepted.
     */
    public static boolean solid(BlockSnapshot block) {
        if (!block.known()) {
            return false;
        }
        BlockProperties properties = block.properties();
        if (properties.collision() != BlockProperties.Collision.FULL
                || properties.collisionHeight() < 1.0
                || properties.liquid() != BlockProperties.Liquid.NONE) {
            return false;
        }
        if (properties.uncertainties().isEmpty()) {
            return true;
        }
        return !properties.uncertainties().contains(BlockProperties.Uncertainty.UNCONFIRMED_COLLISION_SHAPE)
                && !properties.uncertainties().contains(BlockProperties.Uncertainty.UNCONFIRMED_BLOCK_PROPERTIES)
                && !properties.uncertainties().contains(BlockProperties.Uncertainty.UNCONFIRMED_MISSING_BLOCK)
                && !properties.uncertainties().contains(BlockProperties.Uncertainty.UNCONFIRMED_BLOCK_STATE);
    }

    /** Whether the previous judged packet ended inside a solid cube (tests, diagnostics). */
    public boolean clipped() {
        return clipped;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static int ceil(double value) {
        return (int) Math.ceil(value);
    }
}
