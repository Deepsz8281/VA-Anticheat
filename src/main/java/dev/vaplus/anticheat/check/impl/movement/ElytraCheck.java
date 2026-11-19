package dev.vaplus.anticheat.check.impl.movement;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientEntityAction;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPlayerInventory;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
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
import dev.vaplus.anticheat.check.PositionCheck;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.EffectState;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.TrackedEffect;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.world.BlockInteraction;
import dev.vaplus.anticheat.world.mirror.BlockProperties;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.List;

/**
 * Elytra (ElytraFly): gliding that vanilla glide physics cannot produce without a boost.
 *
 * <p>Vanilla glide tick (client {@code LivingEntity} fall-flying travel, read from client code;
 * UNCONFIRMED against a capture): gravity is applied as {@code g × (-1 + 0.75 cos²(pitch))}, so the
 * vertical motion always loses at least {@code 0.25 g} per tick; lift (the only upward term apart from
 * pulling up) only exists while the player is already moving and costs horizontal speed; all motion is
 * damped by 0.99/0.98 per tick. Two consequences are judged, only while the server has acknowledged
 * the gliding pose and only in known free air:
 * <ul>
 *   <li><b>hover</b> — {@code hover-ticks} consecutive ticks with almost no movement at all
 *       ({@code |dy| <= hover-vertical}, horizontal {@code <= hover-horizontal} per tick). Gravity
 *       accelerates a stationary glider downward every tick.</li>
 *   <li><b>climb</b> — {@code climb-ticks} consecutive ticks without descending ({@code dy >= 0}).
 *       Climbing (pulling up) converts horizontal speed into height and loses at least 1% speed per
 *       tick to drag, so a pull-up from any dive ends in a descent long before that.</li>
 * </ul>
 *
 * <p>Why legit play does not trigger it: any item use (firework rockets, wind charges, tridents) and
 * any server velocity suspend judging for {@code boost-grace-ticks} (a rocket lasts at most about
 * 51 ticks); ceilings, walls, liquids, climbables, special blocks and unknown blocks around the
 * player reset the streaks; levitation, slow falling, pending effects, flight, vehicles and
 * creative/spectator (category exemption) are skipped.
 *
 * <p>Known bypasses: speed hacks that keep descending, rocket-boost multipliers (speed is not
 * bounded here), and hacks that only climb for less than {@code climb-ticks}. All numbers are TUNING
 * values. Experimental.
 *
 * <p>Glide starts ({@code START_FLYING_WITH_ELYTRA}) a vanilla client cannot send (Grim ElytraA-I):
 * <ul>
 *   <li>{@code start-gliding} (A): while the acknowledged gliding pose is still on and the player has
 *       not touched the ground since it started.</li>
 *   <li>{@code start-jump} (B, 1.21.2+): vanilla starts gliding on the tick the jump key goes down, so
 *       jump must have been released before and be held at the end of that tick.</li>
 *   <li>{@code start-frequency} (C, 1.21.2+): two starts within two client ticks.</li>
 *   <li>{@code start-no-elytra} (D): no armor slot holds a usable glider. Only judged while every armor
 *       slot was last set by the server and acknowledged; any own inventory click or item use makes
 *       the slots unknown until the server sends them again.</li>
 *   <li>{@code start-flying} (E), {@code start-ground} (F), {@code start-levitation} (G),
 *       {@code start-vehicle} (H), {@code start-water} (I): the client itself refuses to start gliding
 *       while flying, on the ground (its last claim), with levitation, as a passenger or in water.</li>
 * </ul>
 * A, D, E, G, H and I need a repeat here (our pose and inventory model is not Grim's).
 */
