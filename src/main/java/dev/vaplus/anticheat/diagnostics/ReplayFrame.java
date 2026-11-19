package dev.vaplus.anticheat.diagnostics;

/**
 * One recorded movement packet: absolute position + rotation after the packet was accepted. Frames
 * are deliberately the post-processing state (clamped, last-known rotation for rotation-only
 * packets), so a replay file reproduces exactly what the checks saw.
 *
 * @param nanos   milliseconds since the recording started (ordering only, not wall-clock)
 */
public record ReplayFrame(long nanos, double x, double y, double z, float yaw, float pitch,
                          boolean onGround) {
}
