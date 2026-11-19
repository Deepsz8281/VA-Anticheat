package dev.vaplus.anticheat.check;

import com.github.retrooper.packetevents.event.PacketSendEvent;

/**
 * Capability interface for checks that observe packets sent to the player. Implementations must
 * extend {@link Check}. Called for every outbound packet (chunks, entity updates, ...), so
 * implementations should filter on the packet type first and return immediately otherwise.
 */
public interface OutboundPacketCheck {

    void onPacketSend(PacketSendEvent event);
}
