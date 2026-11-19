package dev.vaplus.anticheat.alert;

import dev.vaplus.anticheat.AnticheatContext;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.config.MessageService;
import dev.vaplus.anticheat.config.Placeholders;
import dev.vaplus.anticheat.platform.Permissions;
import dev.vaplus.anticheat.player.PlayerData;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Delivers alerts to staff. The recipient set is maintained from join/quit/permission refresh on
 * region threads; delivery from packet threads hops onto each recipient's entity scheduler so the
 * Bukkit API is only touched from the thread that owns the recipient (Folia-safe).
 */
public final class AlertService implements AlertSink {

    private final Plugin plugin;
    private final MessageService messages;
    private final Supplier<AnticheatContext> context;
    private final ConcurrentHashMap<UUID, Player> recipients = new ConcurrentHashMap<>();

    public AlertService(Plugin plugin, MessageService messages, Supplier<AnticheatContext> context) {
        this.plugin = plugin;
        this.messages = messages;
        this.context = context;
    }

    /** Toggles alerts for {@code player}; returns the new state. Caller checks the permission. */
    public boolean toggle(Player player) {
        if (recipients.remove(player.getUniqueId()) != null) {
            return false;
        }
        recipients.put(player.getUniqueId(), player);
        return true;
    }

    public boolean receiving(UUID playerId) {
        return recipients.containsKey(playerId);
    }

    /** Region thread of the joining player. */
    public void handleJoin(Player player) {
        if (context.get().settings().alertsEnableOnJoin() && player.hasPermission(Permissions.ALERTS)) {
            recipients.put(player.getUniqueId(), player);
        }
    }

    public void handleQuit(UUID playerId) {
        recipients.remove(playerId);
    }

    /** Region thread of {@code player}: drops recipients whose permission was revoked. */
    public void revalidate(Player player) {
        if (recipients.containsKey(player.getUniqueId()) && !player.hasPermission(Permissions.ALERTS)) {
            recipients.remove(player.getUniqueId());
        }
    }

    /**
     * Captures the placeholder values on the packet thread (cheap) and leaves MiniMessage rendering
     * and delivery to the async scheduler, keeping template parsing off the netty event loop.
     */
    @Override
    public void alert(PlayerData player, Check check, double violationLevel, String details) {
        String[] pairs = Placeholders.forViolation(player, check, violationLevel, details);
        String key = check.descriptor().experimental() ? "alert-experimental" : "alert";
        boolean console = context.get().settings().alertsToConsole();
        Bukkit.getAsyncScheduler().runNow(plugin, task -> deliver(messages.render(key, pairs), console));
    }

    @Override
    public void broadcast(Component message) {
        Bukkit.getAsyncScheduler().runNow(plugin, task -> deliver(message, true));
    }

    private void deliver(Component message, boolean console) {
        for (Player recipient : recipients.values()) {
            recipient.getScheduler().run(plugin, task -> recipient.sendMessage(message), null);
        }
        if (console) {
            Bukkit.getConsoleSender().sendMessage(message);
        }
    }
}
