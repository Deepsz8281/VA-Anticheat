package dev.vaplus.anticheat.world.mirror;

import dev.vaplus.anticheat.world.mirror.BlockProperties.Uncertainty;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static dev.vaplus.anticheat.world.mirror.BlockProperties.Collision.EMPTY;
import static dev.vaplus.anticheat.world.mirror.BlockProperties.Collision.FULL;
import static dev.vaplus.anticheat.world.mirror.BlockProperties.Collision.FULL_FALLBACK;
import static dev.vaplus.anticheat.world.mirror.BlockProperties.Liquid.LAVA;
import static dev.vaplus.anticheat.world.mirror.BlockProperties.Liquid.NONE;
import static dev.vaplus.anticheat.world.mirror.BlockProperties.Liquid.WATER;
import static dev.vaplus.anticheat.world.mirror.BlockProperties.SpecialFlag.BED_BOUNCE;
import static dev.vaplus.anticheat.world.mirror.BlockProperties.SpecialFlag.BUBBLE_COLUMN_DOWN;
import static dev.vaplus.anticheat.world.mirror.BlockProperties.SpecialFlag.BUBBLE_COLUMN_UP;
import static dev.vaplus.anticheat.world.mirror.BlockProperties.SpecialFlag.HONEY_STICK;
import static dev.vaplus.anticheat.world.mirror.BlockProperties.SpecialFlag.SHELF_MUSHROOM_BOUNCE;
import static dev.vaplus.anticheat.world.mirror.BlockProperties.SpecialFlag.SLIME_BOUNCE;
import static dev.vaplus.anticheat.world.mirror.BlockProperties.SpecialFlag.SOUL_SAND_SLOW;
import static dev.vaplus.anticheat.world.mirror.BlockProperties.SpecialFlag.SULFUR_GEYSER;
import static dev.vaplus.anticheat.world.mirror.BlockProperties.Uncertainty.UNCONFIRMED_BLOCK_PROPERTIES;
import static dev.vaplus.anticheat.world.mirror.BlockProperties.Uncertainty.UNCONFIRMED_BLOCK_STATE;
import static dev.vaplus.anticheat.world.mirror.BlockProperties.Uncertainty.UNCONFIRMED_MISSING_BLOCK;
import static dev.vaplus.anticheat.world.mirror.BlockProperties.Uncertainty.UNCONFIRMED_SULFUR_GEYSER_IMPULSE;

/**
 * Small, version-independent metadata table. Not a complete block registry; unlisted keys retain
 * their identity but receive an uncertain FULL fallback. Numeric coefficients are block metadata,
 * not final entity motion. The engine owns version gating, state/neighbor rules and bounce maths.
 *
 * <p>Sources read for this table: {@code docs/research/version-notes.md} sections 3.2, 3.4 and 5;
 * <a href="https://www.mcpk.wiki/wiki/Slipperiness">Java slipperiness table</a>;
 * <a href="https://minecraft.wiki/w/Blue_Ice#Usage">ice slipperiness</a>;
 * <a href="https://minecraft.wiki/w/Soul_Sand#Usage">soul-sand slowdown and partial shape</a>;
 * <a href="https://minecraft.wiki/w/Honey_Block#Usage">honey slowdown and partial shape</a>.
 * Unverified exact factors and a contradictory brief value are deliberately named UNCONFIRMED.
 */
public final class VanillaBlocks {

