package dev.vaplus.anticheat.testutil;

import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;

import java.util.function.UnaryOperator;

/** Shortcuts for building descriptors in tests. */
public final class Descriptors {

    private Descriptors() {
    }

    public static CheckDescriptor of(String id, CheckCategory category) {
        return CheckDescriptor.builder(id, category).build();
    }

    public static CheckDescriptor of(String id, CheckCategory category, UnaryOperator<CheckDefaults> defaults) {
        return CheckDescriptor.builder(id, category).defaults(defaults.apply(CheckDefaults.of(category))).build();
    }

    public static CheckDescriptor movement(String id) {
        return of(id, CheckCategory.MOVEMENT);
    }

    public static CheckDescriptor combat(String id) {
        return of(id, CheckCategory.COMBAT);
    }

    public static CheckDescriptor packet(String id) {
        return of(id, CheckCategory.PACKET);
    }
}
