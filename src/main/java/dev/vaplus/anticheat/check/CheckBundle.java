package dev.vaplus.anticheat.check;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.movement.LookChange;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.profiling.CheckProfiler;
import dev.vaplus.anticheat.world.BlockInteraction;

import java.util.ArrayList;
import java.util.List;

/**
 * The check instances of one player, pre-sorted into flat arrays per capability so dispatch is a
 * plain indexed loop (no iterator or lambda allocation on the hot path).
 *
 * <p>All dispatch methods must be called on the player's owner (netty) thread. Each call first
 * syncs settings with the registry epoch, skips everything for bypassing players, isolates check
 * exceptions and records timings in the {@link CheckProfiler}.
 */
public final class CheckBundle {

    private final PlayerData player;
    private final CheckRegistry registry;
    private final CheckProfiler profiler;
    private final CheckErrorReporter errors;

    private final Check[] all;
    private final List<Check> allView;
    private final Check[] packetBase;
    private final PacketCheck[] packetHooks;
    private final Check[] outboundBase;
    private final OutboundPacketCheck[] outboundHooks;
    private final Check[] positionBase;
    private final PositionCheck[] positionHooks;
    private final Check[] rotationBase;
    private final RotationCheck[] rotationHooks;
    private final Check[] blockBase;
    private final BlockCheck[] blockHooks;
    private final Check[] tickBase;
    private final ClientTickCheck[] tickHooks;
    private final Check[] attackBase;
    private final CombatCheck[] attackHooks;

    private int appliedEpoch = -1;

    private CheckBundle(PlayerData player, CheckRegistry registry, CheckProfiler profiler, CheckErrorReporter errors, Check[] all) {
        this.player = player;
        this.registry = registry;
        this.profiler = profiler;
        this.errors = errors;
        this.all = all;
        this.allView = List.of(all);

        List<Check> packet = new ArrayList<>();
        List<Check> outbound = new ArrayList<>();
        List<Check> position = new ArrayList<>();
        List<Check> rotation = new ArrayList<>();
        List<Check> block = new ArrayList<>();
        List<Check> tick = new ArrayList<>();
        List<Check> attack = new ArrayList<>();
        for (Check check : all) {
            if (check instanceof PacketCheck) {
                packet.add(check);
            }
            if (check instanceof OutboundPacketCheck) {
                outbound.add(check);
            }
            if (check instanceof PositionCheck) {
                position.add(check);
            }
            if (check instanceof RotationCheck) {
                rotation.add(check);
            }
            if (check instanceof BlockCheck) {
                block.add(check);
            }
            if (check instanceof ClientTickCheck) {
                tick.add(check);
            }
            if (check instanceof CombatCheck) {
                attack.add(check);
            }
        }
        this.packetBase = packet.toArray(new Check[0]);
        this.packetHooks = new PacketCheck[packetBase.length];
        for (int i = 0; i < packetBase.length; i++) {
            packetHooks[i] = (PacketCheck) packetBase[i];
        }
        this.outboundBase = outbound.toArray(new Check[0]);
        this.outboundHooks = new OutboundPacketCheck[outboundBase.length];
        for (int i = 0; i < outboundBase.length; i++) {
            outboundHooks[i] = (OutboundPacketCheck) outboundBase[i];
        }
        this.positionBase = position.toArray(new Check[0]);
        this.positionHooks = new PositionCheck[positionBase.length];
        for (int i = 0; i < positionBase.length; i++) {
            positionHooks[i] = (PositionCheck) positionBase[i];
        }
        this.rotationBase = rotation.toArray(new Check[0]);
        this.rotationHooks = new RotationCheck[rotationBase.length];
        for (int i = 0; i < rotationBase.length; i++) {
            rotationHooks[i] = (RotationCheck) rotationBase[i];
        }
        this.blockBase = block.toArray(new Check[0]);
        this.blockHooks = new BlockCheck[blockBase.length];
        for (int i = 0; i < blockBase.length; i++) {
            blockHooks[i] = (BlockCheck) blockBase[i];
        }
        this.tickBase = tick.toArray(new Check[0]);
        this.tickHooks = new ClientTickCheck[tickBase.length];
        for (int i = 0; i < tickBase.length; i++) {
            tickHooks[i] = (ClientTickCheck) tickBase[i];
        }
        this.attackBase = attack.toArray(new Check[0]);
        this.attackHooks = new CombatCheck[attackBase.length];
        for (int i = 0; i < attackBase.length; i++) {
            attackHooks[i] = (CombatCheck) attackBase[i];
        }
    }

    /** Instantiates every registered check for {@code player}. The registry must be frozen. */
    public static CheckBundle create(PlayerData player, CheckRegistry registry, CheckProfiler profiler, CheckErrorReporter errors) {
        List<CheckRegistry.Entry> entries = registry.entries();
        Check[] checks = new Check[entries.size()];
        for (CheckRegistry.Entry entry : entries) {
            Check check = entry.factory().create(player);
            if (check == null || check.descriptor() != entry.descriptor()) {
                throw new IllegalStateException("Factory of " + entry.descriptor().id()
                        + " must create a check bound to its own descriptor");
            }
            if (check.player() != player) {
                throw new IllegalStateException("Factory of " + entry.descriptor().id() + " bound the wrong player");
            }
            check.bind(entry.slot());
            checks[entry.slot()] = check;
        }
        CheckBundle bundle = new CheckBundle(player, registry, profiler, errors, checks);
        bundle.syncSettings();
        return bundle;
    }

