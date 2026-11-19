package dev.vaplus.anticheat.check.impl.movement;

import dev.vaplus.anticheat.player.PlayerData;

/** Test access to the package-private mount tracking of {@link VehicleCheck}. */
public final class VehicleCheckAccess {

    private static final int SELF = 7;

    private VehicleCheckAccess() {
    }

    /** Puts the player into a boat with the given entity id. */
    public static void ride(VehicleCheck check, PlayerData player, int boatId) {
        check.onJoin(SELF);
        check.remember(boatId, VehicleCheck.KIND_BOAT);
        check.onPassengers(boatId, new int[]{SELF});
        player.vehicle().mount(boatId);
    }
}
