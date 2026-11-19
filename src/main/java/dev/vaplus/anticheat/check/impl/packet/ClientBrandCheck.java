package dev.vaplus.anticheat.check.impl.packet;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckCategory;
import dev.vaplus.anticheat.check.CheckDefaults;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckOptions;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.OutboundPacketCheck;
import dev.vaplus.anticheat.check.PacketCheck;
import dev.vaplus.anticheat.player.PlayerData;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ClientBrand: the client's self-reported brand ({@code minecraft:brand}, e.g. "vanilla" or
 * "fabric"). The brand is sent during the configuration phase; the pipeline keeps it until the join
 * and {@code /ac info} shows it. Brands are trivial to fake, so this only catches clients that
 * announce themselves.
 *
 * <ul>
 *   <li>{@code blocked}: the brand contains one of the comma separated {@code blocked-brands}
 *       (case-insensitive). Empty by default.</li>
 *   <li>{@code invalid}: an empty, undecodable or longer than 64 bytes brand payload (Grim's limit).</li>
 * </ul>
 */
public final class ClientBrandCheck extends Check implements PacketCheck, OutboundPacketCheck {

    public static final String CHANNEL = "minecraft:brand";
    public static final int MAX_BRAND_BYTES = 64;

    public static final CheckDescriptor DESCRIPTOR = CheckDescriptor.builder("clientbrand", CheckCategory.PACKET)
            .displayName("ClientBrand")
            .description("Blocked or malformed client brands")
            .defaults(CheckDefaults.of(CheckCategory.PACKET).withBufferLimit(0.0))
            .build();

    private boolean checkInvalid = true;
    private List<String> blocked = List.of();
    private boolean judged;

    public ClientBrandCheck(PlayerData player) {
        super(player, DESCRIPTOR);
        onSettingsApplied(settings());
    }

    @Override
    protected void onSettingsApplied(CheckSettings settings) {
        CheckOptions options = settings.options();
        checkInvalid = options.getBoolean("check-invalid", true);
        List<String> list = new ArrayList<>();
        for (String entry : options.getString("blocked-brands", "").split(",")) {
            String trimmed = entry.strip().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        blocked = List.copyOf(list);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.JOIN_GAME && !judged && player.clientBrandPayload() != null) {
            onBrand(player.clientBrandPayload());
        }
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PLUGIN_MESSAGE) {
            WrapperPlayClientPluginMessage packet = new WrapperPlayClientPluginMessage(event);
            if (CHANNEL.equals(packet.getChannelName())) {
                player.clientBrandPayload(packet.getData());
                onBrand(packet.getData());
            }
        }
    }

    /** Judges a raw brand payload once per session. */
    void onBrand(byte[] payload) {
        if (judged) {
            return;
        }
        judged = true;
        String brand = decode(payload);
        if (brand == null) {
            if (checkInvalid) {
                violation("invalid brand payload of " + (payload != null ? payload.length : 0) + " bytes");
            }
            return;
        }
        String lower = brand.toLowerCase(Locale.ROOT);
        for (String entry : blocked) {
            if (lower.contains(entry)) {
                violation("blocked brand=" + brand);
                return;
            }
        }
    }

    /**
     * Decodes a brand payload (VarInt length + UTF-8 string).
     *
     * @return the brand, or {@code null} when the payload is empty, too long or malformed
     */
    public static String decode(byte[] payload) {
        if (payload == null || payload.length == 0 || payload.length > MAX_BRAND_BYTES) {
            return null;
        }
        int length = 0;
        int shift = 0;
        int index = 0;
        while (true) {
            if (index >= payload.length || shift > 28) {
                return null;
            }
            byte b = payload[index++];
            length |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                break;
            }
            shift += 7;
        }
        if (length <= 0 || index + length != payload.length) {
            return null;
        }
        return new String(payload, index, length, StandardCharsets.UTF_8);
    }
}
