package dev.vaplus.anticheat.check.impl.movement;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.type.EntityType;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientVehicleMove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityPositionSync;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerJoinGame;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.OutboundPacketCheck;
import dev.vaplus.anticheat.check.PacketCheck;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.world.mirror.BlockProperties;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.Arrays;
import java.util.Locale;

/**
 * Vehicle: BoatFly, vehicle speed and mount "high jump", judged from the vehicle position the
 * client reports in {@code VEHICLE_MOVE} (the client, not the server, simulates a vehicle it
 * controls). EXPERIMENTAL: all limits are generous reasoned values.
 *
 * <p>Mount tracking is done here (nothing else wires {@code VehicleState.mount}): the own entity
 * id comes from the outbound join packet, vehicle kinds from {@code SPAWN_ENTITY}, and mount and
 * dismount from {@code SET_PASSENGERS}. Without a known kind nothing is judged.
 *
 * <p>Signals (each can be switched off through its option):
 * <ul>
 *   <li><b>speed</b> - horizontal distance per client tick above a per-kind bound. Boats: vanilla
 *       terminal speed is thrust (0.04) / (1 - friction); water/air (0.9) gives 0.4 blocks/tick
 *       (8 m/s), ice (0.98) 2.0 and blue ice (0.989) about 3.6. The check therefore judges boats
 *       only while no sampled block under the hull is slippery or unknown, and for
 *       {@code ice-grace-ticks} afterwards (momentum). Other mounts use one generous bound because
 *       their speed is a server-side attribute.</li>
 *   <li><b>air-hover / air-rise</b> - only while every block cell around the vehicle is known air
 *       (no liquid, bubble column, solid, climbable or special block). A boat there is in free
 *       fall; staying level or rising for {@code max-hover-ticks} ticks, or rising more than
 *       {@code max-air-rise} blocks above where it left support, is impossible (the only upward
 *       source, a bubble column launch of at most 0.7 blocks/tick, gives roughly 5 blocks).
 *       Horses and other mounts use only the rise bound ({@code max-jump-height} for horses).</li>
 * </ul>
 * Skipped: exempt players, unknown chunks/blocks, the first {@code mount-grace-ticks} after
 * mounting, and {@code sync-grace-millis} (plus twice the round trip) after a server correction
 * ({@code VEHICLE_MOVE}, a teleport that moves the vehicle away from its reported position, or a
 * vehicle velocity). Minecarts and flying mounts are not client-controlled/modelled and ignored.
 */
public final class VehicleCheck extends Check implements PacketCheck, OutboundPacketCheck {

    /** TUNING: boat blocks/tick (vanilla water/air terminal speed 0.4, plus current slack). */
    public static final double DEFAULT_MAX_BOAT_SPEED = 0.65;
    /** TUNING: horses and other mounts, blocks/tick (fastest vanilla horse about 0.73). */
    public static final double DEFAULT_MAX_MOUNT_SPEED = 1.2;
    /** TUNING: vehicle packets of "slippery or unknown surface" memory for boats. */
    public static final int DEFAULT_ICE_GRACE_TICKS = 40;
    /** TUNING: free-air ticks a boat may stay level or rise (the apex of a launch is about 2). */
    public static final int DEFAULT_MAX_HOVER_TICKS = 24;
    /** TUNING: blocks a boat may rise after leaving support (physical maximum is about 5). */
    public static final double DEFAULT_MAX_AIR_RISE = 8.0;
    /** TUNING: blocks a mount may rise after leaving the ground (vanilla max horse jump about 5.3). */
    public static final double DEFAULT_MAX_JUMP_HEIGHT = 6.5;
    public static final int DEFAULT_MOUNT_GRACE_TICKS = 10;
    public static final double DEFAULT_SYNC_GRACE_MILLIS = 1000.0;

    /** A boat dy above this (blocks/tick) counts as "not falling". */
    static final double HOVER_DY = -0.01;
    /** Client ticks between two vehicle packets after which the baseline is dropped (client hitch). */
    static final int MAX_PACKET_GAP_TICKS = 20;
    /** Distance of an outbound sync target from the reported position that counts as a correction. */
    static final double SYNC_DISTANCE = 1.5;
    /** Slack added to the horizontal bound (rounding, collisions, other vehicles pushing). */
    static final double SPEED_SLACK = 0.1;

