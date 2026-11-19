package dev.vaplus.anticheat.testutil;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;
import io.github.retrooper.packetevents.impl.netty.NettyManagerImpl;

/**
 * Installs a minimal PacketEvents API for unit tests that need to construct a
 * {@code PacketWrapper} (its constructor reads the server version from the global API). Only
 * {@code getServerManager()} and in-memory Netty buffers are functional; other capabilities throw.
 *
 * <p>PacketEvents 2.14 loads item base components through its buffer API even when tests only
 * construct stacks. No real connections are opened. Restore with {@link #uninstall()} afterward.
 */
public final class FakePacketEvents {

    private static PacketEventsAPI<?> previous;
    private static boolean installed;

    private FakePacketEvents() {
    }

    public static synchronized void install(ServerVersion version) {
        if (installed) {
            return;
        }
        previous = PacketEvents.getAPI();
        if (previous == null) {
            PacketEvents.setAPI(new FakeApi(version));
            installed = true;
        }
    }

    public static synchronized void uninstall() {
        if (installed) {
            PacketEvents.setAPI(previous);
            previous = null;
            installed = false;
        }
    }

    private static final class FakeApi extends PacketEventsAPI<Object> {

        private final ServerManager serverManager;
        private final NettyManager nettyManager = new NettyManagerImpl();

        private FakeApi(ServerVersion version) {
            this.serverManager = () -> version;
        }

        @Override
        public boolean isLoaded() {
            return true;
        }

        @Override
        public void init() {
        }

        @Override
        public boolean isInitialized() {
            return true;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public Object getPlugin() {
            return null;
        }

        @Override
        public ServerManager getServerManager() {
            return serverManager;
        }

        @Override
        public ProtocolManager getProtocolManager() {
            throw new UnsupportedOperationException("fake PacketEvents");
        }

        @Override
        public PlayerManager getPlayerManager() {
            throw new UnsupportedOperationException("fake PacketEvents");
        }

        @Override
        public NettyManager getNettyManager() {
            return nettyManager;
        }

        @Override
        public ChannelInjector getInjector() {
            throw new UnsupportedOperationException("fake PacketEvents");
        }
    }
}
