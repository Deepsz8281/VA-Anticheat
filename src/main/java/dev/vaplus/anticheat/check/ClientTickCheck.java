package dev.vaplus.anticheat.check;

/**
 * Capability interface for checks that need a callback once per client tick (1.21.2+ clients send an
 * explicit tick-end packet; for older clients every movement packet counts as one tick).
 * Implementations must extend {@link Check}.
 */
public interface ClientTickCheck {

    void onClientTick();
}
