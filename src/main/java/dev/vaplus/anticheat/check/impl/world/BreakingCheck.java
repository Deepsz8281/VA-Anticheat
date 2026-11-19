package dev.vaplus.anticheat.check.impl.world;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import dev.vaplus.anticheat.check.BlockCheck;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.check.PacketCheck;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.MovementState;
import dev.vaplus.anticheat.world.BlockInteraction;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.Locale;

/**
 * Breaking: dig packets a vanilla client cannot send (Grim equivalents in brackets).
 *
 * <ul>
 *   <li>{@code air-liquid} (AirLiquidBreak): starting or finishing to dig air, water, lava, a bubble
 *       column or a moving piston. The pick ray passes through all of them. Blocks inside a world
 *       window the client has not acknowledged yet, and a block dug in the last ticks (a broken
 *       waterlogged plant turns into water) are not judged. Unbreakable finishes are the
 *       {@code fastbreak} check's.</li>
 *   <li>{@code face} (InvalidBreak): a dig packet with a block face id outside 0..5.</li>
 *   <li>{@code no-swing} (NoSwingBreak, 1.21.2+ clients): a dig start or finish in a client tick
 *       without an arm swing. Vanilla swings in every tick it starts or continues digging.
 *       Experimental.</li>
 *   <li>{@code position} (PositionBreakA): digging a face of a full cube while every possible eye
 *       position (previous and current position, crawling to standing eye height, 0.03 margin) is
 *       on the inner side of that face's plane: the face is not visible from there.</li>
 *   <li>{@code cancel-face} (PositionBreakB): vanilla aborts with the DOWN face, or with the face of
 *       the block it switches to, immediately followed by the start on that face. An abort with
 *       another face that is not followed by such a start is crafted.</li>
 * </ul>
 * Vehicles, teleports and the usual exemptions are not judged; creative and spectator players are
 * exempt through the WORLD category.
 */
public final class BreakingCheck extends Check implements BlockCheck, PacketCheck, ClientTickCheck {

    /** Grim's movement threshold margin around the eye positions. */
    static final double EYE_MARGIN = 0.03;
    static final double MIN_EYE = 0.4;
    static final double MAX_EYE = 1.62;
    /** Client ticks a dug position stays excused from air-liquid (it may have turned into water). */
    static final int RECENT_DIG_TICKS = 3;

