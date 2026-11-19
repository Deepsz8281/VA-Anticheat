# Checks

VA+ has 44 checks in five categories. The **id** is the key under `checks:` in `config.yml` and the
name used by `/ac debug`.

Checks marked *experimental* are heuristic. Their alerts use the `alert-experimental` message, so
staff can tell them apart. Run them with `options.log-only: true` first (see
[Configuration](CONFIGURATION.md#per-check-settings)).

Some checks need data that only 1.21.2+ clients send (tick-end and player-input packets). They are
marked *1.21.2+*; older clients are simply not judged by them.

## Movement

Default profile `movement`. Movement checks can set the player back.

| Id | Name | Detects | Notes |
|---|---|---|---|
| `speed` | Speed | Horizontal movement faster than the simulation or the physical limit allows | Limit signal 1.21.2+ |
| `fly` | Fly | Vertical movement the movement simulation cannot explain (fly, glide, fast fall) | |
| `prediction` | Prediction | Sustained position error the engine cannot explain | experimental |
| `nofall` | NoFall | On-ground claims that contradict the simulated ground state | |
| `step` | Step | Rising more than step height or jump velocity in one tick | |
| `fastladder` | FastLadder | Climbing ladders, vines or scaffolding faster than vanilla | |
| `highjump` | HighJump | Jumping higher than the jump velocity allows (high jump, air jump) | |
| `spider` | Spider | Climbing walls without a climbable block | experimental |
| `jesus` | Jesus | Standing or walking on water or lava | experimental |
| `phase` | Phase | Moving into or through solid blocks | experimental |
| `noslow` | NoSlow | Full speed while using an item | experimental |
| `sprint` | Sprint | Sprinting without forward input (omni sprint) | 1.21.2+ |
| `velocity` | Velocity | Knockback that does not show up in the movement (anti-KB) | Distance signal partly 1.21.2+ |
| `elytra` | Elytra | Gliding that hovers or keeps climbing without a boost | experimental |
| `vehicle` | Vehicle | BoatFly, vehicle speed, mount high jump | experimental |

## Combat

Default profile `combat`. Combat checks only judge targets the server actually sent to the player.

| Id | Name | Detects | Notes |
|---|---|---|---|
| `reach` | Reach | Attacks from beyond vanilla reach | |
| `hitbox` | Hitbox | Hits whose target box the look ray does not touch (hitbox expansion such as Meteor's Hitboxes, also when most hits land normally) | experimental, 1.21.2+ |
| `killaura` | Killaura | Attacks outside the look direction, while using items, in GUIs or without swing | experimental |
| `aim` | Aim | Rotations off the mouse grid, locked on the hitbox centre or snapping back | experimental |
| `silentrotation` | SilentRotation | Sent rotations that differ from the camera | experimental, movement signal 1.21.2+ |
| `triggerbot` | Triggerbot | Attacks the instant the crosshair reaches a target | experimental |
| `autoclicker` | Autoclicker | Inhumanly constant click rates | experimental |
| `criticals` | Criticals | Attacks after a faked take-off (packet crits) | experimental |
| `mace` | Mace | Mace hits backed by a fake fall | experimental |
| `through-wall` | ThroughWall | Hits on players fully behind opaque blocks | experimental |
| `esp` | ESP | Keeps aiming at players hidden behind blocks | experimental |

## Packet

Default profile `packet`.

| Id | Name | Detects | Notes |
|---|---|---|---|
| `badpackets` | BadPackets | Impossible or duplicate packets: rotations, cursors, dig and prediction sequences, duplicate slot/sprint/sneak/input, missing position reminders, fake flight, spectate, respawn, keep-alive and entity-action abuse, position starvation (Grim A-Z equivalents, see config) | some signals 1.21.2+ |
| `timer` | Timer | Client ticks faster than real time | |
| `blink` | Blink | Movement packets held back and released in a burst | experimental |
| `post` | Post | Attacks and interactions sent after the movement packet of the same tick | experimental, 1.21.2+ |
| `crasher` | Crasher | Packets that can only lag or crash the server (they are dropped) | |

## World

Default profile `world`.

| Id | Name | Detects | Notes |
|---|---|---|---|
| `scaffold` | Scaffold | Placements against faces the eye cannot see, silent rotations while bridging | experimental |
| `blockreach` | BlockReach | Digging blocks beyond the interaction range | |
| `nuker` | Nuker | Several blocks per tick or blocks outside the crosshair | Multi signal 1.21.2+ |
| `fastbreak` | FastBreak | Breaking blocks faster than vanilla allows | |
| `fastplace` | FastPlace | Placing blocks faster than a vanilla client can click | |
| `fastuse` | FastUse | Item uses faster than a vanilla client can send them | 1.21.2+ |
| `ghosthand` | GhostHand | Block interactions through a wall of opaque blocks | experimental |
| `freecam` | Freecam | Block interactions that do not match the look direction | |
| `xray` | Xray | Mining ores that were completely hidden; can also hide them | experimental |

## Inventory

Default profile `inventory`.

| Id | Name | Detects | Notes |
|---|---|---|---|
| `inventorymove` | InventoryMove | Moving or turning while a container is open | experimental |
| `cheststealer` | ChestStealer | Emptying containers inhumanly fast or evenly | experimental |
| `autoarmor` | AutoArmor | Superhuman armor, totem and offhand handling | experimental |
| `autototem` | AutoTotem | Repeated rapid totem equips with consistent delays | experimental |

## Not a check: protections

| Feature | Config | What it does |
|---|---|---|
| Anti health indicator | `anti-health-indicator` | Hides health, absorption, equipment details and game modes of others |
| Anti xray | `checks.xray.options.obfuscate` | Replaces fully enclosed ores in chunk packets |
| Crasher | `checks.crasher` | Drops crash and lag packets before the server handles them |
