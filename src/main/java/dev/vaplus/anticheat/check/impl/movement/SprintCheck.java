package dev.vaplus.anticheat.check.impl.movement;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateHealth;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.check.OutboundPacketCheck;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.MovementState;
import dev.vaplus.anticheat.player.state.PoseState;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.world.mirror.BlockProperties;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.List;

/**
 * Sprint: sprinting in states where the vanilla client stops or cannot start sprinting.
 *
 * <ul>
 *   <li>{@code omni} (1.21.2+ clients): sprinting for {@code min-ticks} client ticks while forward is
 *       not the net input. Vanilla stops sprinting at once when the forward impulse is gone.</li>
 *   <li>{@code hunger} (Grim SprintA): sprinting with 6 or less food while the server does not allow
 *       flight. Vanilla stops sprinting when food drops to 6. Judged from two client ticks after the
 *       client acknowledged the food value.</li>
 *   <li>{@code blindness} (Grim SprintD): starting to sprint while blind. Vanilla cannot start
 *       sprinting with Blindness.</li>
 *   <li>{@code wall} (Grim SprintE, 1.21.2+ clients): still sprinting after {@code wall-ticks} ticks of
 *       running head-on into a wall (client-reported collision, forward held, practically no
 *       horizontal movement). Vanilla stops sprinting on any collision that is not a minor glancing
 *       one; a head-on stop is never minor. Experimental.</li>
 *   <li>{@code gliding} (Grim SprintF, 1.21.4 clients only, like Grim): sprinting while gliding.</li>
 *   <li>{@code water} (Grim SprintG): sprinting with the feet in water and the eyes above it, without
 *       swimming. Vanilla stops sprinting in shallow water. Experimental.</li>
 * </ul>
 *
 * <p>Not judged: vehicles, flight, swimming (separate sprint rules), the usual join/respawn/teleport
 * brackets. Grim's SprintB (sprint while sneaking) is the {@code badpackets} sprint-sneak signal;
 * SprintC (sprint while using an item) depends on per-version item-use behaviour and is not ported.
 */
public final class SprintCheck extends Check implements ClientTickCheck, OutboundPacketCheck {

