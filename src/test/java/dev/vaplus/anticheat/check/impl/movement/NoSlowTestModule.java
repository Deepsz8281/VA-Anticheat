package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.module.ModuleContext;

/** Test-only module registering just the NoSlow check (the lead wires it into the real module). */
final class NoSlowTestModule implements AnticheatModule {

    @Override
    public String name() {
        return "noslow-test";
    }

    @Override
    public void register(ModuleContext context) {
        context.registerCheck(NoSlowCheck.DESCRIPTOR, NoSlowCheck::new);
    }
}
