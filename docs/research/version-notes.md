# Version Notes: Minecraft 1.21 to 26.3 (Paper / PacketEvents / movement anticheat)

Research date: 2026-11-12. Every bullet cites a source. Items marked **UNCONFIRMED** could not be verified from a fetched source. Many Minecraft Wiki changelog pages were truncated by the fetch tool; anything listed as absent is "not found in fetched text", not "confirmed absent".

Source keys used below:
- [PE-REL] https://github.com/retrooper/packetevents/releases
- [PE-tag-X] https://github.com/retrooper/packetevents/releases/tag/vX
- [PE-MR] Modrinth search snippet only (page not fetched): https://modrinth.com/plugin/packetevents/version/2.8.0 and https://modrinth.com/plugin/packetevents/version/2.7.0
- [PAPER-START] https://docs.papermc.io/paper/getting-started
- [PAPER-FOLIA] https://docs.papermc.io/paper/dev/folia-support/
- [PAPER-PYML] https://docs.papermc.io/paper/dev/plugin-yml/
- [PAPER-NEWS-26.1] https://papermc.io/news/26-1
- [PAPER-NEWS-26.3] https://papermc.io/news/26-3
- [WIKI-xxx] https://minecraft.wiki/w/<page> (page name given)
- [WIKI-PROTO] https://minecraft.wiki/w/Java_Edition_protocol/Packets

---

## 0. Summary of confirmed version timeline

| Minecraft | Date (source) | Notes |
|---|---|---|
| 1.21 | June 2024 (inferred, not fetched) | Maces, Wind Charge without experiment, Density/Breach/Wind Burst enchants [WIKI-Mace], [WIKI-Wind_Charge] |
| 1.21.2 | 2024 (UNCONFIRMED date) | Minecart Improvements experiment, `disablePlayerMovementCheck` gamerule, Glider component [WIKI-Java_Edition_1.21.2] |
| 1.21.6 | June 17, 2025 | Happy Ghast [WIKI-Java_Edition_1.21.6] |
| 1.21.11 | (date not fetched) | Spears, Lunge, attack_range component [WIKI-Java_Edition_1.21.11] |
| 26.1 | March 24, 2026 [WIKI-Java_Edition_26.1] | Requires Java 25 [PAPER-START] |
| 26.1.1 | April 1, 2026 [WIKI-Java_Edition_26.1.1 / minecraft.net article via search] | Hotfix, chat reporting only |
| 26.2 | June 16, 2026 [WIKI-Java_Edition_26.2] | "Chaos Cubed"; sulfur cube, air_drag_modifier, bounciness |
| 26.3 | September 15, 2026 [WIKI-Java_Edition_26.3] | Cushion entity, shelf mushroom, poplar boat, attack/interact animation components |

Paper: "26.1 ... upgrading a world to 26.1 is one-way" [PAPER-NEWS-26.1]. Paper's versioning changed to `<mcversion>.build.<build>-<status>` (e.g. `26.3.build.249-stable`), per [PAPER-NEWS-26.1] (article dated Apr 26, 2026). Paper 26.3 news dated Sep 26, 2026 [PAPER-NEWS-26.3].

---

## 1. Runtime requirements

### 1.1 Java version
- Paper requires Java 21 for "1.20 to 1.21.11" and Java 25 for "26.1+". Source: [PAPER-START] ("Paper requires at least Java 25 to run, which is easy to download and install."; version table).
- Minecraft 26.1 itself requires Java 25 per the Wiki's 26.1 summary ("a Java 25 requirement"). Source: [WIKI-Java_Edition_26.1] and a search snippet. Consistent with [PAPER-START].
- Consequence for the plugin: compile target Java 21 is enough for 1.21.x servers, but a 26.x server runs Java 25. Use `--release 21` for broad compatibility unless 26.x-only APIs are needed. (Recommendation, not sourced.)

