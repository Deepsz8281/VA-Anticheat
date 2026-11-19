package dev.vaplus.anticheat.check.impl.combat;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.util.Vector3f;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.check.PacketCheck;
import dev.vaplus.anticheat.check.impl.world.NearbyPlayers;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;

import java.util.Optional;

/**
 * Interact: entity interaction packets a vanilla client cannot send (Grim equivalents in brackets).
 *
 * <ul>
 *   <li>{@code self} (SelfInteract): attacking, interacting with or spectating the own entity. The
 *       pick ray never hits the own player.</li>
 *   <li>{@code cursor} (InvalidInteractCursor): the hit point of an "interact at" on another player
 *       lies outside that player's box (relative to the box's feet centre: |x|, |z| above the half
 *       width, y below 0 or above the height; standing 0.3 x 1.8 at least, larger tracked or scaled
 *       boxes count). Non-finite hit points always flag.</li>
 *   <li>{@code multi} (MultiInteractB, 1.21.2+ clients): two "interact at" packets with different hit
 *       points in one client tick. The crosshair target is picked once per tick, so every click of a
 *       tick hits the same point. Experimental.</li>
 * </ul>
 *
 * <p>Interactions with entities that do not exist (Grim's InvalidInteractTarget) are not ported:
 * Grim's class is empty, and the server already ignores unknown ids.
 */
public final class InteractCheck extends Check implements PacketCheck, ClientTickCheck {

    /** Vanilla player box half width / height, plus Grim's float margin. */
    static final double HALF_WIDTH = 0.3001;
    static final double HEIGHT = 1.8001;
    static final double BELOW = -0.0001;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("interact", CheckCategory.COMBAT)
            .displayName("Interact")
            .description("Interacting with the own entity, hit points outside the target, several hit points per tick")
            .defaults(CheckDefaults.of(CheckCategory.COMBAT)
                    .withBufferLimit(1.0)
                    .withBufferDecay(0.05)
                    .withBufferMax(4.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE)
            .build();

    private final ComponentKey<NearbyPlayers> trackedKey;

    private boolean checkSelf = true;
    private boolean checkCursor = true;
    private boolean checkMulti = true;

    private boolean hitThisTick;
    private float hitX;
    private float hitY;
    private float hitZ;

    public InteractCheck(PlayerData player, ComponentKey<NearbyPlayers> trackedKey) {
        super(player, DESCRIPTOR);
        this.trackedKey = trackedKey;
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        checkSelf = options.getBoolean("check-self", true);
        checkCursor = options.getBoolean("check-cursor", true);
        checkMulti = options.getBoolean("check-multi", true);
    }

    @Override
    protected void onReset() {
        hitThisTick = false;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type != PacketType.Play.Client.INTERACT_ENTITY) {
            return;
        }
        int own = event.getUser() != null ? event.getUser().getEntityId() : -1;
        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
        onSelfCheck(packet.getEntityId(), own);
        if (packet.getAction() == WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT) {
            Optional<Vector3f> target = packet.getTarget();
            if (target.isPresent()) {
                Vector3f hit = target.get();
                onInteractAt(packet.getEntityId(), hit.getX(), hit.getY(), hit.getZ());
            }
        }
    }

    /** Self interaction (attack, interact or spectate of the own id). */
    void onSelfCheck(int entityId, int ownId) {
        if (checkSelf && ownId > 0 && entityId == ownId && !isExempt()
                && player.abilities().gameMode() != GameMode.SPECTATOR) {
            flag("self interaction with entity " + entityId);
        }
    }

    /** An "interact at" with its hit point relative to the target's feet centre. */
    void onInteractAt(int entityId, float x, float y, float z) {
        if (isExempt()) {
            return;
        }
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            flag("cursor non-finite " + x + "," + y + "," + z);
            return;
        }
        if (checkMulti && player.tickEndMode()) {
            if (hitThisTick && (x != hitX || y != hitY || z != hitZ)) {
                flag("multi hit points " + hitX + "," + hitY + "," + hitZ + " and " + x + "," + y + "," + z + " in one tick");
            }
            hitThisTick = true;
            hitX = x;
            hitY = y;
            hitZ = z;
        }
        if (!checkCursor) {
            return;
        }
        NearbyPlayers tracked = player.component(trackedKey);
        for (int i = 0; i < tracked.size(); i++) {
            if (tracked.id(i) != entityId || !tracked.isPlayer(i)) {
                continue;
            }
            double half = Math.max(HALF_WIDTH, tracked.halfWidth(i) + 0.0001);
            double height = Math.max(HEIGHT, tracked.height(i) + 0.0001);
            if (Math.abs(x) >= half || Math.abs(z) >= half || y <= BELOW || y >= height) {
                flag("cursor " + x + "," + y + "," + z + " outside the target box (half-width "
                        + String.format(java.util.Locale.ROOT, "%.2f", half) + ", height "
                        + String.format(java.util.Locale.ROOT, "%.2f", height) + ")");
            }
            return;
        }
    }

    @Override
    public void onClientTick() {
        hitThisTick = false;
    }

    private void flag(String details) {
        if (raiseBuffer(2.0)) {
            violation(details);
            resetBuffer();
        }
    }
}
