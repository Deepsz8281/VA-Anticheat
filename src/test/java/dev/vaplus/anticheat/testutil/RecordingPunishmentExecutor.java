package dev.vaplus.anticheat.testutil;

import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.violation.PunishmentExecutor;
import dev.vaplus.anticheat.violation.ViolationAction;

import java.util.ArrayList;
import java.util.List;

public final class RecordingPunishmentExecutor implements PunishmentExecutor {

    public record Execution(ViolationAction action, PlayerData player, Check check, double violationLevel) {
    }

    public record Kick(PlayerData player, String messageKey) {
    }

    private final List<Execution> executions = new ArrayList<>();
    private final List<Kick> kicks = new ArrayList<>();

    @Override
    public void execute(ViolationAction action, PlayerData player, Check check, double violationLevel) {
        executions.add(new Execution(action, player, check, violationLevel));
    }

    @Override
    public void kick(PlayerData player, String messageKey) {
        kicks.add(new Kick(player, messageKey));
    }

    public List<Execution> executions() {
        return executions;
    }

    public List<Kick> kicks() {
        return kicks;
    }

    public List<ViolationAction> actions() {
        List<ViolationAction> result = new ArrayList<>(executions.size());
        for (Execution execution : executions) {
            result.add(execution.action());
        }
        return result;
    }
}
