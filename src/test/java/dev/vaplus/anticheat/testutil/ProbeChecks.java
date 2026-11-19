package dev.vaplus.anticheat.testutil;

import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import dev.vaplus.anticheat.check.BlockCheck;
import dev.vaplus.anticheat.check.Check;
import dev.vaplus.anticheat.check.CheckDescriptor;
import dev.vaplus.anticheat.check.CheckFactory;
import dev.vaplus.anticheat.check.CheckSettings;
import dev.vaplus.anticheat.check.ClientTickCheck;
import dev.vaplus.anticheat.check.OutboundPacketCheck;
import dev.vaplus.anticheat.check.PacketCheck;
import dev.vaplus.anticheat.check.PositionCheck;
import dev.vaplus.anticheat.check.RotationCheck;
import dev.vaplus.anticheat.movement.MovementUpdate;
import dev.vaplus.anticheat.movement.LookChange;
import dev.vaplus.anticheat.player.PlayerData;
import dev.vaplus.anticheat.simulation.SimulationResult;
import dev.vaplus.anticheat.world.BlockInteraction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Test doubles for {@link Check}. {@link Probe} exposes the protected API of the base class; the
 * nested subclasses add one or more capability interfaces and record what they received.
 */
public final class ProbeChecks {

    private ProbeChecks() {
    }

    /** Immutable copy of a {@link MovementUpdate} (the real one is reused between packets). */
    public record PositionSnapshot(double fromX, double fromY, double fromZ,
                                   double toX, double toY, double toZ,
                                   double deltaX, double deltaY, double deltaZ,
                                   int elapsedTicks, boolean firstPosition, boolean teleport,
                                   boolean positionChanged, boolean rotationChanged,
                                   SimulationResult.Status status, String skipReason) {
        static PositionSnapshot of(MovementUpdate update) {
            return new PositionSnapshot(update.fromX(), update.fromY(), update.fromZ(),
                    update.toX(), update.toY(), update.toZ(),
                    update.deltaX(), update.deltaY(), update.deltaZ(),
                    update.elapsedTicks(), update.firstPosition(), update.teleport(),
                    update.positionChanged(), update.rotationChanged(),
                    update.simulation().status(), update.simulation().skipReason());
        }
    }

    public record RotationSnapshot(float fromYaw, float fromPitch, float toYaw, float toPitch, boolean teleport) {
        static RotationSnapshot of(LookChange update) {
            return new RotationSnapshot(update.fromYaw(), update.fromPitch(), update.toYaw(), update.toPitch(),
                    update.teleport());
        }
    }

    public static CheckFactory factory(CheckDescriptor descriptor, Constructor constructor) {
        return player -> constructor.create(player, descriptor);
    }

    @FunctionalInterface
    public interface Constructor {
        Check create(PlayerData player, CheckDescriptor descriptor);
    }

    /** Plain check without capabilities. */
    public static class Probe extends Check {
        public final List<CheckSettings> appliedSettings = new ArrayList<>();
        public int resets;

        public Probe(PlayerData player, CheckDescriptor descriptor) {
            super(player, descriptor);
        }

        @Override
        protected void onSettingsApplied(CheckSettings settings) {
            appliedSettings.add(settings);
        }

        @Override
        protected void onReset() {
            resets++;
        }

        public boolean raise(double amount) {
            return raiseBuffer(amount);
        }

        public void lower() {
            lowerBuffer();
        }

        public void lower(double amount) {
            lowerBuffer(amount);
        }

        public void clearBuffer() {
            resetBuffer();
        }

        public boolean flag(String details) {
            return violation(details);
        }

        public boolean flag(double weight, String details) {
            return violation(weight, details);
        }

        public boolean exempt() {
            return isExempt();
        }
    }

    public static final class Position extends Probe implements PositionCheck {
        public final List<PositionSnapshot> updates = new ArrayList<>();
        /** Optional extra behaviour per update, e.g. to flag. */
        public Consumer<MovementUpdate> onUpdate = update -> {
        };

        public Position(PlayerData player, CheckDescriptor descriptor) {
            super(player, descriptor);
        }

        @Override
        public void onPosition(MovementUpdate update) {
            updates.add(PositionSnapshot.of(update));
            onUpdate.accept(update);
        }
    }

    public static final class Rotation extends Probe implements RotationCheck {
        public final List<RotationSnapshot> updates = new ArrayList<>();

        public Rotation(PlayerData player, CheckDescriptor descriptor) {
            super(player, descriptor);
        }

        @Override
        public void onRotation(LookChange update) {
            updates.add(RotationSnapshot.of(update));
        }
    }

    public static final class Tick extends Probe implements ClientTickCheck {
        public int ticks;

        public Tick(PlayerData player, CheckDescriptor descriptor) {
            super(player, descriptor);
        }

        @Override
        public void onClientTick() {
            ticks++;
        }
    }

    /** Implements two capability interfaces at once. */
    public static final class PositionAndTick extends Probe implements PositionCheck, ClientTickCheck {
        public final List<PositionSnapshot> updates = new ArrayList<>();
        public int ticks;

        public PositionAndTick(PlayerData player, CheckDescriptor descriptor) {
            super(player, descriptor);
        }

        @Override
        public void onPosition(MovementUpdate update) {
            updates.add(PositionSnapshot.of(update));
        }

        @Override
        public void onClientTick() {
            ticks++;
        }
    }

    public static final class Packet extends Probe implements PacketCheck {
        public int received;

        public Packet(PlayerData player, CheckDescriptor descriptor) {
            super(player, descriptor);
        }

        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            received++;
        }
    }

    public static final class Outbound extends Probe implements OutboundPacketCheck {
        public int sent;

        public Outbound(PlayerData player, CheckDescriptor descriptor) {
            super(player, descriptor);
        }

        @Override
        public void onPacketSend(PacketSendEvent event) {
            sent++;
        }
    }

    public static final class Block extends Probe implements BlockCheck {
        public int interactions;

        public Block(PlayerData player, CheckDescriptor descriptor) {
            super(player, descriptor);
        }

        @Override
        public void onBlockInteraction(BlockInteraction interaction) {
            interactions++;
        }
    }

    /** Throws from every hook it implements. */
    public static final class Throwing extends Probe implements PositionCheck, RotationCheck, ClientTickCheck, PacketCheck {
        public int calls;

        public Throwing(PlayerData player, CheckDescriptor descriptor) {
            super(player, descriptor);
        }

        @Override
        public void onPosition(MovementUpdate update) {
            calls++;
            throw new IllegalStateException("boom-position");
        }

        @Override
        public void onRotation(LookChange update) {
            calls++;
            throw new IllegalStateException("boom-rotation");
        }

        @Override
        public void onClientTick() {
            calls++;
            throw new IllegalStateException("boom-tick");
        }

        @Override
        public void onPacketReceive(PacketReceiveEvent event) {
            calls++;
            throw new IllegalStateException("boom-receive");
        }
    }

    /** Throws while applying settings. */
    public static final class BrokenSettings extends Probe {
        public BrokenSettings(PlayerData player, CheckDescriptor descriptor) {
            super(player, descriptor);
        }

        @Override
        protected void onSettingsApplied(CheckSettings settings) {
            throw new IllegalArgumentException("bad settings");
        }
    }
}