    public static final BlockKey AIR = key("air");
    public static final BlockKey CAVE_AIR = key("cave_air");
    public static final BlockKey VOID_AIR = key("void_air");
    public static final BlockKey STONE = key("stone");
    public static final BlockKey DIRT = key("dirt");
    public static final BlockKey GRASS_BLOCK = key("grass_block");
    public static final BlockKey COBBLESTONE = key("cobblestone");
    public static final BlockKey BEDROCK = key("bedrock");
    public static final BlockKey GLASS = key("glass");
    public static final BlockKey SLIME_BLOCK = key("slime_block");
    public static final BlockKey SOUL_SAND = key("soul_sand");
    public static final BlockKey ICE = key("ice");
    public static final BlockKey FROSTED_ICE = key("frosted_ice");
    public static final BlockKey PACKED_ICE = key("packed_ice");
    public static final BlockKey BLUE_ICE = key("blue_ice");
    public static final BlockKey HONEY_BLOCK = key("honey_block");
    public static final BlockKey WATER_BLOCK = key("water");
    public static final BlockKey LAVA_BLOCK = key("lava");
    public static final BlockKey LADDER = key("ladder");
    public static final BlockKey VINE = key("vine");
    public static final BlockKey BUBBLE_COLUMN = key("bubble_column");
    public static final BlockKey MAGMA_BLOCK = key("magma_block");
    public static final BlockKey SHELF_MUSHROOM = key("shelf_mushroom");
    public static final BlockKey FARMLAND = key("farmland");
    public static final BlockKey DIRT_PATH = key("dirt_path");
    public static final BlockKey WHITE_CARPET = key("white_carpet");
    public static final BlockKey MOSS_CARPET = key("moss_carpet");

    /**
     * Farmland and dirt paths are 15/16 high (Wiki: Farmland/Dirt Path "hitbox height 0.9375"),
     * carpets 1/16. These are state-independent, so the height-only {@link BlockKey} model can carry
     * them; slabs/stairs/fences are state-dependent and therefore stay COMPLEX on purpose.
     */
    public static final double FARMLAND_HEIGHT = 0.9375;
    public static final double CARPET_HEIGHT = 0.0625;

    /** Java default slipperiness 0.6; MCPK Slipperiness, including air, soul sand and fluids. */
    public static final double DEFAULT_FRICTION = 0.6;
    /** Java slime slipperiness 0.8; MCPK Slipperiness table. */
    public static final double SLIME_FRICTION = 0.8;
    /** Ice 0.98; Minecraft Wiki Blue Ice/Usage and MCPK Slipperiness table. */
    public static final double ICE_FRICTION = 0.98;
    /** Blue ice 0.989; Minecraft Wiki Blue Ice/Usage (changed in 18w16a). */
    public static final double BLUE_ICE_FRICTION = 0.989;

    /**
     * Requested phase-2 placeholder, NOT a confirmed vanilla value: the fetched Blue Ice/Usage
     * page explicitly gives packed ice 0.98. Kept at the brief's 0.989 pending LEAD resolution;
     * the table also carries UNCONFIRMED_PACKED_ICE_FRICTION so the engine cannot mistake it for proof.
     */
    public static final double UNCONFIRMED_PACKED_ICE_FRICTION = 0.989;
    /** Brief's 0.4; Soul Sand/Usage confirms slowdown, not this exact speed-factor coefficient. */
    public static final double UNCONFIRMED_SOUL_SAND_SPEED_FACTOR = 0.4;
    /** Brief's ~0.4; Honey Block/Usage describes slowdown, not an exact internal coefficient. */
    public static final double UNCONFIRMED_HONEY_SPEED_FACTOR = 0.4;
    /** Candidate Java jump-velocity factor, not inferred from the Wiki's jump-height percentage. */
    public static final double UNCONFIRMED_HONEY_JUMP_FACTOR = 0.5;

    /** Before 26.2: 66% of impact velocity; version-notes.md section 3.2, Wiki Java Edition 26.2. */
    public static final double BED_BOUNCE_PRE_26_2 = 0.66;
    /** From 26.2: 75% of impact velocity; version-notes.md section 3.2, Wiki Java Edition 26.2. */
    public static final double BED_BOUNCE_26_2 = 0.75;

