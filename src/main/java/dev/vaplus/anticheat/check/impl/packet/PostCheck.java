package dev.vaplus.anticheat.check.impl.packet;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.check.PacketCheck;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;

/**
 * Post: actions sent <em>after</em> the movement packet of the same client tick (1.21.2+ clients).
 *
 * <p>Vanilla order inside one client tick ({@code Minecraft#tick}): input handling first
 * (attacks, swings, item use, placing, digging, hotbar slot - {@code handleKeybinds} and the
 * game mode tick), then the entity tick that sends the movement packet, then the tick-end packet.
 * An attack, swing, use, placement, dig or slot change between the movement packet and the tick end
 * is sent from a "post motion" hook - the signature of silent-rotation auras (Meteor's KillAura with
 * rotation {@code OnHit}, crystal auras, auto-totem/auto-weapon hooks) that first send the aim
 * rotation and attack afterwards so the hit uses it.
 *
 * <p>Vanilla packets that may legitimately follow a movement packet are excluded: the position
 * answer to a server teleport (after {@code TELEPORT_CONFIRM}, sent outside the tick) does not count
 * as the tick's movement, and the position re-sync the client sends right before {@code USE_ITEM}
 * ({@code MultiPlayerGameMode#useItem}) is undone by that use. Clients without tick-end packets have
 * no tick boundary and are never judged.
 */
public final class PostCheck extends Check implements PacketCheck, ClientTickCheck {

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("post", CheckCategory.PACKET)
            .displayName("Post")
            .description("Attacks / interactions sent after the movement packet of the same tick (silent aura)")
            .experimental(true)
            .defaults(CheckDefaults.of(CheckCategory.PACKET)
                    .withBufferLimit(2.0)
                    .withBufferDecay(0.25)
                    .withBufferMax(6.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE)
            .build();

    private boolean checkAttacks = true;
    private boolean checkSwings = true;
    private boolean checkInteractions = true;
    private boolean checkSlot = true;

    private boolean moved;
    private boolean lastWasFullMove;
    private boolean teleportAnswerPending;

    public PostCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        checkAttacks = settings.options().getBoolean("check-attacks", true);
        checkSwings = settings.options().getBoolean("check-swings", true);
        checkInteractions = settings.options().getBoolean("check-interactions", true);
        checkSlot = settings.options().getBoolean("check-slot", true);
    }

    @Override
    protected void onReset() {
        moved = false;
        lastWasFullMove = false;
        teleportAnswerPending = false;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!player.tickEndMode()) {
            return;
        }
        PacketTypeCommon type = event.getPacketType();
        if (WrapperPlayClientPlayerFlying.isFlying(type)) {
            if (teleportAnswerPending) {
                teleportAnswerPending = false;
            } else {
                moved = true;
            }
            lastWasFullMove = type == PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION;
            return;
        }
        if (type == PacketType.Play.Client.TELEPORT_CONFIRM) {
            teleportAnswerPending = true;
            return;
        }
        String action = action(event, type);
        if (action == null) {
            return;
        }
        boolean resync = lastWasFullMove;
        lastWasFullMove = false;
        if (type == PacketType.Play.Client.USE_ITEM && resync) {
            // The full position packet right before an item use is vanilla's pre-use sync.
            moved = false;
            return;
        }
        onAction(action);
    }

    /** One action packet; {@code moved} decides whether it came after the tick's movement. */
    void onAction(String action) {
        if (!moved) {
            lowerBuffer();
            return;
        }
        if (isExempt()) {
            return;
        }
        if (debugging()) {
            debug(action + " after movement buffer=" + buffer());
        }
        if (raiseBuffer(1.0)) {
            violation(action + " after movement in tick " + player.clientTicks());
            resetBuffer();
        }
    }

    /** Test hook: a movement packet of the current tick. */
    void onMovement() {
        moved = true;
    }

    private String action(PacketReceiveEvent event, PacketTypeCommon type) {
        if (type == PacketType.Play.Client.INTERACT_ENTITY) {
            return checkAttacks ? "interact-entity" : null;
        }
        if (type == PacketType.Play.Client.ANIMATION) {
            return checkSwings ? "swing" : null;
        }
        if (type == PacketType.Play.Client.USE_ITEM) {
            return checkInteractions ? "use-item" : null;
        }
        if (type == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            return checkInteractions ? "place" : null;
        }
        if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            return checkSlot ? "slot" : null;
        }
        if (type == PacketType.Play.Client.PLAYER_DIGGING && checkInteractions) {
            DiggingAction digging = new WrapperPlayClientPlayerDigging(event).getAction();
            return digging == DiggingAction.START_DIGGING || digging == DiggingAction.CANCELLED_DIGGING
                    || digging == DiggingAction.FINISHED_DIGGING ? "dig" : null;
        }
        return null;
    }

    @Override
    public void onClientTick() {
        moved = false;
        lastWasFullMove = false;
    }
}
