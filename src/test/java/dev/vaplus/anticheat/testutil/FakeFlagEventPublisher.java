package dev.vaplus.anticheat.testutil;

import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.violation.FlagEventPublisher;

import java.util.ArrayList;
import java.util.List;

/** Records published flag events; can cancel them. */
public final class FakeFlagEventPublisher implements FlagEventPublisher {

    public record Event(PlayerData player, Check check, double violationLevel, String details) {
    }

    private final List<Event> events = new ArrayList<>();
    private boolean cancelling;

    @Override
    public boolean publish(PlayerData player, Check check, double violationLevel, String details) {
        events.add(new Event(player, check, violationLevel, details));
        return !cancelling;
    }

    /** While {@code true}, every violation is cancelled (publish returns {@code false}). */
    public FakeFlagEventPublisher cancelAll(boolean cancelling) {
        this.cancelling = cancelling;
        return this;
    }

    public List<Event> events() {
        return events;
    }
}
