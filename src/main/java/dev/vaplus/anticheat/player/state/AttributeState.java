package dev.vaplus.anticheat.player.state;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Named attribute bases and modifiers as acknowledged by the client. Owner-thread confined;
 * no synchronisation. Attribute and modifier queries return detached immutable snapshots.
 * Typed getters return effective values; typed setters update the base and retain modifiers.
 * An update-attributes feeder should use {@link #setAttribute(String, double, List)} to replace
 * an entire packet entry, not feed an already computed effective value as its base.
 *
 * <p><strong>Vanilla modifier semantics (LEAD decision, phase 2 round 2).</strong> The effective
 * value follows the <a href="https://minecraft.wiki/w/Attribute#Modifiers">Minecraft Wiki
 * Attribute page</a> (fetched 2026-11-13): "add_value: Adds all of the modifiers' amounts to the
 * base attribute", then "add_multiplied_base: Multiplies the base attribute by (1 + sum of
 * modifiers' amounts)", then "add_multiplied_total: Multiplies the base attribute by
 * (1 + modifiers' amounts) for every modifier", and finally "The final value is clamped to fit the
 * attribute's minimum and maximum value range". As a formula:
 * <pre>{@code
 * v = (base + sum(add_value)) * (1 + sum(add_multiplied_base)) * product(1 + add_multiplied_total_i)
 * value = clamp(v, min, max)   // NaN -> min
 * }</pre>
 * This replaces the round-1 brief grouping (product for base, sum for total), which had the two
 * multiply groupings swapped; both agree whenever at most one modifier per multiply operation is
 * present. Modifiers are evaluated in modifier-id order for reproducible floating-point results.
 * {@link Operation} names map to the wire/data-pack names ({@code ADD = add_value},
 * {@code MULTIPLY_BASE = add_multiplied_base}, {@code MULTIPLY_TOTAL = add_multiplied_total});
 * do not treat the enum ordinals as protocol ids.
 *
 * <p>Ranges ({@link #minValue}/{@link #maxValue}) are the Wiki Attribute table values for the
 * tracked constants below. A NaN result is mapped to the minimum (as the round-1 bounciness clamp
 * did). Named attributes without a known range are not clamped.
 *
 * <p>26.2 attributes are always available here; the engine, not this container, gates versions.
 * Legacy generic/player name prefixes are not rewritten; a tracker must canonicalise them to the
 * constants below. {@link #version()} is a local mutation stamp so the movement engine can cache
 * derived values instead of recomputing them on every packet.
 */
public final class AttributeState {

    public static final String MOVEMENT_SPEED = "minecraft:movement_speed";
    public static final String JUMP_STRENGTH = "minecraft:jump_strength";
    public static final String STEP_HEIGHT = "minecraft:step_height";
    public static final String GRAVITY = "minecraft:gravity";
    public static final String SAFE_FALL_DISTANCE = "minecraft:safe_fall_distance";
    public static final String FALL_DAMAGE_MULTIPLIER = "minecraft:fall_damage_multiplier";
    public static final String SCALE = "minecraft:scale";
    public static final String BLOCK_INTERACTION_RANGE = "minecraft:block_interaction_range";
    public static final String ENTITY_INTERACTION_RANGE = "minecraft:entity_interaction_range";
    public static final String SNEAKING_SPEED = "minecraft:sneaking_speed";
    public static final String MOVEMENT_EFFICIENCY = "minecraft:movement_efficiency";
    public static final String WATER_MOVEMENT_EFFICIENCY = "minecraft:water_movement_efficiency";
    public static final String ATTACK_SPEED = "minecraft:attack_speed";
    /** Introduced in 26.2; version-notes.md sections 3.1 and 3.2. */
    public static final String AIR_DRAG_MODIFIER = "minecraft:air_drag_modifier";
    /** Introduced in 26.2; version-notes.md sections 3.1 and 3.2. */
    public static final String FRICTION_MODIFIER = "minecraft:friction_modifier";
    /** Introduced in 26.2; version-notes.md sections 3.1 and 3.2. */
    public static final String BOUNCINESS = "minecraft:bounciness";

    /**
     * Player base 0.1, preserving the phase-1 default. The generic attribute table's 0.7 in
     * version-notes.md section 3.1 is not a player default. The fetched
     * <a href="https://minecraft.wiki/w/Attribute#Movement_Speed">Movement Speed</a> entity table
     * explicitly lists Player at 0.1; no correction to the existing player value is made.
     */
    public static final double DEFAULT_MOVEMENT_SPEED = 0.1;
    public static final double DEFAULT_JUMP_STRENGTH = 0.41999998688697815;
    public static final double DEFAULT_STEP_HEIGHT = 0.6;
    public static final double DEFAULT_GRAVITY = 0.08;
    public static final double DEFAULT_SAFE_FALL_DISTANCE = 3.0;
    public static final double DEFAULT_FALL_DAMAGE_MULTIPLIER = 1.0;
    public static final double DEFAULT_SCALE = 1.0;
    public static final double DEFAULT_BLOCK_INTERACTION_RANGE = 4.5;
    public static final double DEFAULT_ENTITY_INTERACTION_RANGE = 3.0;
    public static final double DEFAULT_SNEAKING_SPEED = 0.3;
    public static final double DEFAULT_MOVEMENT_EFFICIENCY = 0.0;
    public static final double DEFAULT_WATER_MOVEMENT_EFFICIENCY = 0.0;
    public static final double DEFAULT_ATTACK_SPEED = 4.0;
    /** 26.2 default 1.0; version-notes.md section 3.2. */
    public static final double DEFAULT_AIR_DRAG_MODIFIER = 1.0;
    /** 26.2 default 1.0; version-notes.md sections 3.1 and 3.2. */
    public static final double DEFAULT_FRICTION_MODIFIER = 1.0;
    /** 26.2 default 0.0; version-notes.md section 3.2. */
    public static final double DEFAULT_BOUNCINESS = 0.0;
    /** Minecraft Wiki Attribute/Bounciness: minimum 0, maximum 1. */
    public static final double MIN_BOUNCINESS = 0.0;
    public static final double MAX_BOUNCINESS = 1.0;

    /**
     * Value ranges from the Minecraft Wiki Attribute table (fetched 2026-11-13), as {min, max}.
     * Introduction versions remain UNCONFIRMED (version-notes.md section 3.1).
     */
    private static final Map<String, double[]> RANGES = Map.ofEntries(
            Map.entry(MOVEMENT_SPEED, new double[]{0.0, 1024.0}),
            Map.entry(JUMP_STRENGTH, new double[]{0.0, 32.0}),
            Map.entry(STEP_HEIGHT, new double[]{0.0, 10.0}),
            Map.entry(GRAVITY, new double[]{-1.0, 1.0}),
            Map.entry(SAFE_FALL_DISTANCE, new double[]{-1024.0, 1024.0}),
            Map.entry(FALL_DAMAGE_MULTIPLIER, new double[]{0.0, 100.0}),
            Map.entry(SCALE, new double[]{0.0625, 16.0}),
            Map.entry(BLOCK_INTERACTION_RANGE, new double[]{0.0, 64.0}),
            Map.entry(ENTITY_INTERACTION_RANGE, new double[]{0.0, 64.0}),
            Map.entry(SNEAKING_SPEED, new double[]{0.0, 1.0}),
            Map.entry(MOVEMENT_EFFICIENCY, new double[]{0.0, 1.0}),
            Map.entry(WATER_MOVEMENT_EFFICIENCY, new double[]{0.0, 1.0}),
            Map.entry(ATTACK_SPEED, new double[]{0.0, 1024.0}),
            Map.entry(AIR_DRAG_MODIFIER, new double[]{0.0, 2048.0}),
            Map.entry(FRICTION_MODIFIER, new double[]{0.0, 2048.0}),
            Map.entry(BOUNCINESS, new double[]{MIN_BOUNCINESS, MAX_BOUNCINESS}));

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    private final Map<String, Entry> attributes = new LinkedHashMap<>();
    private long version;
    private int pendingWindows;

    public AttributeState() {
        reset();
    }

    /**
     * Uncertainty window bookkeeping for the attribute tracker (phase 2 round 3): an update that
     * matters for movement was sent and its first bracket marker acknowledged, so the client may or
     * may not have applied it. Not touched by {@link #reset()}/{@link #clear()}: every begin is
     * matched by its own end.
     */
    public void beginPending() {
        pendingWindows++;
    }

    /** Closes a window opened by {@link #beginPending()} (second bracket ack). */
    public void endPending() {
        if (pendingWindows > 0) {
            pendingWindows--;
        }
    }

    /** Whether the client may currently hold different movement attributes than committed here. */
    public boolean pending() {
        return pendingWindows > 0;
    }

    /** Open uncertainty windows. */
    public int pendingCount() {
        return pendingWindows;
    }

    /** Back to vanilla player defaults; drops all modifiers and any non-default named attributes. */
    public void reset() {
        version++;
        attributes.clear();
        putDefault(MOVEMENT_SPEED, DEFAULT_MOVEMENT_SPEED);
        putDefault(JUMP_STRENGTH, DEFAULT_JUMP_STRENGTH);
        putDefault(STEP_HEIGHT, DEFAULT_STEP_HEIGHT);
        putDefault(GRAVITY, DEFAULT_GRAVITY);
        putDefault(SAFE_FALL_DISTANCE, DEFAULT_SAFE_FALL_DISTANCE);
        putDefault(FALL_DAMAGE_MULTIPLIER, DEFAULT_FALL_DAMAGE_MULTIPLIER);
        putDefault(SCALE, DEFAULT_SCALE);
        putDefault(BLOCK_INTERACTION_RANGE, DEFAULT_BLOCK_INTERACTION_RANGE);
        putDefault(ENTITY_INTERACTION_RANGE, DEFAULT_ENTITY_INTERACTION_RANGE);
        putDefault(SNEAKING_SPEED, DEFAULT_SNEAKING_SPEED);
        putDefault(MOVEMENT_EFFICIENCY, DEFAULT_MOVEMENT_EFFICIENCY);
        putDefault(WATER_MOVEMENT_EFFICIENCY, DEFAULT_WATER_MOVEMENT_EFFICIENCY);
        putDefault(ATTACK_SPEED, DEFAULT_ATTACK_SPEED);
        putDefault(AIR_DRAG_MODIFIER, DEFAULT_AIR_DRAG_MODIFIER);
        putDefault(FRICTION_MODIFIER, DEFAULT_FRICTION_MODIFIER);
        putDefault(BOUNCINESS, DEFAULT_BOUNCINESS);
    }

    /** Names may be fully namespaced or bare minecraft names (e.g. movement_speed). */
    public boolean has(String name) {
        return attributes.containsKey(attributeName(name));
    }

    /**
     * Local mutation stamp: advances on every change of any base, modifier list or entry. Not a
     * protocol or world version. Lets the movement engine cache derived values cheaply.
     */
    public long version() {
        return version;
    }

    /** Whether a tracked attribute currently carries a modifier with this id (no allocation). */
    public boolean hasModifier(String name, String id) {
        Entry entry = attributes.get(attributeName(name));
        return entry != null && id != null && entry.modifiers.containsKey(id);
    }

    /**
     * Effective value as if the modifier {@code excludedId} were absent (same vanilla formula and
     * clamp). Used by the movement engine to remove modifiers it models itself (e.g. sprinting).
     * Iterates the modifier list; call it when {@link #version()} changed, not per packet.
     */
    public double valueWithout(String name, String excludedId) {
        String key = attributeName(name);
        Entry entry = entry(key);
        if (excludedId == null || !entry.modifiers.containsKey(excludedId)) {
            return entry.value;
        }
        return compute(key, entry, excludedId);
    }

    /** Lower bound of a tracked constant's range, or {@code -Infinity} for names without a range. */
    public static double minValue(String name) {
        double[] range = RANGES.get(attributeName(name));
        return range == null ? Double.NEGATIVE_INFINITY : range[0];
    }

    /** Upper bound of a tracked constant's range, or {@code +Infinity} for names without a range. */
    public static double maxValue(String name) {
        double[] range = RANGES.get(attributeName(name));
        return range == null ? Double.POSITIVE_INFINITY : range[1];
    }

    /** Raw base, before modifiers or the final range clamp. Untracked names are rejected. */
    public double base(String name) {
        return entry(name).base;
    }

    /** Cached effective value; recomputed on mutation, not on every simulation query. */
    public double value(String name) {
        return entry(name).value;
    }

    /** Set a base, retaining existing modifiers; creates an entry for a new named attribute. */
    public void setBase(String name, double base) {
        String key = attributeName(name);
        requireFinite(base, "base");
        Entry entry = attributes.computeIfAbsent(key, ignored -> new Entry(base));
        entry.base = base;
        recompute(key, entry);
    }

    /**
     * Replace a whole attribute packet entry. Input is copied before committing; a repeated
     * modifier id replaces its earlier occurrence rather than stacking. The input list is not kept.
     */
    public void setAttribute(String name, double base, List<Modifier> modifiers) {
        String key = attributeName(name);
        requireFinite(base, "base");
        Entry replacement = new Entry(base);
        for (Modifier modifier : List.copyOf(Objects.requireNonNull(modifiers, "modifiers"))) {
            replacement.modifiers.put(modifier.id(), modifier);
        }
        recompute(key, replacement);
        attributes.put(key, replacement);
    }

    /** Add or replace an id within a tracked attribute. Ids are scoped to the attribute. */
    public void addModifier(String name, Modifier modifier) {
        Entry entry = entry(name);
        Objects.requireNonNull(modifier, "modifier");
        entry.modifiers.put(modifier.id(), modifier);
        recompute(attributeName(name), entry);
    }

    /** Returns false for an untracked attribute or absent id, without creating an entry. */
    public boolean removeModifier(String name, String id) {
        String key = attributeName(name);
        requireId(id);
        Entry entry = attributes.get(key);
        if (entry == null || entry.modifiers.remove(id) == null) {
            return false;
        }
        recompute(key, entry);
        return true;
    }

    /** Clear one attribute's modifiers, retaining its base. An untracked name is a no-op. */
    public void clear(String name) {
        String key = attributeName(name);
        Entry entry = attributes.get(key);
        if (entry != null) {
            entry.modifiers.clear();
            recompute(key, entry);
        }
    }

    /** Clear all modifier lists, retaining bases and named entries; use reset() for vanilla defaults. */
    public void clear() {
        for (Map.Entry<String, Entry> attribute : attributes.entrySet()) {
            attribute.getValue().modifiers.clear();
            recompute(attribute.getKey(), attribute.getValue());
        }
    }

    /** Detached immutable list, sorted by id; later state changes do not change this list. */
    public List<Modifier> modifiers(String name) {
        return List.copyOf(entry(name).modifiers.values());
    }

    public AttributeSnapshot attribute(String name) {
        Entry entry = entry(name);
        return new AttributeSnapshot(entry.base, List.copyOf(entry.modifiers.values()), entry.value);
    }

    /** Detached immutable map with immutable modifier lists, in attribute insertion order. */
    public Map<String, AttributeSnapshot> snapshot() {
        Map<String, AttributeSnapshot> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> attribute : attributes.entrySet()) {
            Entry entry = attribute.getValue();
            copy.put(attribute.getKey(), new AttributeSnapshot(entry.base,
                    List.copyOf(entry.modifiers.values()), entry.value));
        }
        return Collections.unmodifiableMap(copy);
    }

    public double movementSpeed() {
        return value(MOVEMENT_SPEED);
    }

    public void movementSpeed(double value) {
        setBase(MOVEMENT_SPEED, value);
    }

    public double jumpStrength() {
        return value(JUMP_STRENGTH);
    }

    public void jumpStrength(double value) {
        setBase(JUMP_STRENGTH, value);
    }

    public double stepHeight() {
        return value(STEP_HEIGHT);
    }

    public void stepHeight(double value) {
        setBase(STEP_HEIGHT, value);
    }

    public double gravity() {
        return value(GRAVITY);
    }

    public void gravity(double value) {
        setBase(GRAVITY, value);
    }

    public double safeFallDistance() {
        return value(SAFE_FALL_DISTANCE);
    }

    public void safeFallDistance(double value) {
        setBase(SAFE_FALL_DISTANCE, value);
    }

    public double fallDamageMultiplier() {
        return value(FALL_DAMAGE_MULTIPLIER);
    }

    public void fallDamageMultiplier(double value) {
        setBase(FALL_DAMAGE_MULTIPLIER, value);
    }

    public double scale() {
        return value(SCALE);
    }

    public void scale(double value) {
        setBase(SCALE, value);
    }

    public double blockInteractionRange() {
        return value(BLOCK_INTERACTION_RANGE);
    }

    public void blockInteractionRange(double value) {
        setBase(BLOCK_INTERACTION_RANGE, value);
    }

    public double entityInteractionRange() {
        return value(ENTITY_INTERACTION_RANGE);
    }

    public void entityInteractionRange(double value) {
        setBase(ENTITY_INTERACTION_RANGE, value);
    }

    public double sneakingSpeed() {
        return value(SNEAKING_SPEED);
    }

    public void sneakingSpeed(double value) {
        setBase(SNEAKING_SPEED, value);
    }

    public double movementEfficiency() {
        return value(MOVEMENT_EFFICIENCY);
    }

    public void movementEfficiency(double value) {
        setBase(MOVEMENT_EFFICIENCY, value);
    }

    public double waterMovementEfficiency() {
        return value(WATER_MOVEMENT_EFFICIENCY);
    }

    public void waterMovementEfficiency(double value) {
        setBase(WATER_MOVEMENT_EFFICIENCY, value);
    }

    public double attackSpeed() {
        return value(ATTACK_SPEED);
    }

    public void attackSpeed(double value) {
        setBase(ATTACK_SPEED, value);
    }

    public double airDragModifier() {
        return value(AIR_DRAG_MODIFIER);
    }

    public void airDragModifier(double value) {
        setBase(AIR_DRAG_MODIFIER, value);
    }

    public double frictionModifier() {
        return value(FRICTION_MODIFIER);
    }

    public void frictionModifier(double value) {
        setBase(FRICTION_MODIFIER, value);
    }

    public double bounciness() {
        return value(BOUNCINESS);
    }

    public void bounciness(double value) {
        setBase(BOUNCINESS, value);
    }

    private void putDefault(String name, double base) {
        Entry entry = new Entry(base);
        recompute(name, entry);
        attributes.put(name, entry);
    }

    private Entry entry(String name) {
        String key = attributeName(name);
        Entry entry = attributes.get(key);
        if (entry == null) {
            throw new IllegalArgumentException("attribute is not tracked: " + key);
        }
        return entry;
    }

    private static String attributeName(String name) {
        Objects.requireNonNull(name, "name");
        if (RANGES.containsKey(name)) {
            // Fast path for the tracked constants: no regex matcher, so engine reads do not allocate.
            return name;
        }
        String key = name.indexOf(':') < 0 ? "minecraft:" + name : name;
        if (!IDENTIFIER.matcher(key).matches()) {
            throw new IllegalArgumentException("not an attribute identifier: " + name);
        }
        return key;
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireId(String id) {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("modifier id must not be blank");
        }
    }

    private void recompute(String name, Entry entry) {
        version++;
        entry.value = compute(name, entry, null);
    }

    /** Vanilla formula and range clamp (see class javadoc), skipping {@code excludedId} if non-null. */
    private static double compute(String name, Entry entry, String excludedId) {
        double additions = 0.0;
        double baseSum = 0.0;
        double totalProduct = 1.0;
        for (Modifier modifier : entry.modifiers.values()) {
            if (excludedId != null && excludedId.equals(modifier.id())) {
                continue;
            }
            switch (modifier.op()) {
                case ADD -> additions += modifier.amount();
                case MULTIPLY_BASE -> baseSum += modifier.amount();
                case MULTIPLY_TOTAL -> totalProduct *= 1.0 + modifier.amount();
            }
        }
        double value = (entry.base + additions) * (1.0 + baseSum) * totalProduct;
        double[] range = RANGES.get(name);
        if (range != null) {
            value = Double.isNaN(value) ? range[0] : Math.max(range[0], Math.min(range[1], value));
        }
        return value;
    }

    /**
     * Vanilla modifier operations (Wiki Attribute/Modifiers): {@code ADD = add_value},
     * {@code MULTIPLY_BASE = add_multiplied_base} (amounts summed into one factor),
     * {@code MULTIPLY_TOTAL = add_multiplied_total} (one factor per modifier). Not protocol ordinals.
     */
    public enum Operation {
        ADD, MULTIPLY_BASE, MULTIPLY_TOTAL
    }

    /** Immutable modifier; ids may be namespaced keys or legacy UUID strings, but must not be blank. */
    public record Modifier(Operation op, double amount, String id) {

        public Modifier {
            Objects.requireNonNull(op, "op");
            requireFinite(amount, "amount");
            requireId(id);
        }
    }

    public record AttributeSnapshot(double base, List<Modifier> modifiers, double value) {

        public AttributeSnapshot {
            requireFinite(base, "base");
            modifiers = List.copyOf(Objects.requireNonNull(modifiers, "modifiers"));
        }
    }

    private static final class Entry {
        private double base;
        private double value;
        private final Map<String, Modifier> modifiers = new TreeMap<>();

        private Entry(double base) {
            this.base = base;
        }
    }
}