    static final int SURFACE_NOT_FREE = 1;
    static final int SURFACE_UNKNOWN = 2;
    static final int SURFACE_SLIPPERY = 4;

    static final int KIND_NONE = 0;
    static final int KIND_BOAT = 1;
    static final int KIND_HORSE = 2;
    static final int KIND_OTHER = 3;

    private static final int CACHE = 64;
    private static final double HALF_WIDTH = 0.7;
    private static final double MILLI = 1_000_000.0;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("vehicle", CheckCategory.MOVEMENT)
            .displayName("Vehicle")
            .description("BoatFly, vehicle speed and mount high-jump from client-reported vehicle moves")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.MOVEMENT)
                    .withBufferLimit(5.0)
                    .withBufferDecay(0.25)
                    .withBufferMax(10.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.KNOCKBACK,
                    ExemptionCause.EXPLOSION, ExemptionCause.PORTAL)
            .build();

    private final ComponentKey<WorldMirror> worldKey;

    private double maxBoatSpeed = DEFAULT_MAX_BOAT_SPEED;
    private double maxMountSpeed = DEFAULT_MAX_MOUNT_SPEED;
    private int iceGraceTicks = DEFAULT_ICE_GRACE_TICKS;
    private int maxHoverTicks = DEFAULT_MAX_HOVER_TICKS;
    private double maxAirRise = DEFAULT_MAX_AIR_RISE;
    private double maxJumpHeight = DEFAULT_MAX_JUMP_HEIGHT;
    private int mountGraceTicks = DEFAULT_MOUNT_GRACE_TICKS;
    private long syncGraceNanos = (long) (DEFAULT_SYNC_GRACE_MILLIS * MILLI);
    private boolean checkSpeed = true;
    private boolean checkHover = true;
    private boolean checkRise = true;

    private int selfId = -1;
    private final int[] cacheIds = new int[CACHE];
    private final byte[] cacheKinds = new byte[CACHE];
    private int cacheNext;

    private int vehicleId = -1;
    private int kind = KIND_NONE;

    private boolean baselineValid;
    private double lastX;
    private double lastY;
    private double lastZ;
    private long lastTick;
    private int graceTicks;
    private long graceUntilNanos;
    private int slipperyTicks;
    private int airTicks;
    private int hoverTicks;
    private double airStartY;

    public VehicleCheck(PlayerData player, ComponentKey<WorldMirror> worldKey) {
        super(player, DESCRIPTOR);
        this.worldKey = worldKey;
        Arrays.fill(cacheIds, -1);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        maxBoatSpeed = positive(options.getDouble("max-boat-speed", DEFAULT_MAX_BOAT_SPEED),
                DEFAULT_MAX_BOAT_SPEED, 20.0);
        maxMountSpeed = positive(options.getDouble("max-mount-speed", DEFAULT_MAX_MOUNT_SPEED),
                DEFAULT_MAX_MOUNT_SPEED, 20.0);
        maxAirRise = positive(options.getDouble("max-air-rise", DEFAULT_MAX_AIR_RISE),
                DEFAULT_MAX_AIR_RISE, 256.0);
        maxJumpHeight = positive(options.getDouble("max-jump-height", DEFAULT_MAX_JUMP_HEIGHT),
                DEFAULT_MAX_JUMP_HEIGHT, 256.0);
        iceGraceTicks = bounded(options.getInt("ice-grace-ticks", DEFAULT_ICE_GRACE_TICKS),
                DEFAULT_ICE_GRACE_TICKS, 1_200);
        maxHoverTicks = bounded(options.getInt("max-hover-ticks", DEFAULT_MAX_HOVER_TICKS),
                DEFAULT_MAX_HOVER_TICKS, 1_200);
        mountGraceTicks = bounded(options.getInt("mount-grace-ticks", DEFAULT_MOUNT_GRACE_TICKS),
                DEFAULT_MOUNT_GRACE_TICKS, 1_200);
        double millis = options.getDouble("sync-grace-millis", DEFAULT_SYNC_GRACE_MILLIS);
        if (!Double.isFinite(millis) || millis < 0.0 || millis > 60_000.0) {
            millis = DEFAULT_SYNC_GRACE_MILLIS;
        }
        syncGraceNanos = (long) (millis * MILLI);
        checkSpeed = options.getBoolean("check-speed", true);
        checkHover = options.getBoolean("check-hover", true);
        checkRise = options.getBoolean("check-rise", true);
    }

    private static double positive(double value, double fallback, double max) {
        return Double.isFinite(value) && value > 0.0 && value <= max ? value : fallback;
    }

    private static int bounded(int value, int fallback, int max) {
        return value >= 0 && value <= max ? value : fallback;
    }

    @Override
    protected void onReset() {
        dismount();
    }

    // ---------------------------------------------------------------- packet entry points

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.VEHICLE_MOVE) {
            return;
        }
        Vector3d position = new WrapperPlayClientVehicleMove(event).getPosition();
        onVehicleMove(position.getX(), position.getY(), position.getZ());
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.SET_PASSENGERS) {
            WrapperPlayServerSetPassengers packet = new WrapperPlayServerSetPassengers(event);
            onPassengers(packet.getEntityId(), packet.getPassengers());
        } else if (type == PacketType.Play.Server.SPAWN_ENTITY) {
            WrapperPlayServerSpawnEntity packet = new WrapperPlayServerSpawnEntity(event);
            int spawnKind = kindOf(packet.getEntityType());
            if (spawnKind != KIND_NONE) {
                remember(packet.getEntityId(), spawnKind);
            }
        } else if (type == PacketType.Play.Server.VEHICLE_MOVE) {
            onServerCorrection();
        } else if (type == PacketType.Play.Server.ENTITY_TELEPORT) {
            if (vehicleId >= 0) {
                WrapperPlayServerEntityTeleport packet = new WrapperPlayServerEntityTeleport(event);
                if (packet.getEntityId() == vehicleId) {
                    Vector3d position = packet.getPosition();
                    onVehicleSync(position.getX(), position.getY(), position.getZ());
                }
            }
        } else if (type == PacketType.Play.Server.ENTITY_POSITION_SYNC) {
            if (vehicleId >= 0) {
                WrapperPlayServerEntityPositionSync packet = new WrapperPlayServerEntityPositionSync(event);
                if (packet.getId() == vehicleId) {
                    Vector3d position = packet.getValues().getPosition();
                    onVehicleSync(position.getX(), position.getY(), position.getZ());
                }
            }
        } else if (type == PacketType.Play.Server.ENTITY_VELOCITY) {
            if (vehicleId >= 0 && new WrapperPlayServerEntityVelocity(event).getEntityId() == vehicleId) {
                onServerCorrection();
            }
        } else if (type == PacketType.Play.Server.DESTROY_ENTITIES) {
            onDestroy(new WrapperPlayServerDestroyEntities(event).getEntityIds());
        } else if (type == PacketType.Play.Server.JOIN_GAME) {
            onJoin(new WrapperPlayServerJoinGame(event).getEntityId());
        } else if (type == PacketType.Play.Server.RESPAWN) {
            dismount();
            Arrays.fill(cacheIds, -1);
        }
    }

    // ---------------------------------------------------------------- tracking (primitives)

    void onJoin(int entityId) {
        selfId = entityId;
        Arrays.fill(cacheIds, -1);
        dismount();
    }

    /** Remembers the kind of a spawned vehicle-capable entity. */
    void remember(int entityId, int entityKind) {
        for (int i = 0; i < CACHE; i++) {
            if (cacheIds[i] == entityId) {
                cacheKinds[i] = (byte) entityKind;
                return;
            }
        }
        cacheIds[cacheNext] = entityId;
        cacheKinds[cacheNext] = (byte) entityKind;
        cacheNext = (cacheNext + 1) % CACHE;
    }

    private int lookup(int entityId) {
        for (int i = 0; i < CACHE; i++) {
            if (cacheIds[i] == entityId) {
                return cacheKinds[i];
            }
        }
        return KIND_NONE;
    }

    void onPassengers(int entityId, int[] passengers) {
        boolean ours = false;
        if (selfId >= 0 && passengers != null) {
            for (int passenger : passengers) {
                if (passenger == selfId) {
                    ours = true;
                    break;
                }
            }
        }
        if (ours) {
            if (vehicleId != entityId) {
                mount(entityId, lookup(entityId));
            }
        } else if (vehicleId == entityId) {
            dismount();
        }
    }

    void onDestroy(int[] ids) {
        if (ids == null) {
            return;
        }
        for (int id : ids) {
            if (id == vehicleId) {
                dismount();
            }
            for (int i = 0; i < CACHE; i++) {
                if (cacheIds[i] == id) {
                    cacheIds[i] = -1;
                }
            }
        }
    }

    private void mount(int entityId, int entityKind) {
        vehicleId = entityId;
        kind = entityKind;
        resetMotion();
        graceTicks = mountGraceTicks;
    }

    private void dismount() {
        vehicleId = -1;
        kind = KIND_NONE;
        resetMotion();
    }

    private void resetMotion() {
        baselineValid = false;
        slipperyTicks = 0;
        airTicks = 0;
        hoverTicks = 0;
        graceTicks = 0;
    }

    /** The server corrected the vehicle (move packet or velocity): drop the baseline for a while. */
    void onServerCorrection() {
        if (vehicleId < 0) {
            return;
        }
        baselineValid = false;
        airTicks = 0;
        hoverTicks = 0;
        long rtt = Math.max(0L, player.acks().smoothedRttMillis());
        graceUntilNanos = player.context().time().nanoTime() + syncGraceNanos + 2L * rtt * 1_000_000L;
    }

    /** Server position sync of the vehicle: a correction only when it differs from where it is. */
    void onVehicleSync(double x, double y, double z) {
        if (vehicleId < 0) {
            return;
        }
        if (baselineValid) {
            double dx = x - lastX;
            double dy = y - lastY;
            double dz = z - lastZ;
            if (dx * dx + dy * dy + dz * dz <= SYNC_DISTANCE * SYNC_DISTANCE) {
                return;
            }
        }
        onServerCorrection();
    }

    // ---------------------------------------------------------------- decision logic

    /** One client {@code VEHICLE_MOVE}: position of the vehicle (owner thread). */
    void onVehicleMove(double x, double y, double z) {
        if (vehicleId < 0 || kind == KIND_NONE) {
            return;
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            baselineValid = false;
            return;
        }
        long tick = player.clientTicks();
        long now = player.context().time().nanoTime();
        if (isExempt() || graceTicks > 0 || now - graceUntilNanos < 0L) {
            if (graceTicks > 0) {
                graceTicks--;
            }
            store(x, y, z, tick, false);
            airTicks = 0;
            hoverTicks = 0;
            return;
        }
        boolean boat = kind == KIND_BOAT;
        int surface = sample(player.component(worldKey), x, y, z, boat);
        boolean unknown = (surface & SURFACE_UNKNOWN) != 0;
        if (boat) {
            if (unknown || (surface & SURFACE_SLIPPERY) != 0) {
                slipperyTicks = iceGraceTicks;
            } else if (slipperyTicks > 0) {
                slipperyTicks--;
            }
        }
        long gap = tick - lastTick;
        if (baselineValid && gap <= MAX_PACKET_GAP_TICKS) {
            int ticks = (int) Math.max(1L, gap);
            boolean speedJudgeable = !boat || (slipperyTicks == 0 && !unknown);
            if (checkSpeed && speedJudgeable) {
                judgeSpeed(x - lastX, z - lastZ, ticks);
            }
            judgeAir(surface, y);
        } else {
            airTicks = 0;
            hoverTicks = 0;
        }
        store(x, y, z, tick, true);
    }

    private void store(double x, double y, double z, long tick, boolean valid) {
        lastX = x;
        lastY = y;
        lastZ = z;
        lastTick = tick;
        baselineValid = valid;
    }

    private void judgeSpeed(double dx, double dz, int ticks) {
        double limit = (kind == KIND_BOAT ? maxBoatSpeed : maxMountSpeed) * ticks + SPEED_SLACK;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= limit) {
            lowerBuffer();
            return;
        }
        if (raiseBuffer(distance > 2.0 * limit ? 2.0 : 1.0)) {
            violation("speed kind=" + kindName() + " dist=" + number(distance) + " limit=" + number(limit)
                    + " ticks=" + ticks);
            resetBuffer();
        }
    }

    private void judgeAir(int surface, double y) {
        if (surface != 0) {
            airTicks = 0;
            hoverTicks = 0;
            return;
        }
        if (airTicks == 0) {
            airStartY = lastY;
        }
        airTicks++;
        double dy = y - lastY;
        if (kind == KIND_BOAT) {
            hoverTicks = dy > HOVER_DY ? hoverTicks + 1 : 0;
            if (checkHover && hoverTicks > maxHoverTicks) {
                if (raiseBuffer(1.0)) {
                    violation("air-hover ticks=" + hoverTicks + " dy=" + number(dy));
                    resetBuffer();
                }
                return;
            }
        }
        double rise = y - airStartY;
        double limit = kind == KIND_HORSE ? maxJumpHeight : maxAirRise;
        if (checkRise && rise > limit) {
            if (raiseBuffer(1.0)) {
                violation("air-rise kind=" + kindName() + " rise=" + number(rise) + " limit=" + number(limit)
                        + " airTicks=" + airTicks);
                resetBuffer();
            }
            return;
        }
        if (debugging()) {
            debug("air ticks=" + airTicks + " rise=" + number(rise) + " hover=" + hoverTicks);
        }
        lowerBuffer();
    }

    /**
     * Surface classification around the vehicle: {@link #SURFACE_UNKNOWN} when any cell is unknown
     * or uncertain, {@link #SURFACE_NOT_FREE} when any cell has collision, liquid, a climbable or a
     * special block, {@link #SURFACE_SLIPPERY} when a cell near the floor has friction above the
     * default. 0 means free air.
     */
    static int sample(WorldMirror world, double x, double y, double z, boolean boat) {
        int floor = (int) Math.floor(y - 0.05);
        int top = (int) Math.floor(y + (boat ? 0.6 : 1.8));
        int result = 0;
        for (int column = 0; column < 5; column++) {
            double cx = column == 1 ? x - HALF_WIDTH : column == 2 ? x + HALF_WIDTH : x;
            double cz = column == 3 ? z - HALF_WIDTH : column == 4 ? z + HALF_WIDTH : z;
            int bx = (int) Math.floor(cx);
            int bz = (int) Math.floor(cz);
            for (int by = floor - 1; by <= top; by++) {
                BlockSnapshot block = world.getBlock(bx, by, bz);
                if (!block.known() || block.properties().uncertain()) {
                    return SURFACE_UNKNOWN;
                }
                BlockProperties props = block.properties();
                if (props.collision() != BlockProperties.Collision.EMPTY
                        || props.liquid() != BlockProperties.Liquid.NONE || props.climbable()
                        || !props.flags().isEmpty() || props.speedFactor() != 1.0 || props.jumpFactor() != 1.0) {
                    result |= SURFACE_NOT_FREE;
                    if (by <= floor && props.friction() > 0.61) {
                        result |= SURFACE_SLIPPERY;
                    }
                }
            }
        }
        return result;
    }

    /** Vehicle kind of a spawned entity; {@code KIND_NONE} for everything not judged here. */
    static int kindOf(EntityType type) {
        if (type == null) {
            return KIND_NONE;
        }
        if (type.isInstanceOf(EntityTypes.BOAT) || type.isInstanceOf(EntityTypes.CHEST_BOAT)) {
            return KIND_BOAT;
        }
        if (type.isInstanceOf(EntityTypes.ABSTRACT_HORSE) && type != EntityTypes.LLAMA
                && type != EntityTypes.TRADER_LLAMA) {
            return KIND_HORSE;
        }
        if (type == EntityTypes.PIG || type == EntityTypes.STRIDER || type == EntityTypes.CAMEL
                || type == EntityTypes.CAMEL_HUSK) {
            return KIND_OTHER;
        }
        return KIND_NONE;
    }

    private String kindName() {
        return kind == KIND_BOAT ? "boat" : kind == KIND_HORSE ? "horse" : "mount";
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
