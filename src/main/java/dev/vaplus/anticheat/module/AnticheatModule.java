package dev.vaplus.anticheat.module;

/**
 * A self-contained contribution of one area (core tracking, simulation, movement checks, combat,
 * ...). Each phase adds exactly one module to the bootstrap list and registers everything it needs
 * through the {@link ModuleContext}; modules never edit shared bootstrap or container classes.
 */
public interface AnticheatModule {

    /** Short name for logs. */
    String name();

    /** Called once during enable, before any player is tracked. */
    void register(ModuleContext context);
}
