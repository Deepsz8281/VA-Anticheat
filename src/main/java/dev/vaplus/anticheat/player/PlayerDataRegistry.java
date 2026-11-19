package dev.vaplus.anticheat.player;

import com.github.retrooper.packetevents.protocol.player.User;
import dev.vaplus.anticheat.AnticheatContext;
import dev.vaplus.anticheat.platform.UserTransport;

import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps connections to {@link PlayerData}. Lookups by {@link User} happen for every packet, so the
 * primary map is keyed by the PacketEvents user object itself.
 */
public final class PlayerDataRegistry {

    private final AnticheatContext context;
    private final ConcurrentHashMap<User, PlayerData> byUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PlayerData> byId = new ConcurrentHashMap<>();

    public PlayerDataRegistry(AnticheatContext context) {
        this.context = context;
    }

    public PlayerData get(User user) {
        return user == null ? null : byUser.get(user);
    }

    public PlayerData get(UUID uuid) {
        return uuid == null ? null : byId.get(uuid);
    }

    /**
     * Returns the data for {@code user}, creating it on first use. Returns {@code null} while the
     * user's profile (UUID) is not known yet.
     */
    public PlayerData getOrCreate(User user) {
        if (user == null || user.getUUID() == null) {
            return null;
        }
        PlayerData data = byUser.computeIfAbsent(user, this::create);
        byId.put(data.uuid(), data);
        return data;
    }

    /** Case-insensitive name lookup for commands (linear scan, not for packet paths). */
    public PlayerData findByName(String name) {
        if (name == null) {
            return null;
        }
        String wanted = name.toLowerCase(Locale.ROOT);
        for (PlayerData data : byId.values()) {
            if (data.name().toLowerCase(Locale.ROOT).equals(wanted)) {
                return data;
            }
        }
        return null;
    }

    public void remove(User user) {
        PlayerData data = user == null ? null : byUser.remove(user);
        if (data != null) {
            data.markDisconnecting();
            byId.remove(data.uuid(), data);
        }
    }

    public void remove(UUID uuid) {
        PlayerData data = uuid == null ? null : byId.remove(uuid);
        if (data != null) {
            data.markDisconnecting();
            byUser.values().remove(data);
        }
    }

    /**
     * Removes the data of {@code uuid} only if it belongs to {@code player} (or has no Bukkit player
     * yet), so a quit event of an old session cannot untrack a newer login with the same UUID.
     */
    public void removeIfAttached(UUID uuid, Object player) {
        PlayerData data = uuid == null ? null : byId.get(uuid);
        if (data != null && (data.bukkitPlayer() == null || data.bukkitPlayer() == player)) {
            remove(uuid);
        }
    }

    public Collection<PlayerData> all() {
        return Collections.unmodifiableCollection(byId.values());
    }

    public int size() {
        return byId.size();
    }

    public void clear() {
        for (PlayerData data : byId.values()) {
            data.markDisconnecting();
        }
        byUser.clear();
        byId.clear();
    }

    private PlayerData create(User user) {
        String name = user.getName() != null ? user.getName() : user.getUUID().toString();
        return PlayerData.create(context, user.getUUID(), name, user.getClientVersion(), new UserTransport(user));
    }
}
