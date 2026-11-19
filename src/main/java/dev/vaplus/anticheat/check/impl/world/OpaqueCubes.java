package dev.vaplus.anticheat.check.impl.world;

import dev.vaplus.anticheat.world.BlockSight;
import dev.vaplus.anticheat.world.mirror.BlockKey;
import dev.vaplus.anticheat.world.mirror.BlockProperties;
import dev.vaplus.anticheat.world.mirror.BlockSnapshot;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Strict "this block is a full, opaque cube" test for {@link GhostHandCheck} and {@link ScaffoldCheck}.
 *
 * <p>{@link BlockSight#obscures} is deliberately broad (every non-empty, non-see-through block),
 * which suits the ESP/X-Ray statistics but is too loud for a check that judges a single click: a
 * chest, slab, stair, fence or door has a partial shape a client can aim past. The mirror has no
 * voxel shapes (and most blocks carry the uncertain {@code FULL_FALLBACK} collision), so this class
 * uses an <b>allowlist</b> of block names whose outline is the full unit cube in every block state:
 * natural stone/soil, ores, wool, planks, logs, terracotta, concrete, bricks, storage blocks and the
 * full-cube utility blocks. Anything not on the list - including every modded/non-{@code minecraft}
 * block - never occludes. A wrong omission only makes the check quieter, never louder.
 *
 * <p>Additionally the block must be mirrored ({@code known}), pass {@link BlockSight#obscures} (no
 * liquid/waterlogging, non-empty collision, not see-through) and have full collision height.
 *
 * <p>The name classification is cached per key (bounded), so the per-ray path does not allocate.
 * Thread safe; shared by all players.
 */
public final class OpaqueCubes {

    /** Exact names (without namespace) whose shape is a full opaque cube. */
    private static final Set<String> EXACT = Set.of(
            "stone", "granite", "polished_granite", "diorite", "polished_diorite", "andesite",
            "polished_andesite", "deepslate", "cobbled_deepslate", "polished_deepslate", "chiseled_deepslate",
            "reinforced_deepslate", "calcite", "tuff", "polished_tuff", "chiseled_tuff",
            "dirt", "coarse_dirt", "rooted_dirt", "podzol", "mycelium", "grass_block", "mud", "packed_mud",
            "clay", "sand", "red_sand", "suspicious_sand", "gravel", "suspicious_gravel",
            "sandstone", "chiseled_sandstone", "cut_sandstone", "smooth_sandstone",
            "red_sandstone", "chiseled_red_sandstone", "cut_red_sandstone", "smooth_red_sandstone",
            "cobblestone", "mossy_cobblestone", "bedrock", "obsidian", "crying_obsidian", "netherrack",
            "soul_soil", "basalt", "polished_basalt", "smooth_basalt", "blackstone", "polished_blackstone",
            "chiseled_polished_blackstone", "gilded_blackstone", "end_stone", "purpur_pillar", "quartz_pillar",
            "smooth_quartz", "smooth_stone", "prismarine", "dark_prismarine", "sea_lantern", "glowstone",
            "shroomlight", "bookshelf", "chiseled_bookshelf", "crafting_table", "cartography_table",
            "fletching_table", "smithing_table", "loom", "furnace", "blast_furnace", "smoker", "dispenser",
            "dropper", "observer", "crafter", "note_block", "jukebox", "tnt", "pumpkin", "carved_pumpkin",
            "jack_o_lantern", "melon", "sponge", "wet_sponge", "ancient_debris", "lodestone",
            "respawn_anchor", "beehive", "bee_nest", "barrel", "target", "sculk", "terracotta", "bricks",
            "mushroom_stem", "crimson_stem", "warped_stem", "stripped_crimson_stem", "stripped_warped_stem",
            "muddy_mangrove_roots", "budding_amethyst", "creaking_heart");

    /** Suffixes whose every member is a full opaque cube. */
    private static final String[] SUFFIXES = {
            "_wool", "_planks", "_log", "_wood", "_hyphae", "_terracotta", "_concrete", "_concrete_powder",
            "_ore", "_bricks", "_tiles", "_nylium", "_block", "copper"
    };

    /** {@code _block} names that are see-through (rendered translucent). */
    private static final Set<String> BLOCK_EXCEPTIONS = Set.of("slime_block", "honey_block");

    private static final int CACHE_LIMIT = 8192;
    private static final ConcurrentHashMap<String, Boolean> CACHE = new ConcurrentHashMap<>();

    private OpaqueCubes() {
    }

    /** Whether this mirrored block certainly hides everything behind it from an interaction ray. */
    public static boolean occludes(BlockSnapshot block) {
        if (!block.known()) {
            return false;
        }
        BlockProperties properties = block.properties();
        if (properties.liquid() != BlockProperties.Liquid.NONE
                || properties.collision() == BlockProperties.Collision.EMPTY
                || properties.collisionHeight() < 1.0) {
            return false;
        }
        return fullCubeName(block.key());
    }

    /** Name part of the classification (cached; allocation-free on a cache hit). */
    public static boolean fullCubeName(BlockKey key) {
        String value = key.value();
        Boolean cached = CACHE.get(value);
        if (cached != null) {
            return cached;
        }
        boolean result = classify(key);
        if (CACHE.size() < CACHE_LIMIT) {
            CACHE.put(value, result);
        }
        return result;
    }

    private static boolean classify(BlockKey key) {
        String value = key.value();
        if (!value.startsWith("minecraft:") || BlockSight.seeThrough(key)) {
            return false;
        }
        String path = value.substring("minecraft:".length());
        if (path.startsWith("waxed_")) {
            path = path.substring("waxed_".length());
        }
        if (path.startsWith("infested_")) {
            path = path.substring("infested_".length());
        }
        if (EXACT.contains(path)) {
            return true;
        }
        if (BLOCK_EXCEPTIONS.contains(path)) {
            return false;
        }
        for (String suffix : SUFFIXES) {
            if (path.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }
}
