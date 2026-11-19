# VA-Anticheat

Prediction-based anticheat for Paper, Purpur and Folia, focused on movement and combat.
Built on [PacketEvents](https://github.com/retrooper/packetevents).

## Features

- **Movement simulation** that replays vanilla physics per tick and flags what no legal input explains
- **42 checks** in five categories, each with its own buffer, VL decay and punishment profile
- **Anti health indicator**: hides health, absorption, equipment details and game modes of other
  entities from cheat clients
- **Xray obfuscation**, setbacks, violation logs (SQLite or MySQL/MariaDB) and a profiler
- Reload everything at runtime with `/ac reload`

## Checks

| Category | Checks |
|---|---|
| Movement | Speed, Fly, Prediction, NoFall, Step, HighJump, Spider, Jesus, Phase, NoSlow, Sprint, Velocity, Elytra, Vehicle |
| Combat | Reach, Hitbox, Killaura, Aim, SilentRotation, Triggerbot, Autoclicker, Criticals, Mace, ThroughWall, ESP |
| Packet | BadPackets, Timer, Blink, Post, Crasher |
| World | Scaffold, Nuker, FastBreak, FastPlace, FastUse, GhostHand, Freecam, Xray |
| Inventory | InventoryMove, ChestStealer, AutoArmor, AutoTotem |

Every check can be tuned or disabled under `checks.<id>` in `config.yml`.

## Requirements

- Paper, Purpur or Folia 1.21+ (including 26.x)
- [PacketEvents](https://modrinth.com/plugin/packetevents) 2.14+
- Java 21

## Installation

1. Download or build `VAPlus-<version>.jar`.
2. Put it and PacketEvents into the server's `plugins/` folder.
3. Start the server and edit `plugins/VAPlus/config.yml` and `messages.yml`.

The full walkthrough (permissions, database, updating, troubleshooting) is in the
[Installation Guide](docs/INSTALLATION.md).

## Documentation

| Page | Contents |
|---|---|
| [Installation Guide](docs/INSTALLATION.md) | Requirements, installing, first start, database, updating, troubleshooting |
| [Configuration](docs/CONFIGURATION.md) | Global settings, per-check settings, punishments, anti health indicator, storage |
| [Checks](docs/CHECKS.md) | All checks with id, category and what they detect |
| [Commands and Permissions](docs/COMMANDS.md) | `/ac` sub-commands and permission nodes |

## Building

Requires Java 21 and Maven 3.9+.

```sh
git clone https://github.com/Deepsz8281/VA-Anticheat.git
cd VA-Anticheat
mvn -B package
```

The jar is written to `target/`.

## Commands

| Command | Description |
|---|---|
| `/ac alerts` | Toggle alerts |
| `/ac info <player>` | Show a player's state and violations |
| `/ac logs <player> [page]` | Show the violation log |
| `/ac profile` | Show check timings |
| `/ac metrics` | Flags per check and engine state |
| `/ac debug <check\|all> [player]` | Debug output of checks |
| `/ac reload` | Reload `config.yml` and `messages.yml` |

Aliases: `/vaplus`, `/anticheat`.

## Permissions

| Permission | Default | Description |
|---|---|---|
| `ac.alerts` | op | Receive alerts, view player info and logs |
| `ac.admin` | op | Reload, profile, metrics and debug (includes `ac.alerts`) |
| `ac.bypass` | false | Exempt from all checks |
| `ac.healthindicator.bypass` | false | See real health and equipment of other entities |
