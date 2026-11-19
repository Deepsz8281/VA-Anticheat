package dev.vaplus.anticheat.testutil.replay.scenarios;

import java.util.ArrayList;
import java.util.List;

import static dev.vaplus.anticheat.testutil.replay.VanillaMotion.carry;
import static dev.vaplus.anticheat.testutil.replay.VanillaMotion.fall;
import static dev.vaplus.anticheat.testutil.replay.VanillaMotion.groundAccel;

/**
 * Recorded-style movement scenarios for the movement-check regression tests. Every scenario is a
 * list of {@link Frame}s that starts after {@link CheckReplay#spawn} + {@link CheckReplay#settle}
 * (player at rest, facing +z, yaw 0).
 *
 * <p>Legitimate scenarios are produced by {@link Mover}, a straight-line integrator written from the
 * published formulas in {@code VanillaMotion} (MCPK / Minecraft Wiki), independently of the engine,
 * for a flat floor of slipperiness 0.6 (stone) with its top at {@link #FLOOR_TOP}. Cheat scenarios
 * are hand-written packet streams of typical hacks.
 */
public final class MovementScenarios {

    /** Top surface of the floor layer (block layer 63) used by every scenario. */
    public static final double FLOOR_TOP = 64.0;
    /** Jump strength default 0.42 as a float (Wiki Attribute; same literal as PredictionSimulatorTest). */
    public static final double JUMP = 0.41999998688697815;
    private static final double GRAVITY = 0.08;
    private static final double STONE = 0.6;
    private static final double MOVEMENT_SPEED = 0.1;
    /** MCPK-H: in 1.9+ momentum below 0.003 is zeroed at the start of a tick. */
    private static final double NEGLIGIBLE = 0.003;

    private MovementScenarios() {
    }

    // ------------------------------------------------------------------ legitimate

    /** Walking forward on stone from rest, claiming ground every tick. */
    public static List<Frame> walk(int ticks) {
        Mover mover = Mover.onGround(FLOOR_TOP);
        for (int i = 0; i < ticks; i++) {
            mover.tick(false, false);
        }
        return mover.frames;
    }

    /** Sprinting forward on stone from rest (no jumping). */
    public static List<Frame> sprint(int ticks) {
        Mover mover = Mover.onGround(FLOOR_TOP);
        for (int i = 0; i < ticks; i++) {
            mover.tick(true, false);
        }
        return mover.frames;
    }

    /** Sprint-jumping with jump held: several full jumps including landings and re-jumps. */
    public static List<Frame> sprintJumping(int ticks) {
        Mover mover = Mover.onGround(FLOOR_TOP);
        for (int i = 0; i < ticks; i++) {
            mover.tick(true, true);
        }
        return mover.frames;
    }

    /**
     * Free fall from {@code startY} (after an idle settle tick in the air) until landing on the floor,
     * then {@code standTicks} ticks standing still.
     */
    public static List<Frame> fallAndLand(double startY, int standTicks) {
        Mover mover = Mover.inAir(startY, FLOOR_TOP);
        while (!mover.ground) {
            mover.tick(false, false);
            if (mover.frames.size() > 200) {
                throw new IllegalStateException("fall did not land");
            }
        }
        for (int i = 0; i < standTicks; i++) {
            mover.tick(false, false);
        }
        return mover.frames;
    }

    // ------------------------------------------------------------------ cheats

    /** Speed hack: a constant 0.5 blocks per tick along +z on the ground. */
    public static List<Frame> speedHack(int ticks) {
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            frames.add(new Frame(0.0, 0.0, 0.5, true));
        }
        return frames;
    }

    /** Hover: staying at the same height in mid-air, honestly claiming to be airborne. */
    public static List<Frame> hover(int ticks) {
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            frames.add(new Frame(0.0, 0.0, 0.0, false));
        }
        return frames;
    }

    /** Fly up: constant ascent of 0.1 blocks per tick in mid-air. */
    public static List<Frame> ascend(int ticks) {
        List<Frame> frames = new ArrayList<>();
        for (int i = 0; i < ticks; i++) {
            frames.add(new Frame(0.0, 0.1, 0.0, false));
        }
        return frames;
    }

    /** NoFall: a physically correct fall from {@code startY} while claiming ground every tick. */
    public static List<Frame> fakeGroundFall(double startY) {
        List<Frame> legit = fallAndLand(startY, 0);
        List<Frame> frames = new ArrayList<>(legit.size());
        for (Frame frame : legit) {
            frames.add(new Frame(frame.dx(), frame.dy(), frame.dz(), true));
        }
        return frames;
    }

    /**
     * Straight-line (+z, yaw 0) vanilla integrator over a flat floor. Per tick: momentum cut-off,
     * jump (on ground: vy = 0.42, sprinting +0.2 forward), forward acceleration (ground
     * {@code groundAccel}, air 0.02 × 0.98 (× 1.3 sprinting)), floor clamp (landing sets the ground
     * flag), then {@code fall} and {@code carry} (slipperiness 0.6 when the tick started on ground,
     * 1.0 airborne).
     */
    static final class Mover {
        final List<Frame> frames = new ArrayList<>();
        private final double floorTop;
        private double y;
        private double vy;
        private double vz;
        boolean ground;

        private Mover(double y, double floorTop, boolean ground) {
            this.y = y;
            this.floorTop = floorTop;
            this.ground = ground;
            // Motion after the settle tick (at rest, ground or not): (0 - 0.08) * 0.98.
            this.vy = fall(0.0, GRAVITY);
        }

        static Mover onGround(double floorTop) {
            return new Mover(floorTop, floorTop, true);
        }

        static Mover inAir(double y, double floorTop) {
            return new Mover(y, floorTop, false);
        }

        void tick(boolean sprint, boolean jump) {
            boolean startGround = ground;
            if (Math.abs(vy) < NEGLIGIBLE) {
                vy = 0.0;
            }
            if (Math.abs(vz) < NEGLIGIBLE) {
                vz = 0.0;
            }
            if (jump && startGround) {
                vy = JUMP;
                if (sprint) {
                    vz += 0.2;
                }
            }
            double multiplier = 0.98 * (sprint ? 1.3 : 1.0);
            vz += startGround ? groundAccel(MOVEMENT_SPEED, multiplier, 1.0, STONE) : 0.02 * multiplier;
            double dy = vy;
            boolean landed = false;
            if (vy < 0.0 && y + dy < floorTop) {
                dy = floorTop - y;
                landed = true;
            }
            frames.add(new Frame(0.0, dy, vz, landed));
            y += dy;
            ground = landed;
            vy = fall(landed ? 0.0 : vy, GRAVITY);
            vz = carry(vz, startGround ? STONE : 1.0);
        }
    }
}