    public static final int DEFAULT_MIN_TICKS = 3;
    public static final int DEFAULT_WALL_TICKS = 3;
    public static final int DEFAULT_WATER_TICKS = 3;
    /** Vanilla: sprinting needs more than 6 food (unless flight is allowed). */
    static final int SPRINT_FOOD = 6;
    /** Client ticks after the acknowledged food value before sprinting is judged. */
    static final int HUNGER_GRACE_TICKS = 2;
    /** Horizontal movement per tick below which a colliding player stands at the wall. */
    static final double WALL_STILL = 0.01;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("sprint", CheckCategory.MOVEMENT)
            .displayName("Sprint")
            .description("Sprinting without forward input, hungry, blind, against walls, gliding or in shallow water")
            .defaults(CheckDefaults.of(CheckCategory.MOVEMENT)
                    .withBufferLimit(2.0)
                    .withBufferDecay(0.05)
                    .withBufferMax(6.0)
                    .withSetbackVl(-1.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.VEHICLE, ExemptionCause.GAMEMODE_CHANGE)
            .build();

    /** {@code null} without the simulation module: the water signal is off. */
    private final ComponentKey<WorldMirror> worldKey;

    private int minTicks = DEFAULT_MIN_TICKS;
    private boolean checkHunger = true;
    private boolean checkBlindness = true;
    private boolean checkWall = true;
    private int wallTicks = DEFAULT_WALL_TICKS;
    private boolean checkGliding = true;
    private boolean checkWater = true;
    private int waterTicks = DEFAULT_WATER_TICKS;

    private int streak;
    private boolean wasSprinting;
    // hunger: the client-acknowledged food state
    private boolean lowFoodSent;
    private int foodEpoch;
    private boolean lowFoodAcked;
    private long lowFoodSinceTick;
    private int hungerTicks;
    private int wallStreak;
    private int glideStreak;
    private int waterStreak;

    public SprintCheck(PlayerData player) {
        this(player, null);
    }

    public SprintCheck(PlayerData player, ComponentKey<WorldMirror> worldKey) {
        super(player, DESCRIPTOR);
        this.worldKey = worldKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        int ticks = options.getInt("min-ticks", DEFAULT_MIN_TICKS);
        minTicks = ticks >= 2 && ticks <= 100 ? ticks : DEFAULT_MIN_TICKS;
        checkHunger = options.getBoolean("check-hunger", true);
        checkBlindness = options.getBoolean("check-blindness", true);
        checkWall = options.getBoolean("check-wall", true);
        int wall = options.getInt("wall-ticks", DEFAULT_WALL_TICKS);
        wallTicks = wall >= 2 && wall <= 40 ? wall : DEFAULT_WALL_TICKS;
        checkGliding = options.getBoolean("check-gliding", true);
        checkWater = options.getBoolean("check-water", true);
        int water = options.getInt("water-ticks", DEFAULT_WATER_TICKS);
        waterTicks = water >= 2 && water <= 40 ? water : DEFAULT_WATER_TICKS;
    }

    @Override
    protected void onReset() {
        streak = 0;
        hungerTicks = 0;
        wallStreak = 0;
        glideStreak = 0;
        waterStreak = 0;
    }

    // ------------------------------------------------------------------ food tracking

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.UPDATE_HEALTH) {
            onFood(new WrapperPlayServerUpdateHealth(event).getFood(), event.getTasksAfterSend());
        } else if (event.getPacketType() == PacketType.Play.Server.RESPAWN
                || event.getPacketType() == PacketType.Play.Server.JOIN_GAME) {
            foodEpoch++;
            lowFoodSent = false;
            lowFoodAcked = false;
        }
    }

    /** Server sent a food value; low food counts once the client acknowledged it. */
    void onFood(int food, List<Runnable> tasksAfterSend) {
        boolean low = food <= SPRINT_FOOD;
        if (low == lowFoodSent) {
            return;
        }
        lowFoodSent = low;
        int epoch = ++foodEpoch;
        if (!low) {
            // raising food can only end a judgement: apply at once
            lowFoodAcked = false;
            return;
        }
        player.acks().bracket(tasksAfterSend, null, () -> {
            if (epoch == foodEpoch) {
                lowFoodAcked = true;
                lowFoodSinceTick = player.clientTicks();
            }
        });
    }

    // ------------------------------------------------------------------ judging

    @Override
    public void onClientTick() {
        PoseState pose = player.pose();
        boolean sprinting = pose.sprinting();
        boolean started = sprinting && !wasSprinting;
        wasSprinting = sprinting;
        if (isExempt() || player.vehicle().inVehicle() || player.abilities().flying()) {
            resetStreaks();
            return;
        }
        omni(pose, sprinting);
        hunger(sprinting);
        blindness(started);
        wall(pose, sprinting, started);
        gliding(pose, sprinting);
        water(pose, sprinting);
    }

    private void resetStreaks() {
        streak = 0;
        hungerTicks = 0;
        wallStreak = 0;
        glideStreak = 0;
        waterStreak = 0;
    }

    private void omni(PoseState pose, boolean sprinting) {
        if (!player.tickEndMode() || !pose.inputKnown() || pose.swimming() || pose.gliding() || !sprinting) {
            streak = 0;
            return;
        }
        if (pose.forwardImpulse() > 0) {
            streak = 0;
            lowerBuffer();
            return;
        }
        if (++streak < minTicks) {
            return;
        }
        String details = "sprinting " + streak + " ticks without forward input"
                + (pose.backward() ? " (backward)" : pose.left() || pose.right() ? " (sideways)" : " (no keys)");
        streak = 0;
        flag(details);
    }

    private void hunger(boolean sprinting) {
        if (!checkHunger || !lowFoodAcked || !sprinting || player.abilities().flightAllowed()
                || player.clientTicks() - lowFoodSinceTick < HUNGER_GRACE_TICKS) {
            hungerTicks = 0;
            return;
        }
        if (++hungerTicks >= 2) {
            hungerTicks = 0;
            flag("hunger sprinting with food <= " + SPRINT_FOOD);
        }
    }

    private void blindness(boolean started) {
        if (!checkBlindness || !started || player.effects().anyPending()
                || !player.effects().has(TrackedEffect.BLINDNESS)) {
            return;
        }
        flag("blindness started sprinting while blind");
    }

    private void wall(PoseState pose, boolean sprinting, boolean started) {
        MovementState movement = player.movement();
        if (!checkWall || !player.tickEndMode() || !pose.inputKnown() || !sprinting || started
                || pose.swimming() || pose.gliding() || pose.forwardImpulse() <= 0 || !movement.horizontalCollision()
                || !movement.positionKnown()) {
            wallStreak = 0;
            return;
        }
        double moved = Math.hypot(movement.x() - movement.lastX(), movement.z() - movement.lastZ());
        if (moved >= WALL_STILL) {
            wallStreak = 0;
            return;
        }
        if (++wallStreak >= wallTicks) {
            wallStreak = 0;
            flag("wall sprinting into a wall for " + wallTicks + " ticks");
        }
    }

    private void gliding(PoseState pose, boolean sprinting) {
        if (!checkGliding || player.clientVersion() != ClientVersion.V_1_21_4 || !sprinting || !pose.gliding()) {
            glideStreak = 0;
            return;
        }
        if (++glideStreak >= 3) {
            glideStreak = 0;
            flag("gliding while sprinting");
        }
    }

    private void water(PoseState pose, boolean sprinting) {
        if (!checkWater || worldKey == null || !sprinting || pose.swimming() || !shallowWater()) {
            waterStreak = 0;
            return;
        }
        if (++waterStreak >= waterTicks) {
            waterStreak = 0;
            flag("water sprinting in shallow water for " + waterTicks + " ticks");
        }
    }

    /** Feet in water, eyes in known air above it. */
    private boolean shallowWater() {
        MovementState movement = player.movement();
        if (!movement.positionKnown()) {
            return false;
        }
        WorldMirror world = player.component(worldKey);
        int x = (int) Math.floor(movement.x());
        int z = (int) Math.floor(movement.z());
        BlockSnapshot feet = world.getBlock(x, (int) Math.floor(movement.y() + 1.0e-6), z);
        BlockSnapshot eyes = world.getBlock(x, (int) Math.floor(movement.y() + 1.62), z);
        return feet.known() && eyes.known() && !world.pending().any()
                && feet.properties().liquid() == BlockProperties.Liquid.WATER
                && eyes.properties().liquid() == BlockProperties.Liquid.NONE;
    }

    private void flag(String details) {
        if (debugging()) {
            debug(details + " buffer=" + buffer());
        }
        if (raiseBuffer(1.0)) {
            violation(details);
            resetBuffer();
        }
    }
}