public final class ElytraCheck extends Check implements PositionCheck, BlockCheck, PacketCheck,
        OutboundPacketCheck, ClientTickCheck {

    private static final byte SLOT_UNKNOWN = 0;
    private static final byte SLOT_GLIDER = 1;
    private static final byte SLOT_NONE = 2;
    /** Window 0 armor slots 5..8 (head to feet). */
    private static final int FIRST_ARMOR_SLOT = 5;
    /** Player inventory index of the head slot (1.21.2+ set-player-inventory packet), feet is 36. */
    private static final int HEAD_INVENTORY_INDEX = 39;

    public static final int DEFAULT_HOVER_TICKS = 20;
    public static final double DEFAULT_HOVER_VERTICAL = 0.005;
    public static final double DEFAULT_HOVER_HORIZONTAL = 0.05;
    public static final int DEFAULT_CLIMB_TICKS = 100;
    public static final int DEFAULT_BOOST_GRACE_TICKS = 80;
    /** Half the player width plus a margin for the free-air scan. */
    private static final double SCAN_REACH = 0.5;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("elytra", CheckCategory.MOVEMENT)
            .displayName("Elytra")
            .description("Gliding that hovers or keeps climbing without a boost")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.MOVEMENT)
                    .withBufferLimit(1.0)
                    .withBufferDecay(0.05)
                    .withBufferMax(4.0)
                    .withSetbackVl(-1.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT, ExemptionCause.GAMEMODE_CHANGE, ExemptionCause.ABILITY_CHANGE,
                    ExemptionCause.ATTRIBUTE_CHANGE, ExemptionCause.VEHICLE, ExemptionCause.KNOCKBACK,
                    ExemptionCause.EXPLOSION, ExemptionCause.PORTAL, ExemptionCause.ELYTRA_BOOST,
                    ExemptionCause.WIND_CHARGE, ExemptionCause.RIPTIDE, ExemptionCause.BOUNCE,
                    ExemptionCause.LUNGE)
            .build();

    private final ComponentKey<WorldMirror> worldKey;
    private final ComponentKey<VelocityState> velocityKey;

    private int hoverTicksLimit = DEFAULT_HOVER_TICKS;
    private double hoverVertical = DEFAULT_HOVER_VERTICAL;
    private double hoverHorizontal = DEFAULT_HOVER_HORIZONTAL;
    private int climbTicksLimit = DEFAULT_CLIMB_TICKS;
    private int boostGraceTicks = DEFAULT_BOOST_GRACE_TICKS;

    private int hoverTicks;
    private int climbTicks;
    private long lastBoostTick = Long.MIN_VALUE;
    private long seenVelocities = -1L;

    private boolean checkStartGliding = true;
    private boolean checkStartJump = true;
    private boolean checkStartFrequency = true;
    private boolean checkStartNoElytra = true;
    private boolean checkStartFlying = true;
    private boolean checkStartGround = true;
    private boolean checkStartLevitation = true;
    private boolean checkStartVehicle = true;
    private boolean checkStartWater = true;

    private final byte[] armor = new byte[4];
    private final long[] armorEpoch = new long[4];
    private boolean groundedSinceGlide;
    private boolean glideThisTick;
    private boolean glideLastTick;
    private boolean glideAwaitingJump;

    public ElytraCheck(PlayerData player, ComponentKey<WorldMirror> worldKey, ComponentKey<VelocityState> velocityKey) {
        super(player, DESCRIPTOR);
        this.worldKey = worldKey;
        this.velocityKey = velocityKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        hoverTicksLimit = bounded(options.getInt("hover-ticks", DEFAULT_HOVER_TICKS), 5, 1200, DEFAULT_HOVER_TICKS);
        hoverVertical = MovementOptions.tolerance(options, "hover-vertical", DEFAULT_HOVER_VERTICAL);
        hoverHorizontal = MovementOptions.tolerance(options, "hover-horizontal", DEFAULT_HOVER_HORIZONTAL);
        climbTicksLimit = bounded(options.getInt("climb-ticks", DEFAULT_CLIMB_TICKS), 20, 6000, DEFAULT_CLIMB_TICKS);
        boostGraceTicks = bounded(options.getInt("boost-grace-ticks", DEFAULT_BOOST_GRACE_TICKS), 0, 1200,
                DEFAULT_BOOST_GRACE_TICKS);
        checkStartGliding = options.getBoolean("check-start-gliding", true);
        checkStartJump = options.getBoolean("check-start-jump", true);
        checkStartFrequency = options.getBoolean("check-start-frequency", true);
        checkStartNoElytra = options.getBoolean("check-start-no-elytra", true);
        checkStartFlying = options.getBoolean("check-start-flying", true);
        checkStartGround = options.getBoolean("check-start-ground", true);
        checkStartLevitation = options.getBoolean("check-start-levitation", true);
        checkStartVehicle = options.getBoolean("check-start-vehicle", true);
        checkStartWater = options.getBoolean("check-start-water", true);
    }

    private static int bounded(int value, int min, int max, int fallback) {
        return value >= min && value <= max ? value : fallback;
    }

    @Override
    protected void onReset() {
        streakReset();
        lastBoostTick = Long.MIN_VALUE;
        seenVelocities = -1L;
        glideThisTick = false;
        glideLastTick = false;
        glideAwaitingJump = false;
        armorUnknown();
    }

    // ------------------------------------------------------------------ glide starts

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.ENTITY_ACTION) {
            if (new WrapperPlayClientEntityAction(event).getAction()
                    == WrapperPlayClientEntityAction.Action.START_FLYING_WITH_ELYTRA) {
                onGlideStart();
            }
        } else if (type == PacketType.Play.Client.CLICK_WINDOW
                || type == PacketType.Play.Client.CREATIVE_INVENTORY_ACTION) {
            armorUnknown();
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.SET_SLOT) {
            WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(event);
            int index = packet.getSlot() - FIRST_ARMOR_SLOT;
            if (packet.getWindowId() == 0 && index >= 0 && index < armor.length) {
                onArmor(index, glider(packet.getItem()), event.getTasksAfterSend());
            }
        } else if (type == PacketType.Play.Server.WINDOW_ITEMS) {
            WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(event);
            List<ItemStack> items = packet.getItems();
            if (packet.getWindowId() == 0 && items.size() > FIRST_ARMOR_SLOT + armor.length) {
                for (int i = 0; i < armor.length; i++) {
                    onArmor(i, glider(items.get(FIRST_ARMOR_SLOT + i)), event.getTasksAfterSend());
                }
            }
        } else if (type == PacketType.Play.Server.SET_PLAYER_INVENTORY) {
            WrapperPlayServerSetPlayerInventory packet = new WrapperPlayServerSetPlayerInventory(event);
            int index = HEAD_INVENTORY_INDEX - packet.getSlot();
            if (index >= 0 && index < armor.length) {
                onArmor(index, glider(packet.getStack()), event.getTasksAfterSend());
            }
        } else if (type == PacketType.Play.Server.JOIN_GAME || type == PacketType.Play.Server.RESPAWN) {
            armorUnknown();
        }
    }

    /** A usable glider: an elytra or an item with the glider component that is not about to break. */
    static boolean glider(ItemStack item) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        boolean glider = item.getType() == ItemTypes.ELYTRA || item.hasComponent(ComponentTypes.GLIDER);
        return glider && !(item.isDamageableItem() && item.getDamageValue() >= item.getMaxDamage() - 1);
    }

    /**
     * Server-set armor slot. Losing a glider counts once the client acknowledged it; gaining one can
     * only end a judgement and applies at once.
     */
    void onArmor(int index, boolean glider, List<Runnable> tasksAfterSend) {
        long epoch = ++armorEpoch[index];
        if (glider) {
            armor[index] = SLOT_GLIDER;
            return;
        }
        if (armor[index] == SLOT_NONE) {
            return;
        }
        player.acks().bracket(tasksAfterSend, null, () -> {
            if (armorEpoch[index] == epoch) {
                armor[index] = SLOT_NONE;
            }
        });
    }

    /** The client changed its inventory itself: the armor slots are unknown until the server sends them. */
    void armorUnknown() {
        for (int i = 0; i < armor.length; i++) {
            armor[i] = SLOT_UNKNOWN;
            armorEpoch[i]++;
        }
    }

    private boolean noGlider() {
        for (byte slot : armor) {
            if (slot != SLOT_NONE) {
                return false;
            }
        }
        return true;
    }

    /** {@code START_FLYING_WITH_ELYTRA}; runs before the pipeline applies the packet. */
    void onGlideStart() {
        boolean tickEnd = player.tickEndMode();
        boolean frequent = glideThisTick || glideLastTick;
        glideThisTick = true;
        if (isExempt()) {
            return;
        }
        if (checkStartFrequency && tickEnd && frequent) {
            violation("start-frequency started gliding twice within two ticks");
            return;
        }
        if (checkStartJump && tickEnd && player.pose().inputKnown()) {
            if (player.pose().jump()) {
                violation("start-jump started gliding without releasing jump");
                return;
            }
            glideAwaitingJump = true;
        }
        if (checkStartGround && player.movement().positionKnown() && player.movement().onGround()
                && !player.vehicle().inVehicle()) {
            violation("start-ground started gliding on the ground");
            return;
        }
        String signal = null;
        if (checkStartGliding && player.pose().gliding() && !groundedSinceGlide) {
            signal = "start-gliding started gliding while already gliding";
        } else if (checkStartNoElytra && noGlider()) {
            signal = "start-no-elytra started gliding without a glider";
        } else if (checkStartFlying && player.abilities().flying()) {
            signal = "start-flying started gliding while flying";
        } else if (checkStartLevitation && player.effects().has(TrackedEffect.LEVITATION)
                && !player.effects().anyPending()) {
            signal = "start-levitation started gliding with levitation";
        } else if (checkStartVehicle && player.vehicle().inVehicle()) {
            signal = "start-vehicle started gliding as a passenger";
        } else if (checkStartWater && inWater()) {
            signal = "start-water started gliding in water";
        }
        if (signal != null && raiseBuffer(1.0)) {
            violation(signal);
            resetBuffer();
        }
    }

    @Override
    public void onClientTick() {
        if (glideAwaitingJump && !player.pose().jump() && !isExempt()) {
            violation("start-jump started gliding without pressing jump");
        }
        glideAwaitingJump = false;
        glideLastTick = glideThisTick;
        glideThisTick = false;
    }

    /** The water block at the feet: the player box touches water whatever its level. */
    private boolean inWater() {
        if (!player.movement().positionKnown()) {
            return false;
        }
        double y = player.movement().y();
        int bx = (int) Math.floor(player.movement().x());
        int by = (int) Math.floor(y);
        int bz = (int) Math.floor(player.movement().z());
        WorldMirror world = player.component(worldKey);
        if (y - by > 0.1 || world.pending().intersects(bx, by, bz, bx, by, bz)) {
            return false;
        }
        BlockSnapshot block = world.getBlock(bx, by, bz);
        return block.known() && block.properties().liquid() == BlockProperties.Liquid.WATER;
    }

    @Override
    public void onBlockInteraction(BlockInteraction interaction) {
        if (interaction.kind() == BlockInteraction.Kind.USE_ITEM || interaction.kind() == BlockInteraction.Kind.PLACE) {
            // right-clicking may equip a glider from the hand
            armorUnknown();
            // Rockets are used from the hand (USE_ITEM) or against a block; both may boost.
            lastBoostTick = player.clientTicks();
            streakReset();
        }
    }

    @Override
    public void onPosition(MovementUpdate update) {
        if (!player.pose().gliding()) {
            groundedSinceGlide = false;
        } else if (update.toGround() || player.vehicle().inVehicle()) {
            groundedSinceGlide = true;
        }
        long now = player.clientTicks();
        long registered = player.component(velocityKey).registered();
        if (seenVelocities >= 0 && registered != seenVelocities) {
            lastBoostTick = now;
        }
        seenVelocities = registered;
        if (!update.positionChanged() || update.firstPosition() || update.teleport() || !judgeable(now)) {
            streakReset();
            return;
        }
        if (!freeAir(update)) {
            streakReset();
            return;
        }
        int ticks = Math.max(1, update.elapsedTicks());
        judge(ticks, update.deltaY(), update.horizontalDistance());
    }

    /** Decision logic on primitives (tests): one judgeable gliding packet in free air. */
    void judge(int ticks, double dy, double horizontal) {
        boolean hovering = Math.abs(dy) <= hoverVertical * ticks && horizontal <= hoverHorizontal * ticks;
        hoverTicks = hovering ? hoverTicks + ticks : 0;
        climbTicks = dy >= 0.0 ? climbTicks + ticks : 0;
        String signal = null;
        if (hoverTicks > hoverTicksLimit) {
            signal = "hover ticks=" + hoverTicks;
            hoverTicks = 0;
        } else if (climbTicks > climbTicksLimit) {
            signal = "climb ticks=" + climbTicks;
            climbTicks = 0;
        }
        if (signal == null) {
            if (dy < 0.0) {
                lowerBuffer();
            }
            return;
        }
        if (debugging()) {
            debug(signal + " dy=" + MovementOptions.number(dy) + " buffer=" + MovementOptions.number(buffer()));
        }
        if (raiseBuffer(1.0)) {
            violation(signal + " dy=" + MovementOptions.number(dy)
                    + " horizontal=" + MovementOptions.number(horizontal));
        }
    }

    private boolean judgeable(long now) {
        if (isExempt() || !player.pose().gliding()) {
            return false;
        }
        if (lastBoostTick != Long.MIN_VALUE && now - lastBoostTick <= boostGraceTicks) {
            return false;
        }
        if (player.abilities().flying() || player.vehicle().inVehicle()) {
            return false;
        }
        EffectState effects = player.effects();
        return !effects.has(TrackedEffect.LEVITATION) && !effects.has(TrackedEffect.SLOW_FALLING)
                && !effects.anyPending() && !player.attributes().pending()
                && player.component(velocityKey).size() == 0;
    }

    /** Known, collision-free, non-liquid, non-special blocks from one below the feet to above the head. */
    private boolean freeAir(MovementUpdate update) {
        WorldMirror world = player.component(worldKey);
        double x = update.toX();
        double y = update.toY();
        double z = update.toZ();
        int x0 = (int) Math.floor(x - SCAN_REACH);
        int x1 = (int) Math.floor(x + SCAN_REACH);
        int z0 = (int) Math.floor(z - SCAN_REACH);
        int z1 = (int) Math.floor(z + SCAN_REACH);
        int y0 = (int) Math.floor(y) - 1;
        int y1 = (int) Math.floor(y + 2.0);
        if (world.pending().intersects(x0, y0, z0, x1, y1, z1)) {
            return false;
        }
        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                for (int by = y0; by <= y1; by++) {
                    BlockSnapshot block = world.getBlock(bx, by, bz);
                    if (!block.known()) {
                        return false;
                    }
                    BlockProperties properties = block.properties();
                    if (properties.collision() != BlockProperties.Collision.EMPTY
                            || properties.liquid() != BlockProperties.Liquid.NONE
                            || properties.climbable() || !properties.flags().isEmpty()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private void streakReset() {
        hoverTicks = 0;
        climbTicks = 0;
    }
}
