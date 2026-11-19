package dev.vaplus.anticheat.testutil;

import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.movement.SetbackHandler;
import dev.vaplus.anticheat.player.PlayerData;

import java.util.ArrayList;
import java.util.List;

/**
 * Records setback requests. Like the production handler it marks the movement state, so the
 * safe-position bookkeeping of the movement pipeline behaves realistically.
 */
public final class RecordingSetbackHandler implements SetbackHandler {

    public record Request(PlayerData player, Check cause) {
    }

    public record TargetRequest(PlayerData player, SetbackTarget target, String reason) {
    }

    private final List<Request> requests = new ArrayList<>();
    private final List<TargetRequest> targetRequests = new ArrayList<>();

    @Override
    public void request(PlayerData player, Check cause) {
        requests.add(new Request(player, cause));
        player.movement().markSetbackRequested();
    }

    @Override
    public void request(PlayerData player, SetbackTarget target, String reason) {
        targetRequests.add(new TargetRequest(player, target, reason));
        player.movement().markSetbackRequested();
    }

    public List<Request> requests() {
        return requests;
    }

    public List<TargetRequest> targetRequests() {
        return targetRequests;
    }
}