    public static final BlockProperties DEFAULT_PROPERTIES =
            properties(DEFAULT_FRICTION, 1.0, 1.0, NONE, false, FULL, Set.of(), Set.of());
    public static final BlockProperties UNKNOWN_PROPERTIES =
            properties(DEFAULT_FRICTION, 1.0, 1.0, NONE, false, FULL_FALLBACK, Set.of(),
                    Set.of(UNCONFIRMED_BLOCK_PROPERTIES));
    public static final BlockProperties MISSING_PROPERTIES =
            properties(DEFAULT_FRICTION, 1.0, 1.0, NONE, false, FULL_FALLBACK, Set.of(),
                    Set.of(UNCONFIRMED_MISSING_BLOCK));

    /** State-aware feeders choose this for drag=false; version-notes.md section 3.4. */
    public static final BlockProperties BUBBLE_COLUMN_UP_PROPERTIES =
            properties(DEFAULT_FRICTION, 1.0, 1.0, WATER, false, EMPTY,
                    Set.of(BUBBLE_COLUMN_UP), Set.of());
    /** State-aware feeders choose this for drag=true; version-notes.md section 3.4. */
    public static final BlockProperties BUBBLE_COLUMN_DOWN_PROPERTIES =
            properties(DEFAULT_FRICTION, 1.0, 1.0, WATER, false, EMPTY,
                    Set.of(BUBBLE_COLUMN_DOWN), Set.of());
    /**
     * Contextual magma-block override, only after the feeder has established a sulfur geyser setup.
     * Ordinary magma is not automatically a geyser. Section 3.2 confirms a 26.2 upward impulse but
     * not its magnitude; no guessed numeric impulse (including zero) is exposed.
     */
    public static final BlockProperties SULFUR_GEYSER_PROPERTIES =
            properties(DEFAULT_FRICTION, 1.0, 1.0, NONE, false, FULL,
                    Set.of(SULFUR_GEYSER), Set.of(UNCONFIRMED_SULFUR_GEYSER_IMPULSE));

    private static final Map<BlockKey, BlockProperties> TABLE = createTable();

    private VanillaBlocks() {
    }

    public static BlockProperties properties(BlockKey key) {
        return TABLE.getOrDefault(Objects.requireNonNull(key, "key"), UNKNOWN_PROPERTIES);
    }

    /** Immutable table in declaration order; its property sets are immutable too. */
    public static Map<BlockKey, BlockProperties> table() {
        return TABLE;
    }

