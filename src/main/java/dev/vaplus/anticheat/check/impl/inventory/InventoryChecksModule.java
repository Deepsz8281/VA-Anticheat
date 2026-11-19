package dev.vaplus.anticheat.check.impl.inventory;

import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.module.ModuleContext;

/**
 * Inventory checks. They track container windows privately from inbound/outbound packets and need
 * neither the world mirror nor the simulation.
 */
public final class InventoryChecksModule implements AnticheatModule {

    @Override
    public String name() {
        return "inventory-checks";
    }

    @Override
    public void register(ModuleContext context) {
        context.registerCheck(ChestStealerCheck.DESCRIPTOR, ChestStealerCheck::new);
        context.registerCheck(InventoryMoveCheck.DESCRIPTOR, InventoryMoveCheck::new);
        context.registerCheck(AutoArmorCheck.DESCRIPTOR, AutoArmorCheck::new);
        context.registerCheck(AutoTotemCheck.DESCRIPTOR, AutoTotemCheck::new);
    }

    /** Descriptors in registration order (tests, diagnostics). */
    public static CheckDescriptor[] descriptors() {
        return new CheckDescriptor[]{ChestStealerCheck.DESCRIPTOR, InventoryMoveCheck.DESCRIPTOR,
                AutoArmorCheck.DESCRIPTOR, AutoTotemCheck.DESCRIPTOR};
    }
}
