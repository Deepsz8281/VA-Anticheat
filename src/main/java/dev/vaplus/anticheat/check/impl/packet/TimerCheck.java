package dev.vaplus.anticheat.check.impl.packet;

import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTickingState;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.check.OutboundPacketCheck;
import dev.vaplus.anticheat.latency.MarkerTracker;
import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.ExemptionCause;

import java.util.Locale;

/**
 * Timer: a client whose game clock runs faster than real time (game-speed hacks).
 *
 * <p>The check keeps a <em>client clock</em>: every client tick advances it by one tick length
 * (50 ms at the vanilla rate). A tick cannot arrive before it happened, so for an honest client
 * that clock never overtakes the server's own clock; a client ticking faster than real time
 * inevitably does. Each tick that lands in the future counts once and is taken back out of the
 * clock, so the flag rate is proportional to the speed-up (1.1x = one excess tick in ten).
 *
 * <p>Lag is not a false positive: packets delayed by the network arrive late, never early, and the
 * burst that follows a stall only brings the clock back to the present. To keep a client from
 * <em>banking</em> time (standing still or holding packets, then spending the saved ticks in one
 * burst), the clock is pulled up to the send time of the newest marker the client acknowledged,
 * minus {@code clock-drift-millis}: the client provably existed at that instant. What remains is
 * the bank a client gets by withholding the acknowledgements themselves, which is indistinguishable
 * from a connection stall and bounded by {@code latency.ack-timeout}.
 *
 * <p>Tick sources: 1.21.2+ clients send an explicit tick-end packet. Older clients are counted by
 * movement packet; their teleport answers and the duplicated position+look packet sent before an
 * item use are not ticks and are skipped. A server-side {@code /tick rate} is followed from the
 * outbound ticking-state packet: a faster rate applies at once, a slower one only after the client
 * acknowledged it.
 */
public final class TimerCheck extends Check implements ClientTickCheck, OutboundPacketCheck {

    public static final double DEFAULT_CLOCK_DRIFT_MILLIS = 120.0;
    public static final float VANILLA_TICK_RATE = 20.0F;
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final long VANILLA_TICK_NANOS = NANOS_PER_SECOND / 20L;
    /** Client ticks the clock waits for a first latency sample before anchoring without one. */
    static final int MAX_UNANCHORED_TICKS = 40;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("timer", CheckCategory.PACKET)
            .displayName("Timer")
            .description("Client ticks faster than real time")
            .defaults(CheckDefaults.of(CheckCategory.PACKET)
                    .withBufferLimit(3.0)
                    .withBufferDecay(0.01)
                    .withBufferMax(8.0))
            .exemptWhile(ExemptionCause.JOIN, ExemptionCause.RESPAWN, ExemptionCause.WORLD_CHANGE)
            .build();

    private long driftNanos = (long) (DEFAULT_CLOCK_DRIFT_MILLIS * NANOS_PER_MILLI);

    /** Estimated client time in the server's {@code nanoTime} domain; 0 = not anchored. */
    private long clock;
    private long tickNanos = VANILLA_TICK_NANOS;
    private int rateEpoch;
    private int unanchoredTicks;
    /** Excess ticks since the last violation (details only). */
    private int excessTicks;

    public TimerCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        double drift = options.getDouble("clock-drift-millis", DEFAULT_CLOCK_DRIFT_MILLIS);
        if (!Double.isFinite(drift) || drift < 0.0 || drift > 5000.0) {
            drift = DEFAULT_CLOCK_DRIFT_MILLIS;
        }
        driftNanos = (long) (drift * NANOS_PER_MILLI);
    }

    @Override
    protected void onReset() {
        clock = 0L;
        unanchoredTicks = 0;
        excessTicks = 0;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.TICKING_STATE) {
            return;
        }
        applyTickRate(new WrapperPlayServerTickingState(event).getTickRate());
    }

    /**
     * Follows a server tick-rate change (owner thread). Until the client acknowledged the change it
     * may tick at either rate, so the shorter tick length (the one that advances the clock less)
     * stays in effect during that window.
     */
    void applyTickRate(float ticksPerSecond) {
        float rate = Float.isFinite(ticksPerSecond) ? Math.max(1.0F, Math.min(10_000.0F, ticksPerSecond))
                : VANILLA_TICK_RATE;
        long next = Math.max(1L, (long) (NANOS_PER_SECOND / (double) rate));
        int epoch = ++rateEpoch;
        if (next <= tickNanos) {
            tickNanos = next;
            return;
        }
        MarkerTracker acks = player.acks();
        acks.whenAcked(acks.sendMarker(), () -> {
            if (epoch == rateEpoch) {
                tickNanos = next;
            }
        });
    }

    /** Tick length currently assumed for this client (tests, diagnostics). */
    long tickNanos() {
        return tickNanos;
    }

    @Override
    public void onClientTick() {
        long now = player.context().time().nanoTime();
        if (isExempt()) {
            clock = 0L;
            return;
        }
        if (!player.tickEndMode() && !countsAsTick(player.movementUpdate())) {
            return;
        }
        MarkerTracker acks = player.acks();
        if (clock == 0L) {
            // Anchor behind the present: this packet left the client about half a round trip ago.
            // Without a latency sample the anchor would sit too far ahead for a high-ping client
            // (and flag once its latency improves), so wait briefly for the first acknowledgement.
            long rtt = acks.smoothedRttMillis();
            if (rtt < 0 && unanchoredTicks++ < MAX_UNANCHORED_TICKS) {
                return;
            }
            unanchoredTicks = 0;
            clock = now - driftNanos - (rtt > 0 ? rtt * NANOS_PER_MILLI / 2 : 0L);
            return;
        }
        clock += tickNanos;
        long provenClientTime = acks.lastAckedSentAtNanos();
        if (provenClientTime != 0L && clock < provenClientTime - driftNanos) {
            clock = provenClientTime - driftNanos;
        }
        if (clock - now > 0L) {
            long ahead = clock - now;
            // Take the tick back out: the next flag needs a further tick of real speed-up.
            clock -= tickNanos;
            excessTicks++;
            if (raiseBuffer(1.0)) {
                violation("ahead=" + String.format(Locale.ROOT, "%.1f", ahead / (double) NANOS_PER_MILLI) + "ms"
                        + " excess-ticks=" + excessTicks
                        + " tick=" + String.format(Locale.ROOT, "%.1f", tickNanos / (double) NANOS_PER_MILLI) + "ms"
                        + " rtt=" + acks.smoothedRttMillis() + "ms");
                resetBuffer();
                excessTicks = 0;
            }
        } else {
            lowerBuffer();
        }
        if (debugging() && player.clientTicks() % 20 == 0) {
            debug("behind=" + (now - clock) / NANOS_PER_MILLI + "ms tick=" + tickNanos / NANOS_PER_MILLI
                    + "ms buffer=" + String.format(Locale.ROOT, "%.2f", buffer()));
        }
    }

    /**
     * Pre-1.21.2 clients have no tick-end packet; a movement packet stands for a tick unless it is
     * a teleport answer or the duplicated position+look packet (identical position) that 1.17+
     * clients send before using an item.
     */
    private static boolean countsAsTick(MovementUpdate update) {
        if (update.teleport()) {
            return false;
        }
        return !(update.positionChanged() && update.rotationChanged()
                && update.deltaX() == 0.0 && update.deltaY() == 0.0 && update.deltaZ() == 0.0);
    }
}
