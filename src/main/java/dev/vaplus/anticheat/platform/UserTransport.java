package dev.vaplus.anticheat.platform;

import com.github.retrooper.packetevents.netty.channel.ChannelHelper;
import com.github.retrooper.packetevents.protocol.ConnectionState;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPing;
import dev.vaplus.anticheat.player.PacketTransport;

/** {@link PacketTransport} backed by a PacketEvents {@link User}. */
public final class UserTransport implements PacketTransport {

    private final User user;

    public UserTransport(User user) {
        this.user = user;
    }

    @Override
    public boolean sendMarker(int markerId) {
        // A play-state ping encoded while the client is in another protocol state would be
        // misinterpreted by the client and disconnect it.
        if (user.getEncoderState() != ConnectionState.PLAY) {
            return false;
        }
        user.sendPacket(new WrapperPlayServerPing(markerId));
        return true;
    }

    @Override
    public void send(PacketWrapper<?> packet) {
        user.sendPacket(packet);
    }

    @Override
    public void runOnOwnerThread(Runnable task) {
        Object channel = user.getChannel();
        if (channel != null && ChannelHelper.isOpen(channel)) {
            ChannelHelper.runInEventLoop(channel, task);
        }
    }

    @Override
    public void close() {
        user.closeConnection();
    }
}
