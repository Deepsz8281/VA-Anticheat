package dev.vaplus.anticheat.check.impl.packet;

import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.module.ModuleContext;

/**
 * Protocol-level checks. Installed last: they only need raw packets and player state, not the world
 * mirror or the simulation, and they must keep running while other checks are exempt.
 */
public final class PacketChecksModule implements AnticheatModule {

    @Override
    public String name() {
        return "packet-checks";
    }

    @Override
    public void register(ModuleContext context) {
        context.registerCheck(BadPacketsCheck.DESCRIPTOR, BadPacketsCheck::new);
        context.registerCheck(TimerCheck.DESCRIPTOR, TimerCheck::new);
        context.registerCheck(CrasherCheck.DESCRIPTOR, CrasherCheck::new);
        context.registerCheck(PostCheck.DESCRIPTOR, PostCheck::new);
        context.registerCheck(BlinkCheck.DESCRIPTOR, BlinkCheck::new);
        context.registerCheck(MultiActionsCheck.DESCRIPTOR, MultiActionsCheck::new);
        context.registerCheck(ChatCheck.DESCRIPTOR, ChatCheck::new);
        context.registerCheck(ClientBrandCheck.DESCRIPTOR, ClientBrandCheck::new);
        context.registerCheck(PacketOrderCheck.DESCRIPTOR, PacketOrderCheck::new);
    }

    /** Descriptors in registration order (tests, diagnostics). */
    public static CheckDescriptor[] descriptors() {
        return new CheckDescriptor[]{BadPacketsCheck.DESCRIPTOR, TimerCheck.DESCRIPTOR, CrasherCheck.DESCRIPTOR,
                PostCheck.DESCRIPTOR, BlinkCheck.DESCRIPTOR, MultiActionsCheck.DESCRIPTOR, ChatCheck.DESCRIPTOR,
                ClientBrandCheck.DESCRIPTOR, PacketOrderCheck.DESCRIPTOR};
    }
}