    private static Map<BlockKey, BlockProperties> createTable() {
        Map<BlockKey, BlockProperties> table = new LinkedHashMap<>();
        BlockProperties air = properties(DEFAULT_FRICTION, 1.0, 1.0, NONE, false, EMPTY,
                Set.of(), Set.of());
        table.put(AIR, air);
        table.put(CAVE_AIR, air);
        table.put(VOID_AIR, air);
        for (BlockKey key : new BlockKey[]{STONE, DIRT, GRASS_BLOCK, COBBLESTONE, BEDROCK, GLASS, MAGMA_BLOCK}) {
            table.put(key, DEFAULT_PROPERTIES);
        }
        table.put(SLIME_BLOCK, properties(SLIME_FRICTION, 1.0, 1.0, NONE, false, FULL,
                Set.of(SLIME_BOUNCE), Set.of()));
        table.put(SOUL_SAND, properties(DEFAULT_FRICTION, UNCONFIRMED_SOUL_SAND_SPEED_FACTOR, 1.0,
                NONE, false, FULL_FALLBACK, Set.of(SOUL_SAND_SLOW),
                Set.of(Uncertainty.UNCONFIRMED_SOUL_SAND_SPEED_FACTOR)));
        BlockProperties ice = properties(ICE_FRICTION, 1.0, 1.0, NONE, false, FULL, Set.of(), Set.of());
        table.put(ICE, ice);
        table.put(FROSTED_ICE, ice);
        table.put(PACKED_ICE, properties(UNCONFIRMED_PACKED_ICE_FRICTION, 1.0, 1.0, NONE, false, FULL,
                Set.of(), Set.of(Uncertainty.UNCONFIRMED_PACKED_ICE_FRICTION)));
        table.put(BLUE_ICE, properties(BLUE_ICE_FRICTION, 1.0, 1.0, NONE, false, FULL, Set.of(), Set.of()));
        table.put(HONEY_BLOCK, properties(DEFAULT_FRICTION, UNCONFIRMED_HONEY_SPEED_FACTOR,
                UNCONFIRMED_HONEY_JUMP_FACTOR, NONE, false, FULL_FALLBACK, Set.of(HONEY_STICK),
                Set.of(Uncertainty.UNCONFIRMED_HONEY_SPEED_FACTOR, Uncertainty.UNCONFIRMED_HONEY_JUMP_FACTOR)));
        table.put(WATER_BLOCK, properties(DEFAULT_FRICTION, 1.0, 1.0, WATER, false, EMPTY, Set.of(), Set.of()));
        table.put(LAVA_BLOCK, properties(DEFAULT_FRICTION, 1.0, 1.0, LAVA, false, EMPTY, Set.of(), Set.of()));
        table.put(LADDER, properties(DEFAULT_FRICTION, 1.0, 1.0, NONE, true, FULL_FALLBACK, Set.of(), Set.of()));
        table.put(VINE, properties(DEFAULT_FRICTION, 1.0, 1.0, NONE, true, EMPTY, Set.of(), Set.of()));
        table.put(BUBBLE_COLUMN, properties(DEFAULT_FRICTION, 1.0, 1.0, WATER, false, EMPTY,
                Set.of(), Set.of(UNCONFIRMED_BLOCK_STATE)));
        BlockProperties bed = properties(DEFAULT_FRICTION, 1.0, 1.0, NONE, false, FULL_FALLBACK,
                Set.of(BED_BOUNCE), Set.of());
        for (String color : new String[]{"white", "orange", "magenta", "light_blue", "yellow", "lime",
                "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"}) {
            table.put(key(color + "_bed"), bed);
        }
        table.put(SHELF_MUSHROOM, properties(DEFAULT_FRICTION, 1.0, 1.0, NONE, false, FULL_FALLBACK,
                Set.of(SHELF_MUSHROOM_BOUNCE), Set.of()));
        BlockProperties farmland = properties(DEFAULT_FRICTION, 1.0, 1.0, NONE, false, FULL,
                Set.of(), Set.of(), FARMLAND_HEIGHT);
        table.put(FARMLAND, farmland);
        table.put(DIRT_PATH, farmland);
        BlockProperties carpet = properties(DEFAULT_FRICTION, 1.0, 1.0, NONE, false, FULL,
                Set.of(), Set.of(), CARPET_HEIGHT);
        table.put(MOSS_CARPET, carpet);
        for (String color : new String[]{"white", "orange", "magenta", "light_blue", "yellow", "lime",
                "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"}) {
            table.put(key(color + "_carpet"), carpet);
        }
        return Collections.unmodifiableMap(table);
    }

    private static BlockProperties properties(double friction, double speedFactor, double jumpFactor,
                                              BlockProperties.Liquid liquid, boolean climbable,
                                              BlockProperties.Collision collision,
                                              Set<BlockProperties.SpecialFlag> flags,
                                              Set<BlockProperties.Uncertainty> uncertainties) {
        return properties(friction, speedFactor, jumpFactor, liquid, climbable, collision, flags,
                uncertainties, 1.0);
    }

    private static BlockProperties properties(double friction, double speedFactor, double jumpFactor,
                                              BlockProperties.Liquid liquid, boolean climbable,
                                              BlockProperties.Collision collision,
                                              Set<BlockProperties.SpecialFlag> flags,
                                              Set<BlockProperties.Uncertainty> uncertainties,
                                              double collisionHeight) {
        return new BlockProperties(friction, speedFactor, jumpFactor, liquid, climbable, collision,
                flags, uncertainties, collisionHeight);
    }

    private static BlockKey key(String name) {
        return new BlockKey("minecraft:" + name);
    }
}
