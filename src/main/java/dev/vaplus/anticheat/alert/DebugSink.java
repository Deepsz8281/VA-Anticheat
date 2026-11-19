package dev.vaplus.anticheat.alert;

import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.player.PlayerData;

/** Live debug output for {@code /ac debug}. {@link #isWatching} must be cheap: it is called per packet. */
public interface DebugSink {

    DebugSink NONE = new DebugSink() {
        @Override
        public boolean isWatching(PlayerData player, CheckDescriptor check) {
            return false;
        }

        @Override
        public void send(PlayerData player, CheckDescriptor check, String message) {
        }
    };

    boolean isWatching(PlayerData player, CheckDescriptor check);

    void send(PlayerData player, CheckDescriptor check, String message);
}
