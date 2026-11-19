package dev.vaplus.anticheat.obfuscation;

import com.github.retrooper.packetevents.protocol.entity.type.EntityType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Per-viewer state of the anti-health-indicator prevention: the type of every entity the viewer was
 * sent, the last real health of each living entity (needed to give a rider the true health of its
 * mount), the vehicle the viewer currently rides and the scoreboard objectives rendered as hearts.
 *
 * <p>Only touched by {@link HealthIndicatorObfuscator} on the viewer's connection thread; not
 * thread-safe.
 */
public final class HealthIndicatorState {

    public static final int NO_VEHICLE = Integer.MIN_VALUE;

    private final Map<Integer, EntityType> types = new HashMap<>();
    private final Map<Integer, Float> realHealth = new HashMap<>();
    private final Set<String> heartObjectives = new HashSet<>();
    private int vehicle = NO_VEHICLE;

    public void spawn(int entityId, EntityType type) {
        types.put(entityId, type);
        realHealth.remove(entityId);
    }

    public EntityType type(int entityId) {
        return types.get(entityId);
    }

    public void remove(int entityId) {
        types.remove(entityId);
        realHealth.remove(entityId);
        if (vehicle == entityId) {
            vehicle = NO_VEHICLE;
        }
    }

    /** Entity ids are per world instance: forget everything on join and respawn. */
    public void clearEntities() {
        types.clear();
        realHealth.clear();
        vehicle = NO_VEHICLE;
    }

    public void recordHealth(int entityId, float health) {
        if (types.containsKey(entityId)) {
            realHealth.put(entityId, health);
        }
    }

    /** Last real health sent for the entity, or {@code null} if none was seen. */
    public Float realHealth(int entityId) {
        return realHealth.get(entityId);
    }

    public int vehicle() {
        return vehicle;
    }

    public void vehicle(int entityId) {
        this.vehicle = entityId;
    }

    public void heartObjective(String name, boolean hearts) {
        if (name == null) {
            return;
        }
        if (hearts) {
            heartObjectives.add(name);
        } else {
            heartObjectives.remove(name);
        }
    }

    public boolean isHeartObjective(String name) {
        return name != null && heartObjectives.contains(name);
    }

    /** Tracked entity count (tests, diagnostics). */
    public int trackedEntities() {
        return types.size();
    }
}
