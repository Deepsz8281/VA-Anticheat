package dev.vaplus.anticheat.platform;

import dev.vaplus.anticheat.alert.AlertSink;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.config.MessageService;
import dev.vaplus.anticheat.config.Placeholders;
import dev.vaplus.anticheat.config.TextTemplates;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.violation.PunishmentExecutor;
import dev.vaplus.anticheat.violation.ViolationAction;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Folia-safe action execution: kicks run on the player's entity scheduler, console commands on the
 * global region scheduler, staff notices through the alert sink.
 */
public final class BukkitPunishmentExecutor implements PunishmentExecutor {

    private final Plugin plugin;
    private final MessageService messages;
    private final AlertSink alerts;

    public BukkitPunishmentExecutor(Plugin plugin, MessageService messages, AlertSink alerts) {
        this.plugin = plugin;
        this.messages = messages;
        this.alerts = alerts;
    }

    @Override
    public void execute(ViolationAction action, PlayerData player, Check check, double violationLevel) {
        String[] pairs = Placeholders.forViolation(player, check, violationLevel, "");
        switch (action) {
            case ViolationAction.Kick kick -> {
                // Commands and notices still run for a player who is already being kicked
                // (e.g. "run: [kick, cmd: ban ...]"), only a second kick is pointless.
                if (player.claimKick(player.context().time().nanoTime())) {
                    String template = kick.template() != null ? kick.template() : messages.raw("kick-message");
                    disconnect(player, messages.renderTemplate(template, pairs));
                }
            }
            case ViolationAction.ConsoleCommand command -> {
                String line = TextTemplates.substitute(command.template(), pairs, false);
                Bukkit.getGlobalRegionScheduler().execute(plugin,
                        () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line));
            }
            case ViolationAction.StaffNotice notice -> {
                String template = notice.template() != null ? notice.template() : messages.raw("staff-notice");
                alerts.broadcast(messages.renderTemplate(template, pairs));
            }
        }
    }

    /** Callers must have claimed the kick via {@link PlayerData#claimKick(long)}. */
    @Override
    public void kick(PlayerData player, String messageKey) {
        disconnect(player, messages.render(messageKey, Placeholders.forPlayer(player)));
    }

    private void disconnect(PlayerData player, Component reason) {
        Player bukkit = player.bukkitPlayer();
        if (bukkit == null) {
            player.markDisconnecting();
            // Not joined yet (no Bukkit player to kick): drop the connection directly.
            player.transport().close();
            return;
        }
        bukkit.getScheduler().run(plugin, task -> bukkit.kick(reason), null);
    }
}