### 1.2 Folia for 26.x
- Folia docs describe Folia as "a fork of Paper, which is currently maintained by the PaperMC team." The page names no Minecraft version. Source: [PAPER-FOLIA].
- **UNCONFIRMED:** whether a Folia build exists for 26.x. Searches returned no Folia 26.x release announcement. Paper's 26.1 and 26.3 news posts mention only Paper, and Folia appears only in the site navigation [PAPER-NEWS-26.1], [PAPER-NEWS-26.3].
- Action: check https://github.com/PaperMC/Folia/releases directly before relying on Folia 26.x. (Not fetched.)

### 1.3 `folia-supported` flag
- Still documented. Quote: "Plugins can't run on Folia if you don't have this field in your paper-plugin.yml (or plugin.yml, respectively): `folia-supported: true`". Source: [PAPER-FOLIA].
- Quote: "Marking a plugin as supported on Folia is _not nearly enough_ to support Folia." Source: [PAPER-FOLIA].
- Folia schedulers per docs: global, region, entity, async. Source: [PAPER-FOLIA].

### 1.4 paper-plugin.yml vs plugin.yml
- [PAPER-PYML] documents `plugin.yml` fields. It states: `api-version`: "Servers with a version lower than the version specified here will refuse to load the plugin." If unspecified: "the plugin will be loaded as a legacy plugin and a warning will be printed to the console." Source: [PAPER-PYML].
- [PAPER-PYML] does not say `paper-plugin.yml` is required or replaces `plugin.yml`. It names the `paper-plugin-loader` field. Source: [PAPER-PYML].
- [PAPER-FOLIA] names both files as places for `folia-supported`, without explaining the difference.
- **UNCONFIRMED:** whether `paper-plugin.yml` is mandatory for any 26.x feature. Breaking change noted in [PAPER-NEWS-26.1]: "we have fully dropped the internal remapper - obfuscated names no longer exist in the server jar." This affects only plugins that use NMS/reflection.
- API additions in 26.1 per [PAPER-NEWS-26.1]: `World#locateNearestPoi`, `WorldCreator#forcedSpawnPosition`, `EntityLungeEvent` (relevant to spear lunge, see section 4). `WorldInfo#getName` marked obsolete.

---

## 2. Protocol changes relevant to movement anticheat

### 2.1 Serverbound / clientbound IDs for protocol 777 (26.3)
Source: [WIKI-PROTO] (current-version table; the page itself warns hardcoding IDs is "highly discouraged").
- Player Input: ID 43 (0x2B), `player_input`
- Client Tick End: ID 13 (0xD), `client_tick_end`
- Set Player Position: ID 30 (0x1E), `move_player_pos`
- Set Player Position and Rotation: ID 31 (0x1F), `move_player_pos_rot`
- Set Player Rotation: ID 32 (0x20), `move_player_rot`
- Set Player Movement Flags: ID 33 (0x21), `move_player_status_only`
- Clientbound Synchronize Player Position: ID 73 (0x49), `player_position`
- The wiki's teleport-flag table defines relative bits for X, Y, Z, Yaw, Pitch, and Velocity X/Y/Z (0x0001 to 0x0080), plus rotate-velocity (0x0100). Source: [WIKI-PROTO].

Introduction version for each packet: **UNCONFIRMED** (the fetched wiki text did not state it).

### 2.2 Version-by-version packet history
- **1.21.2 horizontal collision flag in movement packet:** **UNCONFIRMED.** Search and Azalea commit evidence only showed a position-packet restructure (separate x/y/z fields to a `pos` structure) in a 1.21.2 client. Searches did not confirm a collision bit. Sources consulted: a search over wiki pages (no decisive source) and an Azalea commit mention (not fetched).
- **Player Input (`player_input`)**: version introduced **UNCONFIRMED.** The 1.21.2 changelog's "Input predicate" (data pack `input` field for forward/jump/sprint) is a data-pack feature, not the packet. Source: [WIKI-Java_Edition_1.21.2].
- **Client Tick End**: version introduced **UNCONFIRMED.**
- **Teleport velocity field (1.21.2)**: **UNCONFIRMED** from fetched sources. The 26.3 table shows velocity flags exist in the teleport flags, so the field exists in 26.3. [WIKI-PROTO]
- **Protocol history (wiki.vg)**: https://wiki.vg/Protocol_History returned a connection error. The mirror https://wikivg.booky.dev/Protocol_History was fetched, but only protocol 767 entries were visible in the truncated text. All version-specific packet changes for 768 to 777 are therefore **UNCONFIRMED.**