    private static final long EXEMPT_CAUSES = ExemptionCause.mask(ExemptionCause.JOIN, ExemptionCause.RESPAWN,
            ExemptionCause.WORLD_CHANGE, ExemptionCause.TELEPORT, ExemptionCause.VEHICLE);

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("breaking", CheckCategory.WORLD)
            .displayName("Breaking")
            .description("Digging air or liquids, invalid faces, without swing, hidden faces or crafted aborts")
            .defaults(CheckDefaults.of(CheckCategory.WORLD)
                    .withBufferLimit(1.0)
                    .withBufferDecay(0.05)
                    .withBufferMax(4.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.VEHICLE)
            .build();

    private final ComponentKey<WorldMirror> worldKey;

    private boolean checkAirLiquid = true;
    private boolean checkFace = true;
    private boolean checkNoSwing = true;
    private boolean checkPosition = true;
    private boolean checkCancelFace = true;

    private boolean digThisTick;
    private boolean swingThisTick;
    private BlockFace abortFace;
    private int lastDigX;
    private int lastDigY;
    private int lastDigZ;
    private long lastDigTick = Long.MIN_VALUE / 2;

    public BreakingCheck(PlayerData player, ComponentKey<WorldMirror> worldKey) {
        super(player, DESCRIPTOR);
        this.worldKey = worldKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        checkAirLiquid = options.getBoolean("check-air-liquid", true);
        checkFace = options.getBoolean("check-face", true);
        checkNoSwing = options.getBoolean("check-no-swing", true);
        checkPosition = options.getBoolean("check-position", true);
        checkCancelFace = options.getBoolean("check-cancel-face", true);
    }

    @Override
    protected void onReset() {
        digThisTick = false;
        swingThisTick = false;
        abortFace = null;
    }

    // ------------------------------------------------------------------ raw packets

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.ANIMATION) {
            onSwing();
        } else if (type == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging packet = new WrapperPlayClientPlayerDigging(event);
            DiggingAction action = packet.getAction();
            if (action == DiggingAction.START_DIGGING || action == DiggingAction.FINISHED_DIGGING
                    || action == DiggingAction.CANCELLED_DIGGING) {
                onFaceId(packet.getBlockFaceId(), action);
            }
        }
    }

    /** Arm swing (no-swing). */
    void onSwing() {
        swingThisTick = true;
    }

    /** Raw block face id of a dig packet (face). */
    void onFaceId(int faceId, DiggingAction action) {
        if (checkFace && (faceId < 0 || faceId > 5) && !judgeableExempt()) {
            flag("face id=" + faceId + " action=" + action.name().toLowerCase(Locale.ROOT));
        }
    }

    // ------------------------------------------------------------------ interactions

    @Override
    public void onBlockInteraction(BlockInteraction interaction) {
        BlockInteraction.Kind kind = interaction.kind();
        if (kind != BlockInteraction.Kind.DIG_START && kind != BlockInteraction.Kind.DIG_FINISH
                && kind != BlockInteraction.Kind.DIG_CANCEL) {
            return;
        }
        if (judgeableExempt()) {
            abortFace = null;
            return;
        }
        cancelFace(kind, interaction.face());
        if (kind == BlockInteraction.Kind.DIG_CANCEL) {
            return;
        }
        digThisTick = true;
        int x = interaction.blockX();
        int y = interaction.blockY();
        int z = interaction.blockZ();
        WorldMirror world = player.component(worldKey);
        boolean pending = world.pending().intersects(x, y, z, x, y, z);
        BlockSnapshot block = world.getBlock(x, y, z);
        boolean recent = x == lastDigX && y == lastDigY && z == lastDigZ
                && player.clientTicks() - lastDigTick <= RECENT_DIG_TICKS;
        lastDigX = x;
        lastDigY = y;
        lastDigZ = z;
        lastDigTick = player.clientTicks();
        if (!block.known() || pending) {
            return;
        }
        if (checkAirLiquid && !recent && airOrLiquid(block)) {
            flag("air-liquid block=" + block.key().value() + " at " + x + "," + y + "," + z
                    + " action=" + (kind == BlockInteraction.Kind.DIG_START ? "start" : "finish"));
            return;
        }
        if (checkPosition && OpaqueCubes.occludes(block) && hiddenFace(interaction.face(), x, y, z)) {
            flag("position face=" + interaction.face() + " of " + x + "," + y + "," + z
                    + " is not visible from the eye");
        }
    }

    /** Abort faces (cancel-face). */
    private void cancelFace(BlockInteraction.Kind kind, BlockFace face) {
        if (!checkCancelFace) {
            return;
        }
        if (kind == BlockInteraction.Kind.DIG_START && face == abortFace) {
            abortFace = null;
        }
        if (abortFace != null) {
            flag("cancel-face aborted with " + abortFace + " without starting on that face");
            abortFace = null;
        }
        if (kind == BlockInteraction.Kind.DIG_CANCEL) {
            abortFace = face == BlockFace.DOWN || face == BlockFace.OTHER ? null : face;
        }
    }

    static boolean airOrLiquid(BlockSnapshot block) {
        String key = block.key().value();
        return key.endsWith("air") || key.equals("minecraft:water") || key.equals("minecraft:lava")
                || key.equals("minecraft:bubble_column") || key.equals("minecraft:moving_piston");
    }

    /** Whether every eye candidate lies on the inner side of the face's plane. */
    private boolean hiddenFace(BlockFace face, int bx, int by, int bz) {
        MovementState movement = player.movement();
        if (!movement.positionKnown()) {
            return false;
        }
        double scale = Math.max(1.0, player.attributes().scale());
        double minX = Math.min(movement.x(), movement.lastX()) - EYE_MARGIN;
        double maxX = Math.max(movement.x(), movement.lastX()) + EYE_MARGIN;
        double minZ = Math.min(movement.z(), movement.lastZ()) - EYE_MARGIN;
        double maxZ = Math.max(movement.z(), movement.lastZ()) + EYE_MARGIN;
        double minY = Math.min(movement.y(), movement.lastY()) + MIN_EYE - EYE_MARGIN;
        double maxY = Math.max(movement.y(), movement.lastY()) + MAX_EYE * scale + EYE_MARGIN;
        boolean intersects = maxX >= bx && minX <= bx + 1 && maxY >= by && minY <= by + 1
                && maxZ >= bz && minZ <= bz + 1;
        if (intersects) {
            return false;
        }
        return switch (face) {
            case NORTH -> minZ > bz;
            case SOUTH -> maxZ < bz + 1;
            case EAST -> maxX < bx + 1;
            case WEST -> minX > bx;
            case UP -> maxY < by + 1;
            case DOWN -> minY > by;
            default -> false;
        };
    }

    @Override
    public void onClientTick() {
        if (checkNoSwing && player.tickEndMode() && digThisTick && !swingThisTick && !judgeableExempt()) {
            if (raiseBuffer(1.0)) {
                violation("no-swing dug without swinging the arm");
                resetBuffer();
            }
        }
        digThisTick = false;
        swingThisTick = false;
    }

    private boolean judgeableExempt() {
        return isExempt() || player.exemptions().anyActive(EXEMPT_CAUSES) || player.vehicle().inVehicle();
    }

    private void flag(String details) {
        if (raiseBuffer(2.0)) {
            violation(details);
            resetBuffer();
        }
    }
}
