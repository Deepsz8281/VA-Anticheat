package dev.vaplus.anticheat.check;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Global catalogue of check types. Registration happens during plugin enable; afterwards the
 * registry is frozen and only the settings can change (on reload).
 *
 * <p>Settings are published as a volatile array plus an epoch counter. Each player's
 * {@link CheckBundle} compares the epoch on its own thread and re-applies settings when it changed,
 * so reloads never mutate check state from a foreign thread.
 */
public final class CheckRegistry {

    /** A registered check type and its slot (index) in all per-check arrays. */
    public record Entry(int slot, CheckDescriptor descriptor, CheckFactory factory) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, Entry> byId = new HashMap<>();
    private volatile boolean frozen;
    private volatile CheckSettings[] settings = new CheckSettings[0];
    private volatile int epoch;

    public synchronized void register(CheckDescriptor descriptor, CheckFactory factory) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(factory, "factory");
        if (frozen) {
            throw new IllegalStateException("Check registry is frozen; register checks during enable");
        }
        if (byId.containsKey(descriptor.id())) {
            throw new IllegalArgumentException("Duplicate check id: " + descriptor.id());
        }
        Entry entry = new Entry(entries.size(), descriptor, factory);
        entries.add(entry);
        byId.put(descriptor.id(), entry);
    }

    /** Ends registration and installs the code defaults as initial settings. */
    public synchronized void freeze() {
        if (frozen) {
            return;
        }
        frozen = true;
        CheckSettings[] initial = new CheckSettings[entries.size()];
        for (Entry entry : entries) {
            initial[entry.slot()] = CheckSettings.of(entry.descriptor().defaults(), List.of());
        }
        settings = initial;
        epoch++;
    }

    public boolean frozen() {
        return frozen;
    }

    public int size() {
        return entries.size();
    }

    public List<Entry> entries() {
        requireFrozen();
        return Collections.unmodifiableList(entries);
    }

    public List<CheckDescriptor> descriptors() {
        requireFrozen();
        List<CheckDescriptor> result = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            result.add(entry.descriptor());
        }
        return Collections.unmodifiableList(result);
    }

    public CheckDescriptor descriptor(int slot) {
        return entries.get(slot).descriptor();
    }

    public Optional<CheckDescriptor> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        Entry entry;
        synchronized (this) {
            entry = byId.get(id.toLowerCase(Locale.ROOT));
        }
        return entry != null ? Optional.of(entry.descriptor()) : Optional.empty();
    }

    /** Resolves and publishes new settings for every registered check. */
    public synchronized void applySettings(Function<CheckDescriptor, CheckSettings> resolver) {
        requireFrozen();
        CheckSettings[] next = new CheckSettings[entries.size()];
        for (Entry entry : entries) {
            next[entry.slot()] = Objects.requireNonNull(resolver.apply(entry.descriptor()),
                    () -> "resolver returned null for " + entry.descriptor().id());
        }
        settings = next;
        epoch++;
    }

    public CheckSettings settings(int slot) {
        return settings[slot];
    }

    int epoch() {
        return epoch;
    }

    CheckSettings[] settingsArray() {
        return settings;
    }

    private void requireFrozen() {
        if (!frozen) {
            throw new IllegalStateException("Check registry is not frozen yet");
        }
    }
}
