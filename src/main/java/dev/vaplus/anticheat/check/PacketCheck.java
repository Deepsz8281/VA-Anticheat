package dev.vaplus.anticheat.check;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;

/**
 * Capability interface for checks that inspect raw incoming packets. Implementations must extend
 * {@link Check}. Packet checks run <em>before</em> the pipeline updates player state, so
 * {@code player} still reflects the previous packet. Checks may cancel the event.
 *
 * <p>Prefer data the pipeline already parsed (movement, interactions) over constructing wrappers;
 * every wrapper construction re-reads the packet buffer. Outbound packets are a separate capability
 * ({@link OutboundPacketCheck}) because the server sends far more packets than the client.
 */
public interface PacketCheck {

    void onPacketReceive(PacketReceiveEvent event);
}