### 2.3 PacketEvents support per Minecraft version
Source: [PE-REL] (index page, dates lack year) and the tag pages below.

| PacketEvents | Date (page, year not shown) | MC support stated |
|---|---|---|
| 2.6.0 | 03 Nov | "1.21.2 and 1.21.3 Minecraft support." [PE-tag-2.6.0] |
| 2.7.0 | Dec 10, 2024 [PE-MR] | 1.21.4 support. Search snippet only. |
| 2.8.0 | May 15, 2025 [PE-MR] | 1.21.5 support. Search snippet only. |
| 2.9.0 | Jul 9, 2025 (tag page: "09 Jul 23:19") [PE-tag-2.9.0] | "Added 1.21.6 support", "Added 1.21.7 support" |
| 2.9.1 | hotfix | 1.21.6 and 1.21.7 [search snippet, Modrinth] |
| 2.9.5 | 25 Aug [PE-tag-2.9.5] | No MC version named in the release notes text fetched. Direction Y/Z axis swap fixed. |
| 2.10.0 | 19 Oct [PE-REL] | "Added 1.21.9 & 1.21.10 support" |
| 2.10.1 | 04 Nov [PE-REL] | Particle and bee fixes for 1.21.9 and 1.21.10 |
| 2.11.0 | 09 Dec [PE-REL] | "Added support for Minecraft 1.21.11" |
| 2.11.1 | 21 Dec [PE-REL] | Fix `minecraft:day` timeline on 1.21.11 |
| 2.11.2 | 27 Jan [PE-REL] | End skylight / infiniburn fixes |
| 2.12.0 | 05 Apr [PE-REL] | "Added support for Minecraft 26.1 (and 26.1.1)" |
| 2.12.1 | 19 Apr [PE-REL] | Mark 26.1.2 fully supported; WrapperPlayServerChunkBiomes |
| 2.12.2 | 01 Jun [PE-REL] | No MC version mention |
| 2.13.0 | 22 Jun [PE-REL] | "Add support for 26.2"; PluginMessage common wrapper |
| 2.14.0 | 23 Sep [PE-REL] | "Add support for 26.3" (PR #1582) |

- **1.21.8**: no dedicated line found. 2.9.x or 2.10 may cover it. **UNCONFIRMED** which release.
- No PacketEvents release note mentions player input, client tick end, or position packet changes (checked 2.6.0, 2.9.0, 2.9.5, 2.10 to 2.14 notes) [PE-REL], [PE-tag-*].
- Latest PacketEvents: 2.14.0 (matches the task brief).

---

## 3. Movement physics changes 1.21 to 26.3

### 3.1 Attributes
Source: [WIKI-Attribute] (version column absent from fetched text, so introduction versions are **UNCONFIRMED** unless noted in 3.1.b).

Attributes listed on the fetched page (default values from the wiki table): `air_drag_modifier` 1, `block_interaction_range` 4.5, `entity_interaction_range` 3, `burning_time` 1, `camera_distance` 4, `fall_damage_multiplier` 1, `friction_modifier` 1, `gravity` 0.08, `jump_strength` 0.42, `mining_efficiency` 0, `movement_efficiency` 0, `movement_speed` 0.7, `safe_fall_distance` 3, `scale` 1, `sneaking_speed` 0.3, `step_height` 0.6, `submerged_mining_speed` 0.2, `water_movement_efficiency` 0, `bounciness` 0, `tempt_range` 10, `waypoint_transmit_range` 0, `waypoint_receive_range` 0, `below_name_distance` 10, `flying_speed` 0.4.

- **attack_range**: not on the fetched attribute page. It is a data component, not an attribute: `minecraft:attack_range` with default 3.0 blocks and 5.0 in Creative. Source: [WIKI-Java_Edition_1.21.11].
- Version introduction:
  - `camera_distance`: Happy Ghast (1.21.6). [WIKI-Java_Edition_1.21.6]
  - `flying_speed`: ghasts gained the attribute in 1.21.6. [WIKI-Java_Edition_1.21.6]
  - `tempt_range`: listed in the 1.21.2 changelog (Technical > Additions). [WIKI-Java_Edition_1.21.2]
  - `air_drag_modifier`, `friction_modifier`, `bounciness`: 26.2. [WIKI-Java_Edition_26.2]
  - `knockback_resistance` minimum changed to -2.0 in 26.2. [WIKI-Java_Edition_26.2]
  - All others (`movement_efficiency`, `water_movement_efficiency`, `step_height`, `jump_strength`, `gravity`, `safe_fall_distance`, `fall_damage_multiplier`, `scale`, `sneaking_speed`, `block_interaction_range`, `entity_interaction_range`, `mining_efficiency`, `submerged_mining_speed`): introduction version **UNCONFIRMED.**

### 3.2 Friction, gravity, elytra, swimming, sprinting, minecarts

- **Ground friction / air drag / bounciness (26.2):** `friction_modifier` scales ground friction relative to the block. `air_drag_modifier` default 1.0, range 0 to 2048. `bounciness` default 0, max 1.0, applied on block/entity collision. Source: [WIKI-Java_Edition_26.2].
- **Beds (26.2):** bounce falling entities at 75% of impact velocity instead of 66%. Source: [WIKI-Java_Edition_26.2]. Anticheat relevance: fall/bounce velocity changes.
- **`#suppresses_bounce` tag (26.2)**: blocks suppress bounciness. Source: [WIKI-Java_Edition_26.2].
- **Potent sulfur geysers (26.2)**: upward impulse on entities above a magma block; 50 s interval; 4 to 5 s eruption. Lava underneath = continuous eruption. Source: [WIKI-Java_Edition_26.2]. High relevance for vertical movement checks (unknown impulse).
- **Sulfur cube (26.2)**: hops like a slime; archetypes set bounciness, friction, air drag, buoyancy, and `knockback_modifiers`; players can push it. Source: [WIKI-Java_Edition_26.2].
- **Elytra (1.21.11)**: can glide through cave vines and similar blocks, via `#can_glide_through`. Source: [WIKI-Java_Edition_1.21.11]. Glider component (1.21.2): "If present, this item allows players to glide (as with elytra) when equipped." Source: [WIKI-Java_Edition_1.21.2].
- **Elytra and smash (mace)**: in Java the elytra must be unequipped for a smash attack. Source: [WIKI-Mace].
- **Slime blocks (1.21.2)**: "Now cancel fall damage when sneaking." Source: [WIKI-Java_Edition_1.21.2].
- **Minecarts (1.21.2, experimental)**: "Can now jump better." and "Minecarts no longer phase through blocks on slopes when going up or down, they collide instead." Only with the Minecart Improvements experiment. Gamerule `minecartMaxSpeed` (default 8, max 1000 blocks/s). Source: [WIKI-Java_Edition_1.21.2].
- **Minecarts (26.x)**: no changes found in fetched text. **UNCONFIRMED** whether the experimental physics were promoted. Third-party mod pages claim "new minecart physics" but are not official sources and are ignored here.
- **Nether portal cooldown for minecarts/boats (1.21.2)**: reduced from 15 s to 0.5 s. Source: [WIKI-Java_Edition_1.21.2]. Relevant to teleport-like movement exemptions.
- **Water-riding (1.21.11)**: horses, mules, donkeys, zombie horses, camels no longer sink in water while ridden. Source: [WIKI-Java_Edition_1.21.11].
- **Breath of the Nautilus (1.21.11)**: pauses oxygen use for mounted players. Source: [WIKI-Java_Edition_1.21.11].
- **Lava (1.21.11)**: `minecraft:gameplay/fast_lava` makes lava spread faster and push entities harder. Dimensions using it: **UNCONFIRMED.** Source: [WIKI-Java_Edition_1.21.11].
- **Sprinting**: `use_effects` component (1.21.11) sets `speed_multiplier` (default 0.2) and `can_sprint` (default false) while using an item. Source: [WIKI-Java_Edition_1.21.11]. Movement speed while using items must be modeled.
- **Swimming / sprint rules**: no changes found in fetched 26.x text. **UNCONFIRMED.**
- **Gravity constant change**: not found; the `gravity` attribute default is 0.08. [WIKI-Attribute]. Whether the vanilla default changed: **UNCONFIRMED.**

### 3.3 Happy Ghast (1.21.6)
Source: [WIKI-Java_Edition_1.21.6]
- Rideable by up to four players with a harness. Harness only attachable to happy ghasts.
- First rider controls: forward flies in look direction; back flies opposite; left/right strafe; jump flies straight up.
- Standing on it: "stops moving and aligns to a cardinal direction."
- Cannot enter boats, can enter minecarts.
- Leash: free roaming distance 10 blocks, snapping at 16 blocks.
- Anticheat relevance: flying-mount movement must be whitelisted or modeled separately.

### 3.4 Bubble columns (26.1)
- Magma block makes downward columns, soul sand makes upward columns, and a fluid tag defines which fluids can hold a column (water by default). Source: [WIKI-Java_Edition_26.1].

### 3.5 Baby mob collision (26.1)
- Baby mob models changed; bounding boxes tweaked to match. Source: [WIKI-Java_Edition_26.1].
- Zombie-type baby heights 0.99 to 0.98 blocks (26.2). Source: [WIKI-Java_Edition_26.2].

### 3.6 Explosions (26.1)
- Explosion impact position and impulse reset grace-time fields added to all mobs and armor stands. `ignore_fall_damage_from_current_explosion` (player-only) removed. Source: [WIKI-Java_Edition_26.1]. Relevant to knockback-exemption windows.

---

## 4. Combat mechanics

### 4.1 Mace (smash attack)
Source: [WIKI-Mace] (fetched page; long page truncated).
- Added in 1.21 (experimental snapshot 24w11a under "Update 1.21"; general availability in 24w18a).
- Smash attack triggers after the player "has fallen 1.5 blocks or more," regardless of attack cooldown. Fall height = starting Y minus ending Y.
- Fall-distance bonus tiers: 4 HP per block for the first 3 blocks; 2 HP per block for the next 5; 1 HP per block after that.
- Smash attacks are usually critical: base damage and smash damage each +50%.
- Accumulated damage "theoretically unlimited", but the cited Mojira report is "Invalid". **UNCONFIRMED** as a real cap, so do not hard-code a cap.
- Knockback: all entities within 2.5 blocks knocked back, "similar to the effect of a wind charge." Page marks details "[more information needed]". **UNCONFIRMED.** The attacker is not knocked back. Smash attacks do not work under Slow Falling.
- Wind Burst enchantment (max III): 8 blocks height gain per level; does not negate fall damage; chainable. Source: [WIKI-Mace].
- Breach (max IV): reduces armor by 15% per level. Source: [WIKI-Mace].
- Density (max V): the page's two descriptions conflict ("0.5HP x 0.25 per block" vs "0.5 per level"). **UNCONFIRMED.** Density and Breach are mutually exclusive. Source: [WIKI-Mace].
- Mace mob smash: mob mace damage scales with fall distance (24w37a; Bedrock 1.21.50). Source: [WIKI-Mace].
- 1.21.2: new death message "was smashed by" for mace smash kills; "Over-Overkill" advancement requires a smash attack; `mace_smash` damage tag added. Source: [WIKI-Java_Edition_1.21.2].

### 4.2 Wind Charge
Source: [WIKI-Wind_Charge].
- Player knockback: player-fired charge deals "22% more knockback than regular explosions, but have a much smaller burst area." Blast Protection reduces it.
- Aiming straight down: "knock the player upward ~6 blocks instead of ~2.5" (Java 1.21-pre1 and later).
- Crouching: "Crouching no longer affects wind charge knockback." (Java 1.21-pre1.)
- Fall damage: wind charge projectiles "always negate the player's fall damage" (Java 1.21-pre1). Burst accumulates fall damage only when falling below the hit Y.
- Cooldown: 10 ticks (0.5 s). The page states the `use_cooldown` component is default from 1.21.2 (24w35a). Source: [WIKI-Wind_Charge].
- Direct hits: 1 HP damage plus knockback. Player/dispenser burst range: 2.4-block sphere. Breeze burst: 6-block sphere, 40% less knockback. Source: [WIKI-Wind_Charge].
- Bedrock 1.21.20.21: player-fired charges "no longer have a chance to deal more than 1HP" (Bedrock only).
- Java 1.21.2 (24w34a): no longer pass through the world border when hit. Source: [WIKI-Wind_Charge].
- Launch achievement: "Use a Wind Charge to launch yourself upward 8 blocks." Source: [WIKI-Wind_Charge].
- Velocity numbers for player launch in blocks/tick: **UNCONFIRMED.**
- Wind charge changes in 26.x: none found in fetched text. The page's 26.x section is absent. **UNCONFIRMED.**

### 4.3 Spear (1.21.11), Lunge, attack range
Source: [WIKI-Java_Edition_1.21.11].
- Spear: craftable in wood, stone, copper, iron, gold, diamond, netherite. Cannot mine blocks. Has a minimum reach (too close = no damage). Two attacks: quick jab, and charge when secondary button is held. Wooden spears are furnace fuel.
- Lunge enchantment (spear only): works with jab; pushes player horizontally in look direction. Costs 1, 2, or 3 hunger points by level, needs at least 6 hunger points, costs 1 durability per use.
- `minecraft:attack_range`: default max reach 3.0 blocks (5.0 in Creative); adds min reach, hitbox margin, mob factor.
- `minecraft:kinetic_weapon`: charge damage based on relative speed; conditions for knockback and dismounting.
- `minecraft:piercing_weapon`: quick attack hitting multiple entities along a ray.
- `minecraft:minimum_attack_charge`, `minecraft:swing_animation`, `minecraft:damage_type` added.
- Paper API: `EntityLungeEvent` added in 26.1 per [PAPER-NEWS-26.1]. Lunge velocity values: **UNCONFIRMED.**
- 26.1 changelog: "dismount speed thresholds have been updated to match Bedrock" for spears [WIKI-Java_Edition_26.1]. Numeric thresholds: **UNCONFIRMED.**
- 26.1: `post_piercing_attack` enchantment no longer needs a food level for players [WIKI-Java_Edition_26.1].
- 26.2: nothing on spear/lunge seen in visible text (remaining text truncated). **UNCONFIRMED** beyond that.
- 26.3: `minecraft:attack_animation` (`whack` or `stab`, duration in ticks) and `minecraft:interact_animation` data components. Source: [WIKI-Java_Edition_26.3]. Animation only; no movement or reach effect stated.
- 26.3: `minecraft:mob_visibility` component modifies mob detection (0.0 = 2 blocks, 10.0 = tenfold). Source: [WIKI-Java_Edition_26.3].
- Attack range attribute: the attribute page does not list `attack_range`. Only the data component exists. Changes to the attack cooldown: **UNCONFIRMED.**
- 1.21.11 Mob Kabob advancement: hit five mobs in one Charge attack. Source: [WIKI-Java_Edition_1.21.11].
- Zombie-type spear holders in 1.21.11 (zombie villager not in changelog). Source: [WIKI-Java_Edition_1.21.11].

### 4.4 Breeze
- 1.21.2: breeze no longer has minimum distance to attack a target (mobs only). Source: [WIKI-Java_Edition_1.21.2].

---

## 5. Other things an anticheat must handle in 26.1 to 26.3

- **Sulfur cube (26.2)**: `minecraft:type_specific/cube_mob` predicate includes sulfur cubes; `#cannot_be_pushed_onto_boats` and `#non_controlling_rider` include them. Source: [WIKI-Java_Edition_26.2].
- **Sulfur spikes (26.2)**: stalactites fall and damage when unsupported. Source: [WIKI-Java_Edition_26.2].
- **Hot/explosive sulfur cube**: contact damage and TNT detonation. Source: [WIKI-Java_Edition_26.2].
- **Cushion entity (26.3)**: players sit on it; no collision; breaks when support removed. Source: [WIKI-Java_Edition_26.3]. Mounting/seat movement must be whitelisted.
- **Shelf mushroom (26.3)**: bouncy like a bed when landed on. Source: [WIKI-Java_Edition_26.3]. Vertical bounce velocity exemption needed.
- **Poplar boat / chest boat (26.3)**: new variants, no handling change described. Source: [WIKI-Java_Edition_26.3].
- **Straw bed (26.3)**: single-use bed that does not reset spawn; not in Nether/End. Source: [WIKI-Java_Edition_26.3]. Non-movement.
- **Copper chains and copper bars**: from snapshot 25w32a (search snippet, not an official 26.x changelog). Copper bars behave like iron bars. **UNCONFIRMED** whether they are in a 26.x release and whether collision matches iron bars. Source: search snippet only (no fetched official source).
- **Copper engine minecart / copper minecarts**: Mojang feedback posts only. **UNCONFIRMED** as shipped features.
- **Entity tags (26.2)**: `#not_affected_by_geysers`, `#fox_immune_to`, `#polar_bear_immune_to` affect dismounting. Source: [WIKI-Java_Edition_26.2].
- **Ticks since last hurt (26.2)**: `ticks_since_last_hurt_by_mob` replaces `HurtByTimestamp`. Source: [WIKI-Java_Edition_26.2]. Relevant to hurt-invulnerability windows.
- **Invulnerable time tag (26.3)**: `invulnerable_time` tag makes mobs temporarily invulnerable. Source: [WIKI-Java_Edition_26.3].
- **Game rules**: `disablePlayerMovementCheck` (1.21.2, default false) controls the player movement speed restriction. Source: [WIKI-Java_Edition_1.21.2]. Important: a server may disable the vanilla speed check, so an anticheat cannot rely on it. No new movement gamerule found in 26.2 visible text. [WIKI-Java_Edition_26.2]
- **Ender pearls (1.21.2)**: unload on logout, reappear on login; 3x3 chunk loading. Source: [WIKI-Java_Edition_1.21.2]. Teleport-related.
- **World border (1.21.2)**: arrows and tridents bounce; other projectiles do not pass through. Source: [WIKI-Java_Edition_1.21.2].
- **Dismount speed threshold for spears (26.1)**: matches Bedrock. Numeric value UNCONFIRMED.
- **Bubble columns (26.1)**: see 3.4.
- **Golden dandelion**: no movement effect. [WIKI-Java_Edition_26.1].
- **26.1.1 (April 1, 2026)**: hotfix for chat reporting only. [WIKI-Java_Edition_26.1.1]. No movement changes.

---

## 6. Open items (UNCONFIRMED list)

1. 1.21.2 to 26.3 packet-level changes (horizontal collision bit, teleport velocity field version, player_input and client_tick_end introduction versions). Fetch https://wikivg.booky.dev/Protocol_History fully (page truncated) or the decompiled `ServerboundMovePlayerPacket` for the matching version.
2. Attribute introduction versions for `movement_efficiency`, `water_movement_efficiency`, `step_height`, `jump_strength`, `gravity`, `safe_fall_distance`, `fall_damage_multiplier`, `scale`, `sneaking_speed`, `block_interaction_range`, `entity_interaction_range`, `mining_efficiency`, `submerged_mining_speed`.
3. Folia 26.x existence.
4. PacketEvents release covering 1.21.2 (2.6.0 claims it), 1.21.4 (2.7.0 per Modrinth snippet), 1.21.5 (2.8.0 per Modrinth snippet), 1.21.8 (not found).
5. Mace knockback details, Density scaling, Wind charge player velocity values.
6. Lunge velocity, spear dismount thresholds (numeric), attack cooldown changes.
7. 26.x changes to minecarts (experimental physics promotion), swimming, sprinting, elytra.
8. Copper chains/bars in a shipped 26.x release.
9. Whether `paper-plugin.yml` is required in 26.x (docs are silent).
10. Remaining truncated text of 26.1, 26.2, 26.3 Wiki pages (Combat, Mace, Spear sections of 26.2/26.3 not fetched).

Fetch failures: https://wiki.vg/Protocol_History (ECONNREFUSED). https://docs.papermc.io/paper/... pages fetched fine. https://papermc.io/news/26-3 fetched (dated Sep 26, 2026).
