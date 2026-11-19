package dev.vaplus.anticheat.testutil;

import dev.vaplus.anticheat.alert.AlertSink;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.player.PlayerData;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.List;

public final class RecordingAlertSink implements AlertSink {

    public record Alert(PlayerData player, Check check, double violationLevel, String details) {
    }

    private final List<Alert> alerts = new ArrayList<>();
    private final List<Component> broadcasts = new ArrayList<>();

    @Override
    public void alert(PlayerData player, Check check, double violationLevel, String details) {
        alerts.add(new Alert(player, check, violationLevel, details));
    }

    @Override
    public void broadcast(Component message) {
        broadcasts.add(message);
    }

    public List<Alert> alerts() {
        return alerts;
    }

    public List<Component> broadcasts() {
        return broadcasts;
    }
}
