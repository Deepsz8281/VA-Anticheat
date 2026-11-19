package dev.vaplus.anticheat.alert;

import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.config.MessageService;
import dev.vaplus.anticheat.player.PlayerData;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@code /ac debug} subscriptions. {@link #isWatching} is called per packet by checks, so the
 * common case (nobody debugging) is a single volatile read.
 */
public final class DebugService implements DebugSink {

    /**
     * @param target  watched player, {@code null} for everybody
     * @param checkId watched check id, {@code null} for all checks
     */
    public record Subscription(UUID viewerId, CommandSender viewer, UUID target, String checkId) {
        boolean matches(PlayerData player, CheckDescriptor check) {
            return (target == null || target.equals(player.uuid()))
                    && (checkId == null || checkId.equals(check.id()));
        }
    }

    /** Console subscriptions use this id. */
    public static final UUID CONSOLE_ID = new UUID(0L, 0L);

    private final Plugin plugin;
    private final MessageService messages;
    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();
    private volatile boolean active;

    public DebugService(Plugin plugin, MessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
    }

    /** Whether {@code sender} can receive debug lines from packet threads safely. */
    public static boolean supportsViewer(CommandSender sender) {
        return sender instanceof Player || sender instanceof ConsoleCommandSender;
    }

    /** Toggles a subscription; returns {@code true} if it is now active. Viewer: player or console. */
    public synchronized boolean toggle(CommandSender viewer, UUID viewerId, UUID target, String checkId) {
        if (!supportsViewer(viewer)) {
            throw new IllegalArgumentException("Debug viewers must be players or the console");
        }
        for (Subscription subscription : subscriptions) {
            if (subscription.viewerId().equals(viewerId)
                    && java.util.Objects.equals(subscription.target(), target)
                    && java.util.Objects.equals(subscription.checkId(), checkId)) {
                subscriptions.remove(subscription);
                active = !subscriptions.isEmpty();
                return false;
            }
        }
        subscriptions.add(new Subscription(viewerId, viewer, target, checkId));
        active = true;
        return true;
    }

    public synchronized void removeViewer(UUID viewerId) {
        subscriptions.removeIf(subscription -> subscription.viewerId().equals(viewerId));
        active = !subscriptions.isEmpty();
    }

    @Override
    public boolean isWatching(PlayerData player, CheckDescriptor check) {
        if (!active) {
            return false;
        }
        for (Subscription subscription : subscriptions) {
            if (subscription.matches(player, check)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void send(PlayerData player, CheckDescriptor check, String message) {
        if (!active) {
            return;
        }
        Component line = null;
        for (Subscription subscription : subscriptions) {
            if (!subscription.matches(player, check)) {
                continue;
            }
            if (line == null) {
                line = messages.render("debug-line",
                        "player", player.name(),
                        "check", check.displayName(),
                        "message", message);
            }
            Component finalLine = line;
            CommandSender viewer = subscription.viewer();
            if (viewer instanceof Player viewingPlayer) {
                viewingPlayer.getScheduler().run(plugin, task -> viewingPlayer.sendMessage(finalLine), null);
            } else {
                viewer.sendMessage(finalLine);
            }
        }
    }
}
