package dev.vaplus.anticheat.check.impl.packet;

import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Post (packet order) and Blink (held movement)")
class PostAndBlinkCheckTest {

    private static PlayerData player(Harness[] out) {
        Harness harness = TestContexts.builder().module(new PacketChecksModule()).build();
        out[0] = harness;
        PlayerData player = harness.newPlayer();
        player.enableTickEndMode();
        return player;
    }

    @Nested
    @DisplayName("post")
    class Post {

        @Test
        @DisplayName("attacks before the tick's movement are vanilla; after it they flag")
        void order() {
            Harness[] harness = new Harness[1];
            PlayerData player = player(harness);
            PostCheck post = player.checks().get(PostCheck.class);
            for (int i = 0; i < 50; i++) {
                post.onAction("interact-entity");
                post.onAction("swing");
                post.onMovement();
                player.onClientTick();
            }
            assertEquals(0, post.violationCount());

            for (int i = 0; i < 2; i++) {
                post.onMovement();
                post.onAction("interact-entity");
                post.onAction("swing");
                player.onClientTick();
            }
            assertEquals(1, post.violationCount(), () -> "alerts: " + harness[0].alerts.alerts());
        }

        @Test
        @DisplayName("a new tick starts clean")
        void tickResets() {
            Harness[] harness = new Harness[1];
            PlayerData player = player(harness);
            PostCheck post = player.checks().get(PostCheck.class);
            for (int i = 0; i < 20; i++) {
                post.onMovement();
                player.onClientTick();
                post.onAction("interact-entity");
            }
            assertEquals(0, post.violationCount());
        }
    }

    @Nested
    @DisplayName("blink")
    class Blink {

        @Test
        @DisplayName("one movement per tick is vanilla; a released burst flags at once")
        void burst() {
            Harness[] harness = new Harness[1];
            PlayerData player = player(harness);
            BlinkCheck blink = player.checks().get(BlinkCheck.class);
            for (int i = 0; i < 100; i++) {
                blink.onMovement();
                player.onClientTick();
            }
            // Teleport answer in the same tick as the regular movement: allowed.
            blink.onTeleportConfirm();
            blink.onMovement();
            blink.onMovement();
            player.onClientTick();
            assertEquals(0, blink.violationCount());

            for (int i = 0; i < 6; i++) {
                blink.onMovement();
            }
            player.onClientTick();
            assertEquals(1, blink.violationCount(), () -> "alerts: " + harness[0].alerts.alerts());
        }

        @Test
        @DisplayName("ticking on without any movement packet for longer than vanilla's 20 ticks flags")
        void withheld() {
            Harness[] harness = new Harness[1];
            PlayerData player = player(harness);
            BlinkCheck blink = player.checks().get(BlinkCheck.class);
            for (int i = 0; i < 39; i++) {
                player.onClientTick();
            }
            assertEquals(0, blink.violationCount(), "no movement seen yet (loading screen)");
            blink.onMovement();
            player.onClientTick();
            for (int round = 0; round < 2; round++) {
                for (int i = 0; i < BlinkCheck.DEFAULT_MAX_SILENT_TICKS + 2; i++) {
                    player.onClientTick();
                }
                blink.onMovement();
                player.onClientTick();
            }
            assertEquals(1, blink.violationCount(), () -> "alerts: " + harness[0].alerts.alerts());
        }
    }
}