    /** All checks of this player in slot order (immutable view). */
    public List<Check> all() {
        return allView;
    }

    public Check get(int slot) {
        return all[slot];
    }

    /** Linear lookup by type, for checks that cooperate with each other. */
    public <T extends Check> T get(Class<T> type) {
        for (Check check : all) {
            if (type.isInstance(check)) {
                return type.cast(check);
            }
        }
        return null;
    }

    /** Re-applies settings if a reload happened since the last call. Owner thread only. */
    public void syncSettings() {
        int current = registry.epoch();
        if (current == appliedEpoch) {
            return;
        }
        CheckSettings[] settings = registry.settingsArray();
        for (int i = 0; i < all.length; i++) {
            try {
                all[i].applySettings(settings[i]);
            } catch (RuntimeException | LinkageError error) {
                errors.report(all[i], error);
            }
        }
        appliedEpoch = current;
    }

    /** Clears buffers and per-world state of every check (respawn / world change). */
    public void resetAll() {
        for (Check check : all) {
            try {
                check.reset();
            } catch (RuntimeException | LinkageError error) {
                errors.report(check, error);
            }
        }
    }

    public void dispatchReceive(PacketReceiveEvent event) {
        if (!prepare()) {
            return;
        }
        boolean profiling = profiler.enabled();
        for (int i = 0; i < packetBase.length; i++) {
            Check base = packetBase[i];
            if (!base.enabled()) {
                continue;
            }
            long start = profiling ? System.nanoTime() : 0L;
            try {
                packetHooks[i].onPacketReceive(event);
            } catch (RuntimeException | LinkageError error) {
                errors.report(base, error);
            }
            if (profiling) {
                profiler.recordCheck(base.slot(), System.nanoTime() - start);
            }
        }
    }

    public void dispatchSend(PacketSendEvent event) {
        if (!prepare()) {
            return;
        }
        boolean profiling = profiler.enabled();
        for (int i = 0; i < outboundBase.length; i++) {
            Check base = outboundBase[i];
            if (!base.enabled()) {
                continue;
            }
            long start = profiling ? System.nanoTime() : 0L;
            try {
                outboundHooks[i].onPacketSend(event);
            } catch (RuntimeException | LinkageError error) {
                errors.report(base, error);
            }
            if (profiling) {
                profiler.recordCheck(base.slot(), System.nanoTime() - start);
            }
        }
    }

    public void dispatchPosition(MovementUpdate update) {
        if (!prepare()) {
            return;
        }
        boolean profiling = profiler.enabled();
        for (int i = 0; i < positionBase.length; i++) {
            Check base = positionBase[i];
            if (!base.enabled()) {
                continue;
            }
            long start = profiling ? System.nanoTime() : 0L;
            try {
                positionHooks[i].onPosition(update);
            } catch (RuntimeException | LinkageError error) {
                errors.report(base, error);
            }
            if (profiling) {
                profiler.recordCheck(base.slot(), System.nanoTime() - start);
            }
        }
    }

    public void dispatchRotation(LookChange update) {
        if (!prepare()) {
            return;
        }
        boolean profiling = profiler.enabled();
        for (int i = 0; i < rotationBase.length; i++) {
            Check base = rotationBase[i];
            if (!base.enabled()) {
                continue;
            }
            long start = profiling ? System.nanoTime() : 0L;
            try {
                rotationHooks[i].onRotation(update);
            } catch (RuntimeException | LinkageError error) {
                errors.report(base, error);
            }
            if (profiling) {
                profiler.recordCheck(base.slot(), System.nanoTime() - start);
            }
        }
    }

    public void dispatchInteraction(BlockInteraction interaction) {
        if (!prepare()) {
            return;
        }
        boolean profiling = profiler.enabled();
        for (int i = 0; i < blockBase.length; i++) {
            Check base = blockBase[i];
            if (!base.enabled()) {
                continue;
            }
            long start = profiling ? System.nanoTime() : 0L;
            try {
                blockHooks[i].onBlockInteraction(interaction);
            } catch (RuntimeException | LinkageError error) {
                errors.report(base, error);
            }
            if (profiling) {
                profiler.recordCheck(base.slot(), System.nanoTime() - start);
            }
        }
    }

    public void dispatchAttack(dev.vaplus.anticheat.check.impl.combat.EntityAttack attack) {
        if (!prepare()) {
            return;
        }
        boolean profiling = profiler.enabled();
        for (int i = 0; i < attackBase.length; i++) {
            Check base = attackBase[i];
            if (!base.enabled()) {
                continue;
            }
            long start = profiling ? System.nanoTime() : 0L;
            try {
                attackHooks[i].onAttack(attack);
            } catch (RuntimeException | LinkageError error) {
                errors.report(base, error);
            }
            if (profiling) {
                profiler.recordCheck(base.slot(), System.nanoTime() - start);
            }
        }
    }

    public void dispatchClientTick() {
        if (!prepare()) {
            return;
        }
        boolean profiling = profiler.enabled();
        for (int i = 0; i < tickBase.length; i++) {
            Check base = tickBase[i];
            if (!base.enabled()) {
                continue;
            }
            long start = profiling ? System.nanoTime() : 0L;
            try {
                tickHooks[i].onClientTick();
            } catch (RuntimeException | LinkageError error) {
                errors.report(base, error);
            }
            if (profiling) {
                profiler.recordCheck(base.slot(), System.nanoTime() - start);
            }
        }
    }

    private boolean prepare() {
        syncSettings();
        return !player.bypassing();
    }
}
