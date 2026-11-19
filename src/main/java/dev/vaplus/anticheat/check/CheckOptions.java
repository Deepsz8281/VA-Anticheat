package dev.vaplus.anticheat.check;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Check-specific tuning values from the {@code options:} block of a check's config section.
 * Checks should read these once in {@link Check#onSettingsApplied(CheckSettings)} and cache them in
 * fields rather than querying the map on the packet hot path.
 */
public final class CheckOptions {

    public static final CheckOptions EMPTY = new CheckOptions(Map.of());

    private final Map<String, Object> values;

    public CheckOptions(Map<String, ?> values) {
        Map<String, Object> copy = new HashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.put(entry.getKey(), entry.getValue());
            }
        }
        this.values = Collections.unmodifiableMap(copy);
    }

    public boolean contains(String key) {
        return values.containsKey(key);
    }

    public Set<String> keys() {
        return values.keySet();
    }

    public double getDouble(String key, double fallback) {
        return values.get(key) instanceof Number number ? number.doubleValue() : fallback;
    }

    public int getInt(String key, int fallback) {
        return values.get(key) instanceof Number number ? number.intValue() : fallback;
    }

    public long getLong(String key, long fallback) {
        return values.get(key) instanceof Number number ? number.longValue() : fallback;
    }

    public boolean getBoolean(String key, boolean fallback) {
        return values.get(key) instanceof Boolean bool ? bool : fallback;
    }

    public String getString(String key, String fallback) {
        Object value = values.get(key);
        return value != null ? value.toString() : fallback;
    }

    public List<String> getStringList(String key) {
        if (!(values.get(key) instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element != null) {
                result.add(element.toString());
            }
        }
        return List.copyOf(result);
    }

    @Override
    public String toString() {
        return values.toString();
    }
}
