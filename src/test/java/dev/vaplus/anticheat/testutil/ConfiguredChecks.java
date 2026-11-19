package dev.vaplus.anticheat.testutil;

import dev.vaplus.anticheat.check.CheckDescriptor;

import java.util.List;

/**
 * Checks added in round 8 that have a {@code checks.<id>} section in the bundled config.yml. The
 * config tests add this list to their "every section belongs to a registered check" assertion.
 */
public final class ConfiguredChecks {

    public static final List<CheckDescriptor> ROUND_8 = List.of(
            dev.vaplus.anticheat.check.impl.movement.VelocityCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.world.ScaffoldCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.movement.PhaseCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.packet.CrasherCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.world.GhostHandCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.combat.CriticalsCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.world.FastBreakCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.movement.StepCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.movement.JesusCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.movement.VehicleCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.combat.MaceCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.movement.ElytraCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.inventory.InventoryMoveCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.inventory.AutoArmorCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.inventory.AutoTotemCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.world.FastUseCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.combat.AimCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.packet.PostCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.packet.BlinkCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.movement.SprintCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.movement.HighJumpCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.combat.SilentRotationCheck.DESCRIPTOR,
            dev.vaplus.anticheat.check.impl.world.NukerCheck.DESCRIPTOR);

    private ConfiguredChecks() {
    }
}
