package dev.vaplus.anticheat.check.impl.packet;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateHealth;
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

/**
 * Blink / FakeLag: movement packets held back and released later (1.21.2+ clients).
 *
 * <p>Blink modules cancel the movement packets but let every other packet through, including the
 * tick-end packet, so the server sees the client keep ticking without moving and then a burst of
 * stored movement packets inside one tick.
 * <ul>
 *   <li>{@code burst}: a vanilla client sends at most one movement packet per tick, plus the answer
 *       to a server teleport (after {@code TELEPORT_CONFIRM}) and the position re-sync right before
 *       an item use. Every further movement packet in one tick is a released one; the buffer is
 *       raised by the excess (at most 3), so a real blink release flags at once.</li>
 *   <li>{@code withheld}: vanilla sends a position packet at least every 20 ticks even while
 *       standing still ({@code LocalPlayer#sendPosition}). More than {@code max-silent-ticks} tick
 *       ends without any movement packet means the packets are being held. Counted only after the
 *       first movement since join/respawn, never while dead, riding, or in a game mode the movement
 *       category exempts (spectator camera).</li>
 * </ul>
 */
public final class BlinkCheck extends Check implements PacketCheck, OutboundPacketCheck, ClientTickCheck {

    public static final int DEFAULT_MAX_SILENT_TICKS = 40;
    static final double MAX_BURST_WEIGHT = 3.0;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("blink", CheckCategory.PACKET)
            .displayName("Blink")
            .description("Movement packets held back and released in a burst (blink / fake lag)")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.PACKET)
                    .withBufferLimit(1.5)
                    .withBufferDecay(0.01)
                    .withBufferMax(6.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE)
            .build();

    private boolean checkBurst = true;
    private boolean checkWithheld = true;
    private int maxSilentTicks = DEFAULT_MAX_SILENT_TICKS;

    private int movesThisTick;
    private int allowance;
    private int silentTicks;
    private boolean seenMovement;
    private boolean silentFlagged;
    private boolean dead;

    public BlinkCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        checkBurst = options.getBoolean("check-burst", true);
        checkWithheld = options.getBoolean("check-withheld", true);
        int silent = options.getInt("max-silent-ticks", DEFAULT_MAX_SILENT_TICKS);
        maxSilentTicks = silent >= 25 && silent <= 1200 ? silent : DEFAULT_MAX_SILENT_TICKS;
    }

    @Override
    protected void onReset() {
        movesThisTick = 0;
        allowance = 0;
        silentTicks = 0;
        seenMovement = false;
        silentFlagged = false;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!player.tickEndMode()) {
            return;
        }
        PacketTypeCommon type = event.getPacketType();
        if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            onMovement();
        } else if (type == PacketType.Play.Client.TELEPORT_CONFIRM || type == PacketType.Play.Client.USE_ITEM) {
            allowance++;
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.UPDATE_HEALTH) {
            dead = new WrapperPlayServerUpdateHealth(event).getHealth() <= 0.0f;
            if (dead) {
                silentTicks = 0;
            }
        } else if (type == PacketType.Play.Server.RESPAWN || type == PacketType.Play.Server.JOIN_GAME) {
            dead = false;
            seenMovement = false;
            silentTicks = 0;
        }
    }

    void onMovement() {
        movesThisTick++;
        seenMovement = true;
        silentTicks = 0;
        silentFlagged = false;
    }

    void onTeleportConfirm() {
        allowance++;
    }

    @Override
    public void onClientTick() {
        if (!player.tickEndMode()) {
            return;
        }
        int excess = movesThisTick - 1 - allowance;
        boolean moved = movesThisTick > 0;
        movesThisTick = 0;
        allowance = 0;
        boolean exempt = isExempt() || player.vehicle().inVehicle();

        if (checkBurst && !exempt) {
            if (excess > 0) {
                flag(Math.min(MAX_BURST_WEIGHT, excess), "burst moves=" + (excess + 1) + " in one tick");
            } else if (moved) {
                lowerBuffer();
            }
        }

        if (moved || !seenMovement || dead || exempt || player.isExempt(CheckCategory.MOVEMENT)) {
            if (!moved) {
                silentTicks = 0;
            }
            return;
        }
        silentTicks++;
        if (checkWithheld && silentTicks > maxSilentTicks && !silentFlagged) {
            silentFlagged = true;
            flag(1.0, "withheld " + silentTicks + " ticks without movement");
        }
    }

    private void flag(double weight, String details) {
        if (debugging()) {
            debug(details + " buffer=" + buffer());
        }
        if (raiseBuffer(weight)) {
            violation(details);
            resetBuffer();
        }
    }
}
