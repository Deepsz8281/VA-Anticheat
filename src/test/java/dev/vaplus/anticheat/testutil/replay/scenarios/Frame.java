package dev.vaplus.anticheat.testutil.replay.scenarios;

/**
 * One recorded position packet covering one client tick: the reported movement delta and the
 * client-declared on-ground flag.
 */
public record Frame(double dx, double dy, double dz, boolean onGround) {
}
