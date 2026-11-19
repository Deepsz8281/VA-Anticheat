package dev.vaplus.anticheat.simulation;

/**
 * Vanilla player movement constants used by {@link PredictionSimulator}. Every value cites the
 * source it was read from (fetched 2026-11-13); anything without a fetched source is named
 * {@code UNCONFIRMED_*} and documented as such.
 *
 * <p>Source keys:
 * <ul>
 *   <li>[MCPK-H] https://www.mcpk.wiki/wiki/Horizontal_Movement_Formulas</li>
 *   <li>[MCPK-V] https://www.mcpk.wiki/wiki/Vertical_Movement_Formulas</li>
 *   <li>[WIKI-Entity] https://minecraft.wiki/w/Entity (motion table, players/living entities)</li>
 *   <li>[WIKI-Attribute] https://minecraft.wiki/w/Attribute</li>
 * </ul>
 */
public final class PhysicsConstants {

    /** Gravity default 0.08 per tick²; [WIKI-Entity] "-0.08 (double)", [MCPK-V] "Vy - 0.08". */
    public static final double GRAVITY = 0.08;
    /** Gravity under Slow Falling; [WIKI-Entity] "Players/mobs with Slow Falling" row: "-0.01 (double)". */
    public static final double SLOW_FALLING_GRAVITY = 0.01;
    /** Vertical drag; [WIKI-Entity] "0.98 (float)", [MCPK-V] "(Vy - 0.08) × 0.98". */
    public static final double VERTICAL_DRAG = 0.98f;
    /** Horizontal drag; [WIKI-Entity] "0.91 (float)", on ground "0.91×friction"; [MCPK-H] "S × 0.91". */
    public static final double HORIZONTAL_DRAG = 0.91f;
    /** Airborne slipperiness; [MCPK-H] "Airborne 1.0". */
    public static final double AIR_SLIPPERINESS = 1.0;
    /**
     * Momentum below which an axis is zeroed at the start of a tick; [MCPK-H]/[MCPK-V] "In 1.9+,
     * they are compared to 0.003 instead" (of 0.005).
     */
    public static final double NEGLIGIBLE_MOMENTUM = 0.003;
    /** Ground acceleration numerator; [MCPK-H] "0.1 × M × E × (0.6/S)³" with the 0.1 = movement_speed. */
    public static final double GROUND_ACCEL_NUMERATOR = 0.6 * 0.6 * 0.6;
    /** Air acceleration; [MCPK-H] "Air Velocity ... + 0.02 × M". */
    public static final double AIR_ACCELERATION = 0.02;
    /** Default input multiplier; [MCPK-H] movement multiplier "0.98 Default". */
    public static final double INPUT_FACTOR = 0.98;
    /**
     * Input multiplier while using an item (eating, blocking, drawing a bow). Source:
     * version-notes.md section 3.2, 1.21.11 {@code use_effects} component "speed_multiplier (default
     * 0.2)". UNCONFIRMED for versions before 1.21.11 and for the exact application point; only used
     * as an additional (slower) candidate, so it can never hide a speed increase.
     */
    public static final double ITEM_USE_INPUT_FACTOR = 0.2;
    /** Sprint multiplier of M; [MCPK-H] "Sprinting 1.3". */
    public static final double SPRINT_MULTIPLIER = 1.3;
    /** Sprint-jump boost along facing; [MCPK-H] "0.2 when sprinting". */
    public static final double SPRINT_JUMP_BOOST = 0.2;
    /** Speed effect per level; [MCPK-H] "E = (1 + 0.2 × Speed) × (1 − 0.15 × Slowness)". */
    public static final double SPEED_PER_LEVEL = 0.2;
    /** Slowness effect per level; [MCPK-H] (see {@link #SPEED_PER_LEVEL}), E must be ≥ 0. */
    public static final double SLOWNESS_PER_LEVEL = 0.15;
    /** Jump boost per level; [MCPK-V] "increased by 0.1 per level of Jump Boost". */
    public static final double JUMP_BOOST_PER_LEVEL = 0.1;
    /** Player hitbox half width (0.6 wide) and height 1.8 at scale 1. UNCONFIRMED: no fetched source. */
    public static final double UNCONFIRMED_HALF_WIDTH = 0.3;
    public static final double UNCONFIRMED_HEIGHT = 1.8;
    /**
     * Max jump apex added to the ceiling scan of multi-tick packets (0.42-based jump reaches about
     * 1.25 blocks; derived from [MCPK-V], not a separately sourced number).
     */
    public static final double JUMP_APEX_MARGIN = 1.3;

    /**
     * Attribute modifier ids the client applies itself and the server may echo in the attribute
     * packet. The movement engine models sprint and the Speed/Slowness effects separately, so it
     * strips/respects these ids. UNCONFIRMED: the ids are not in any fetched source.
     */
    public static final String UNCONFIRMED_SPRINT_MODIFIER_ID = "minecraft:sprinting";
    public static final String UNCONFIRMED_SPEED_EFFECT_MODIFIER_ID = "minecraft:effect.speed";
    public static final String UNCONFIRMED_SLOWNESS_EFFECT_MODIFIER_ID = "minecraft:effect.slowness";

    private PhysicsConstants() {
    }
}
