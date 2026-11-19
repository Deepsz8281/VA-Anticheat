package dev.vaplus.anticheat.check.impl.packet;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.common.client.WrapperCommonClientSettings;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatCommand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatCommandUnsigned;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientChatMessage;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientSettings;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientTabComplete;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.PacketCheck;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.player.state.PoseState;

/**
 * Chat: chat packets a vanilla client cannot send (Grim ChatA-D).
 *
 * <ul>
 *   <li>{@code blank-tab} (ChatA): a suggestion request for "/" or blank text. The client completes
 *       those from its own command tree. Needs a repeat.</li>
 *   <li>{@code invalid-message} (ChatB): an empty chat message, one with leading or trailing spaces,
 *       one starting with "/" (commands have their own packet since 1.19), or a command with leading
 *       or trailing spaces. The client trims everything it sends.</li>
 *   <li>{@code moving} (ChatC): chatting while sprinting or holding movement keys (1.21.2+ key
 *       state). An open chat screen releases every key. Swimming, gliding and vehicles are skipped.
 *       Experimental; messages matching {@code exempt-regex} are ignored.</li>
 *   <li>{@code hidden} (ChatD): a chat message while the client's chat visibility is "hidden"; the
 *       client cannot open the chat then. Commands are not judged (books and signs can run them).</li>
 * </ul>
 * The packets are not cancelled; the punishment profile decides.
 */
public final class ChatCheck extends Check implements PacketCheck {

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("chat", CheckCategory.PACKET)
            .displayName("Chat")
            .description("Chat packets a vanilla client cannot send, chatting while moving or with hidden chat")
            .defaults(CheckDefaults.of(CheckCategory.PACKET)
                    .withBufferLimit(1.0)
                    .withBufferDecay(0.05)
                    .withBufferMax(4.0))
            .build();

    private boolean checkBlankTab = true;
    private boolean checkInvalidMessage = true;
    private boolean checkMoving = true;
    private boolean checkHidden = true;
    private java.util.regex.Pattern exemptRegex;

    public ChatCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        checkBlankTab = options.getBoolean("check-blank-tab", true);
        checkInvalidMessage = options.getBoolean("check-invalid-message", true);
        checkMoving = options.getBoolean("check-moving", true);
        checkHidden = options.getBoolean("check-hidden", true);
        String regex = options.getString("exempt-regex", "");
        java.util.regex.Pattern compiled = null;
        if (regex != null && !regex.isBlank()) {
            try {
                compiled = java.util.regex.Pattern.compile(regex);
            } catch (java.util.regex.PatternSyntaxException invalid) {
                compiled = null;
            }
        }
        exemptRegex = compiled;
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Client.CHAT_MESSAGE) {
            onMessage(new WrapperPlayClientChatMessage(event).getMessage());
        } else if (type == PacketType.Play.Client.CHAT_COMMAND) {
            onCommand(new WrapperPlayClientChatCommand(event).getCommand());
        } else if (type == PacketType.Play.Client.CHAT_COMMAND_UNSIGNED) {
            onCommand(new WrapperPlayClientChatCommandUnsigned(event).getCommand());
        } else if (type == PacketType.Play.Client.TAB_COMPLETE) {
            onTabComplete(new WrapperPlayClientTabComplete(event).getText());
        } else if (type == PacketType.Play.Client.CLIENT_SETTINGS) {
            player.chatHidden(new WrapperPlayClientSettings(event).getChatVisibility()
                    == WrapperCommonClientSettings.ChatVisibility.HIDDEN);
        }
    }

    void onMessage(String message) {
        String text = message != null ? message : "";
        if (checkInvalidMessage && (text.isEmpty() || !text.strip().equals(text) || text.startsWith("/"))) {
            violation("invalid-message \"" + shorten(text) + "\"");
            return;
        }
        if (checkHidden && player.chatHidden()) {
            violation("hidden chatted with hidden chat");
            return;
        }
        moving(text);
    }

    void onCommand(String command) {
        String text = "/" + (command != null ? command : "");
        if (checkInvalidMessage && !text.strip().equals(text)) {
            violation("invalid-message command \"" + shorten(text) + "\"");
            return;
        }
        moving(text);
    }

    void onTabComplete(String text) {
        if (checkBlankTab && (text == null || text.equals("/") || text.isBlank()) && raiseBuffer(1.0)) {
            violation("blank-tab \"" + (text != null ? text : "") + "\"");
            resetBuffer();
        }
    }

    private void moving(String text) {
        if (!checkMoving || isExempt() || player.vehicle().inVehicle()) {
            return;
        }
        if (exemptRegex != null && exemptRegex.matcher(text).matches()) {
            return;
        }
        PoseState pose = player.pose();
        if (pose.swimming() || pose.gliding()) {
            return;
        }
        boolean keys = player.tickEndMode() && pose.inputKnown()
                && (pose.forward() || pose.backward() || pose.left() || pose.right() || pose.jump());
        if ((keys || pose.sprinting()) && raiseBuffer(1.0)) {
            violation("moving chatted with" + (keys ? " movement keys" : "") + (pose.sprinting() ? " sprint" : ""));
            resetBuffer();
        }
    }

    private static String shorten(String text) {
        return text.length() > 32 ? text.substring(0, 32) + "..." : text;
    }
}
