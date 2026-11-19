package dev.vaplus.anticheat.violation;

import dev.vaplus.anticheat.check.CheckCategory;

import java.util.Objects;
import java.util.UUID;

/** Immutable entry of the violation log; the unit persisted by database backends. */
public record ViolationRecord(
        UUID playerId,
        String playerName,
        String checkId,
        CheckCategory category,
        double violationLevel,
        String details,
        long pingMillis,
        String clientVersion,
        long timestampMillis) {

    public ViolationRecord {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(playerName, "playerName");
        Objects.requireNonNull(checkId, "checkId");
        Objects.requireNonNull(category, "category");
        details = details != null ? details : "";
        clientVersion = clientVersion != null ? clientVersion : "unknown";
    }
}
