package dev.vaplus.anticheat.simulation;

import dev.vaplus.anticheat.module.AnticheatModule;
import dev.vaplus.anticheat.module.ComponentKey;
import dev.vaplus.anticheat.module.ModuleContext;
import dev.vaplus.anticheat.pipeline.AttributeTracker;
import dev.vaplus.anticheat.pipeline.EffectTracker;
import dev.vaplus.anticheat.pipeline.VelocityTracker;
import dev.vaplus.anticheat.pipeline.WorldBlockStates;
import dev.vaplus.anticheat.pipeline.WorldTracker;
import dev.vaplus.anticheat.player.state.VelocityState;
import dev.vaplus.anticheat.world.mirror.BlockStateResolver;
import dev.vaplus.anticheat.world.mirror.WorldMirror;

import java.util.Objects;
import java.util.Set;

/**
 * Phase-2 simulation module: registers the per-player {@link VelocityState}, {@link WorldMirror}
 * and {@link PredictionState} components, the outbound {@link VelocityTracker} and installs the
 * {@link PredictionSimulator} (replacing {@link InactiveSimulator}).
 *
 * <p>Install after {@code CoreTrackingModule} and {@code MotionTrackingModule}: the engine looks up
 * their teleport-queue and motion-history components in the module setup.
 *
 * <p>Round 3 (appended after the round-2 registrations, order matters): the world feeder
 * {@link WorldTracker} fills {@link #worldMirrorKey()} from chunk/block packets (state ids resolved
 * by a shared {@link BlockStateResolver}, by default {@link WorldBlockStates#forServer()}), and the
 * {@link AttributeTracker} / {@link EffectTracker} feed {@code AttributeState} / {@code EffectState}
 * for the player's own entity. They run after the {@link VelocityTracker}, which records the own
 * entity id from JOIN_GAME first.
 */
public final class SimulationModule implements AnticheatModule {

    private final BlockStateResolver blockStates;
    private ComponentKey<VelocityState> velocityKey;
    private ComponentKey<WorldMirror> worldKey;
    private ComponentKey<PredictionState> predictionKey;
    private ComponentKey<AttributeTracker.SentAttributes> sentAttributesKey;
    private ComponentKey<EffectTracker.SentEffects> sentEffectsKey;
    private PredictionSimulator simulator;
    private WorldTracker worldTracker;

    /** Live module: block states resolved with the running server's PacketEvents mappings. */
    public SimulationModule() {
        this(WorldBlockStates.forServer());
    }

    /** Module with a custom block-state resolver (tests, replays). */
    public SimulationModule(BlockStateResolver blockStates) {
        this.blockStates = Objects.requireNonNull(blockStates, "blockStates");
    }

    @Override
    public String name() {
        return "simulation";
    }

    @Override
    public void register(ModuleContext context) {
        velocityKey = context.registerComponent("velocity-state", VelocityState.class, player -> new VelocityState());
        worldKey = context.registerComponent("world-mirror", WorldMirror.class, player -> new WorldMirror(blockStates));
        predictionKey = context.registerComponent("prediction-state", PredictionState.class,
                player -> new PredictionState());
        context.addOutboundTracker(new VelocityTracker(velocityKey));
        // Mount/dismount of the own entity (needs the self id the velocity tracker reads from JOIN_GAME).
        context.addOutboundTracker(new dev.vaplus.anticheat.pipeline.PassengerTracker(velocityKey));
        // Own gliding/swimming flags from entity metadata (the engine skips both poses).
        dev.vaplus.anticheat.pipeline.PoseFlagsTracker poseFlags = new dev.vaplus.anticheat.pipeline.PoseFlagsTracker(velocityKey);
        context.addOutboundTracker(poseFlags);
        context.addTickListener(poseFlags);
        simulator = new PredictionSimulator(predictionKey, velocityKey, worldKey);
        context.installSimulator(simulator);

        // Round 3: world feeder + attribute/effect trackers (after the velocity tracker).
        sentAttributesKey = context.registerComponent("attribute-sync", AttributeTracker.SentAttributes.class,
                player -> new AttributeTracker.SentAttributes());
        sentEffectsKey = context.registerComponent("effect-sync", EffectTracker.SentEffects.class,
                player -> new EffectTracker.SentEffects());
        worldTracker = new WorldTracker(worldKey);
        context.addOutboundTracker(worldTracker);
        context.addOutboundTracker(new AttributeTracker(velocityKey, sentAttributesKey,
                Set.of(PhysicsConstants.UNCONFIRMED_SPRINT_MODIFIER_ID)));
        context.addOutboundTracker(new EffectTracker(velocityKey, sentEffectsKey));
    }

    public ComponentKey<VelocityState> velocityKey() {
        return require(velocityKey);
    }

    /** Per-player compensated world the engine reads; filled by {@link #worldTracker()}. */
    public ComponentKey<WorldMirror> worldMirrorKey() {
        return require(worldKey);
    }

    public ComponentKey<PredictionState> predictionKey() {
        return require(predictionKey);
    }

    public PredictionSimulator simulator() {
        return require(simulator);
    }

    /** The world feeder (primitive entry points are used by replays). */
    public WorldTracker worldTracker() {
        return require(worldTracker);
    }

    public ComponentKey<AttributeTracker.SentAttributes> sentAttributesKey() {
        return require(sentAttributesKey);
    }

    public ComponentKey<EffectTracker.SentEffects> sentEffectsKey() {
        return require(sentEffectsKey);
    }

    private static <T> T require(T value) {
        if (value == null) {
            throw new IllegalStateException("SimulationModule is not registered yet");
        }
        return value;
    }
}
