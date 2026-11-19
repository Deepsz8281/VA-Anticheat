package dev.vaplus.anticheat.platform;

import dev.vaplus.anticheat.config.GlobalSettings;
import dev.vaplus.anticheat.latency.ServerTickMarkers;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.pipeline.CoreTrackingModule;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.testutil.RecordingTransport;
import dev.vaplus.anticheat.testutil.TestContexts;
import dev.vaplus.anticheat.testutil.TestContexts.Harness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ServerTickMarkerService (Paper tick end / Folia region ticks)")
class ServerTickMarkerServiceTest {

    private static final GlobalSettings ON = GlobalSettings.defaults();
    private static final GlobalSettings OFF = new GlobalSettings(true, true, 0L, 50L, 15_000L, true, 250L, true, 200,
            500, false);

    private Harness harness;
    private ComponentKey<ServerTickMarkers> key;
    private PlayerData alex;
    private PlayerData bob;
    private RecordingTransport alexTransport;
    private RecordingTransport bobTransport;
    private final Map<UUID, PlayerData> players = new HashMap<>();
    private final AtomicReference<GlobalSettings> settings = new AtomicReference<>(ON);

    @BeforeEach
    void setUp() {
        harness = TestContexts.builder().module(new CoreTrackingModule(false, false)).build();
        key = ServerTickMarkers.keyOf(harness.context.setup());
        alexTransport = new RecordingTransport();
        alex = harness.newPlayer(UUID.randomUUID(), "Alex", alexTransport);
        bobTransport = new RecordingTransport();
        bob = harness.newPlayer(UUID.randomUUID(), "Bob", bobTransport);
        players.put(alex.uuid(), alex);
        players.put(bob.uuid(), bob);
    }

    private ServerTickMarkerService service(ComponentKey<ServerTickMarkers> componentKey, boolean folia) {
        return new ServerTickMarkerService(null, () -> List.copyOf(players.values()), players::get, componentKey,
                settings::get, folia);
    }

    @Test
    @DisplayName("Paper: every tick end sends one marker to every tracked player, mapped to the tick number")
    void paperFanOut() {
        ServerTickMarkerService service = service(key, false);
        service.onServerTickEnd(1000);
        service.onServerTickEnd(1001);
        assertEquals(2, alexTransport.markerIds().size());
        assertEquals(2, bobTransport.markerIds().size());

        alex.acks().onPong(alexTransport.markerIds().get(0));
        assertEquals(1000, alex.component(key).acknowledgedServerTick());
        assertEquals(ServerTickMarkers.NONE, bob.component(key).acknowledgedServerTick(), "players are independent");
    }

    @Test
    @DisplayName("the config toggle is read every tick (reload switches it without re-registering)")
    void toggle() {
        ServerTickMarkerService service = service(key, false);
        settings.set(OFF);
        service.onServerTickEnd(1);
        assertTrue(alexTransport.markerIds().isEmpty());
        settings.set(ON);
        service.onServerTickEnd(2);
        assertEquals(1, alexTransport.markerIds().size());
    }

    @Test
    @DisplayName("without the core-tracking module the service does nothing")
    void noComponent() {
        ServerTickMarkerService service = service(null, false);
        service.start();
        service.onServerTickEnd(5);
        assertTrue(alexTransport.markerIds().isEmpty());
        service.shutdown();
    }

    @Test
    @DisplayName("Folia: the per-player region ticker counts its own runs as the tick index")
    void foliaTicker() {
        ServerTickMarkerService service = service(key, true);
        service.start(); // Folia: no global listener is registered (no server access needed)
        ServerTickMarkerService.RegionTicker ticker = service.new RegionTicker(alex.uuid());
        ticker.accept(null);
        ticker.accept(null);
        assertEquals(2, ticker.ticks());
        assertEquals(2, alexTransport.markerIds().size());
        assertTrue(bobTransport.markerIds().isEmpty(), "a region ticker only serves its own player");
        alex.acks().onPong(alexTransport.lastMarkerId());
        assertEquals(2, alex.component(key).acknowledgedServerTick());

        settings.set(OFF);
        ticker.accept(null);
        assertEquals(3, ticker.ticks(), "the index keeps counting while disabled");
        assertEquals(2, alexTransport.markerIds().size());

        players.remove(alex.uuid());
        settings.set(ON);
        ticker.accept(null);
        assertEquals(2, alexTransport.markerIds().size(), "untracked players are skipped");
        assertEquals(0, service.attachedTasks());
        service.shutdown();
    }

    @Test
    @DisplayName("Folia detection: the regionised-server class is absent from the Paper compile API")
    void foliaDetection() {
        assertFalse(ServerTickMarkerService.detectFolia());
        assertTrue(service(key, true).folia());
    }
}
