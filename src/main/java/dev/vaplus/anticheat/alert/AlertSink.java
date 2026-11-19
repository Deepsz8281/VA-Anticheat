package dev.vaplus.anticheat.alert;

import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.player.PlayerData;
import net.kyori.adventure.text.Component;

/** Destination of staff alerts. Called from packet threads; implementations handle thread hopping. */
public interface AlertSink {

    void alert(PlayerData player, Check check, double violationLevel, String details);

    /** Sends an arbitrary line to every staff member that currently receives alerts. */
    void broadcast(Component message);
}
