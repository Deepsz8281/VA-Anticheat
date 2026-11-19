package dev.vaplus.anticheat.player.state;

/** Vehicle the client is riding, as acknowledged through passenger packets. Owner thread only. */
public final class VehicleState {

    public static final int NO_VEHICLE = -1;

    private int vehicleEntityId = NO_VEHICLE;
    private int ticksSinceDismount = Integer.MAX_VALUE;

    public void mount(int entityId) {
        this.vehicleEntityId = entityId;
    }

    public void dismount() {
        if (vehicleEntityId != NO_VEHICLE) {
            vehicleEntityId = NO_VEHICLE;
            ticksSinceDismount = 0;
        }
    }

    public void tick() {
        if (vehicleEntityId == NO_VEHICLE && ticksSinceDismount < Integer.MAX_VALUE) {
            ticksSinceDismount++;
        }
    }

    public boolean inVehicle() {
        return vehicleEntityId != NO_VEHICLE;
    }

    public int vehicleEntityId() {
        return vehicleEntityId;
    }

    public int ticksSinceDismount() {
        return ticksSinceDismount;
    }
}
