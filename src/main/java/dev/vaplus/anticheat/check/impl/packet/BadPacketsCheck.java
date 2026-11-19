package dev.vaplus.anticheat.check.impl.packet;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.Location;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import dev.vaplus.anticheat.check.BlockCheck;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.check.PacketCheck;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;
import dev.vaplus.anticheat.player.state.PoseState;
import dev.vaplus.anticheat.world.BlockInteraction;

/**
 * BadPackets: protocol-level sanity that vanilla tolerates but no real client produces.
 *
 * <ul>
 *   <li>{@code rotation}: pitch outside [-90, 90] (vanilla clamps its own look vector; a packet
 *       outside that range is a crafted one) or non-finite values.</li>
 *   <li>{@code rate}: more movement packets per second than any client sends (default 60; vanilla
 *       sends 20, a lag spike can batch a burst, the buffer absorbs it).</li>
 *   <li>{@code cursor}: a placement hit point outside the block box ([-0.05, 1.05]).</li>
 *   <li>{@code dig}: {@code FINISHED_DIGGING}/{@code CANCELLED_DIGGING} for a block that was never
 *       started, repeated finishes, and a finish at a different position than the start.</li>
 *   <li>{@code slot}: a hotbar selection outside 0..8.</li>
 *   <li>{@code pose}: sprinting and sneaking at the same time (the client stops one of them).</li>
 * </ul>
 *
 * <p>Every signal is high-precision by construction; the buffer keeps a single glitch from alerting.
 * Category {@link CheckCategory#PACKET} has no game-mode exemption: crafted packets are never
 * legitimate, but the check still honours join/respawn/teleport brackets.
 */
public final class BadPacketsCheck extends Check implements PacketCheck, BlockCheck, ClientTickCheck {

    public static final int DEFAULT_MAX_PACKETS_PER_SECOND = 60;
    /** Tolerance around the unit block box for the placement cursor. */
    public static final double CURSOR_TOLERANCE = 0.05;
    private static final long RATE_WINDOW_NANOS = 1_000_000_000L;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("badpackets", CheckCategory.PACKET)
            .displayName("BadPackets")
            .description("Impossible rotations, cursors, dig sequences and packet rates")
            .defaults(CheckDefaults.of(CheckCategory.PACKET)
                    .withBufferLimit(1.0)
                    .withBufferDecay(0.05)
                    .withBufferMax(4.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE,
                    ExemptionCause.TELEPORT)
            .build();

    private boolean checkPitch = true;
    private boolean checkCursor = true;
    private boolean checkDigSequence = true;
    private boolean checkPose = true;
    private boolean checkSlot = true;
    private int maxPacketsPerSecond = DEFAULT_MAX_PACKETS_PER_SECOND;

    private long rateWindowStart;
    private int rateWindowCount;

    private boolean digActive;
    private int digX;
    private int digY;
    private int digZ;

    public BadPacketsCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        checkPitch = options.getBoolean("check-pitch", true);
        checkCursor = options.getBoolean("check-cursor", true);
        checkDigSequence = options.getBoolean("check-dig-sequence", true);
        checkPose = options.getBoolean("check-sprint-sneak", true);
        checkSlot = options.getBoolean("check-held-slot", true);
        int configured = options.getInt("max-movement-packets-per-second", DEFAULT_MAX_PACKETS_PER_SECOND);
        maxPacketsPerSecond = Math.max(20, Math.min(400, configured));
    }

    @Override
    protected void onReset() {
        digActive = false;
        rateWindowCount = 0;
        rateWindowStart = 0L;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            if (checkSlot) {
                onHeldSlot(new com.github.retrooper.packetevents.wrapper.play.client
                        .WrapperPlayClientHeldItemChange(event).getSlot());
            }
            return;
        }
        if (event.getPacketType() != PacketType.Play.Client.PLAYER_FLYING
                && event.getPacketType() != PacketType.Play.Client.PLAYER_POSITION
                && event.getPacketType() != PacketType.Play.Client.PLAYER_ROTATION
                && event.getPacketType() != PacketType.Play.Client.PLAYER_POSITION_AND_ROTATION) {
            return;
        }
        long now = player.context().time().nanoTime();
        if (checkPitch) {
            Location location = new WrapperPlayClientPlayerFlying(event).getLocation();
            float pitch = location.getPitch();
            float yaw = location.getYaw();
            if (!Float.isFinite(pitch) || !Float.isFinite(yaw) || pitch < -90.0F || pitch > 90.0F) {
                if (raiseBuffer(2.0)) {
                    violation("impossible rotation pitch=" + pitch + " yaw=" + yaw);
                    resetBuffer();
                }
                return;
            }
        }
        if (maxPacketsPerSecond <= 0) {
            return;
        }
        if (rateWindowStart == 0L || now - rateWindowStart >= RATE_WINDOW_NANOS) {
            rateWindowStart = now;
            rateWindowCount = 0;
        }
        rateWindowCount++;
        if (rateWindowCount > maxPacketsPerSecond) {
            rateWindowCount = 0;
            rateWindowStart = now;
            if (raiseBuffer(1.0)) {
                violation("movement-rate>" + maxPacketsPerSecond + "/s");
                resetBuffer();
            }
        }
    }

    /** Hotbar selection outside 0..8: the client only ever selects one of its nine hotbar slots. */
    void onHeldSlot(int slot) {
        if (slot < 0 || slot > 8) {
            if (raiseBuffer(2.0)) {
                violation("held-slot=" + slot);
                resetBuffer();
            }
        }
    }

    @Override
    public void onBlockInteraction(BlockInteraction interaction) {
        if (checkCursor && interaction.kind() == BlockInteraction.Kind.PLACE) {
            if (!withinUnitBox(interaction.cursorX()) || !withinUnitBox(interaction.cursorY())
                    || !withinUnitBox(interaction.cursorZ())) {
                if (raiseBuffer(1.0)) {
                    violation("placement-cursor=" + interaction.cursorX() + "," + interaction.cursorY()
                            + "," + interaction.cursorZ());
                    resetBuffer();
                }
            }
        }
        if (!checkDigSequence) {
            return;
        }
        switch (interaction.kind()) {
            case DIG_START -> {
                digActive = true;
                digX = interaction.blockX();
                digY = interaction.blockY();
                digZ = interaction.blockZ();
            }
            case DIG_CANCEL, DIG_FINISH -> {
                boolean sameBlock = digX == interaction.blockX() && digY == interaction.blockY()
                        && digZ == interaction.blockZ();
                if (!digActive || !sameBlock) {
                    if (raiseBuffer(1.0)) {
                        violation("dig-" + interaction.kind().name().toLowerCase(java.util.Locale.ROOT)
                                + " without matching start at " + interaction.blockX() + ","
                                + interaction.blockY() + "," + interaction.blockZ());
                        resetBuffer();
                    }
                }
                digActive = false;
            }
            default -> {
            }
        }
    }

    @Override
    public void onClientTick() {
        if (!checkPose) {
            return;
        }
        PoseState pose = player.pose();
        if (pose.sprinting() && pose.sneaking()) {
            if (raiseBuffer(1.0)) {
                violation("sprinting while sneaking");
                resetBuffer();
            }
        } else {
            lowerBuffer();
        }
    }

    private static boolean withinUnitBox(float value) {
        return value >= -CURSOR_TOLERANCE && value <= 1.0F + CURSOR_TOLERANCE;
    }
}
