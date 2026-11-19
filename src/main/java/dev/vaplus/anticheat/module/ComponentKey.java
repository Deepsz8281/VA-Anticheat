package dev.vaplus.anticheat.module;

import dev.vaplus.anticheat.player.PlayerData;

import java.util.Objects;
import java.util.function.Function;

/**
 * Typed handle of a per-player component (e.g. a world mirror or entity tracker) registered by a
 * module. {@link PlayerData#component(ComponentKey)} resolves it with one array access.
 */
public final class ComponentKey<T> {

    private final int index;
    private final String name;
    private final Class<T> type;
    private final Function<PlayerData, ? extends T> factory;

    ComponentKey(int index, String name, Class<T> type, Function<PlayerData, ? extends T> factory) {
        this.index = index;
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    public int index() {
        return index;
    }

    public String name() {
        return name;
    }

    public Class<T> type() {
        return type;
    }

    /** Creates the component for a new player; called once per player, before checks are created. */
    public T create(PlayerData player) {
        T component = factory.apply(player);
        if (component == null) {
            throw new IllegalStateException("Component factory '" + name + "' returned null");
        }
        return type.cast(component);
    }

    @Override
    public String toString() {
        return name;
    }
}
