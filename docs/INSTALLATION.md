# Installation Guide

This guide takes you from a fresh download to a running, tuned VA+ installation.

- [Requirements](#requirements)
- [1. Get the plugin](#1-get-the-plugin)
- [2. Install](#2-install)
- [3. First start](#3-first-start)
- [4. Give staff access](#4-give-staff-access)
- [5. Recommended first steps](#5-recommended-first-steps)
- [Database (optional)](#database-optional)
- [Updating](#updating)
- [Uninstalling](#uninstalling)
- [Troubleshooting](#troubleshooting)

## Requirements

| What | Version |
|---|---|
| Server | Paper, Purpur or Folia, Minecraft 1.21 or newer (including 26.x) |
| Java | 21 or newer |
| [PacketEvents](https://modrinth.com/plugin/packetevents) | 2.14.0 or newer (required dependency) |

Spigot and CraftBukkit are **not** supported. Clients older than 1.21.2 can join (for example via
ViaVersion), but a few checks need the 1.21.2+ tick-end and input packets and stay silent for them
(Sprint, SilentRotation movement, the Speed limit and parts of Nuker and Velocity).

## 1. Get the plugin

### Option A: build from source

You need Java 21 and Maven 3.9 or newer.

```sh
git clone https://github.com/Deepsz8281/VA-Anticheat.git
cd VA-Anticheat
mvn -B package
```

The plugin jar is `target/VAPlus-<version>.jar`. `mvn -B package` also runs the test suite; add
`-DskipTests` to build faster.

### Option B: download a release

If a release is published on the
[Releases page](https://github.com/Deepsz8281/VA-Anticheat/releases), download `VAPlus-<version>.jar`
from there.

## 2. Install

1. Stop the server.
2. Download PacketEvents (the Spigot/Paper build) and put it into `plugins/`.
3. Put `VAPlus-<version>.jar` into `plugins/`.
4. Start the server.

```
plugins/
├── packetevents-spigot-2.14.0.jar
└── VAPlus-0.1.0-SNAPSHOT.jar
```

Do not reload the server with `/reload` or plugin managers. Use a full restart, and use
`/ac reload` for configuration changes.

## 3. First start

On the first start VA+ creates its folder:

```
plugins/VAPlus/
├── config.yml        main configuration (checks, punishments, anti health indicator, storage)
├── messages.yml      all chat and kick messages (MiniMessage format)
└── violations.db     violation history (SQLite, created on the first violation)
```

The console should show lines like these:

```
[VAPlus] Server platform: Paper ...
[VAPlus] Enabled with 44 checks.
```

If you see `PacketEvents is not available; disabling VAPlus.`, PacketEvents is missing or failed to
load (see [Troubleshooting](#troubleshooting)).

## 4. Give staff access

| Permission | Default | Grants |
|---|---|---|
| `ac.alerts` | op | Alerts, `/ac alerts`, `/ac info`, `/ac logs` |
| `ac.admin` | op | Everything in `ac.alerts` plus `/ac reload`, `/ac profile`, `/ac metrics`, `/ac debug` |
| `ac.bypass` | false | The player is not checked at all |
| `ac.healthindicator.bypass` | false | The player receives real health and equipment data of others |

Example with LuckPerms:

```
/lp group moderator permission set ac.alerts true
/lp group admin permission set ac.admin true
```

Operators have `ac.alerts` and `ac.admin` by default. `ac.bypass` is never granted by default, not
even to operators. Give it only to accounts that must not be checked (for example test bots).

## 5. Recommended first steps

1. **Join and enable alerts.** Staff with `ac.alerts` receive alerts automatically
   (`alerts.enable-on-join`). `/ac alerts` toggles them.
2. **Run new or experimental checks in shadow mode first.** Add `log-only: true` to the
   check's `options`. The check then keeps alerting and logging but never sets back or punishes:

   ```yaml
   checks:
     nuker:
       options:
         log-only: true
   ```

   Watch `/ac metrics` for a few days and remove `log-only` once you trust the check.
3. **Review the punishment profiles.** By default players are kicked at a certain violation
   level. Replace `kick` with your ban plugin's command if you want bans:

   ```yaml
   punishment-profiles:
     combat:
       - vl: 50
         run: ["cmd: ban %player% Unfair advantage (%check%)"]
   ```

4. **Apply changes** with `/ac reload`. Storage and the in-memory log size need a restart.

See [Configuration](CONFIGURATION.md) for every option.

## Database (optional)

By default violations are stored in `plugins/VAPlus/violations.db` (SQLite). For a network or a
shared database, switch to MySQL or MariaDB:

```yaml
storage:
  type: mysql
  url: "jdbc:mysql://localhost:3306/vaplus"
  user: "vaplus"
  password: "change-me"
  retention-days: 30
```

The JDBC drivers are not bundled. Paper ships SQLite and MySQL drivers; if a driver is missing,
VA+ logs one warning and keeps violations in memory only. Storage changes need a restart.

## Updating

1. Stop the server.
2. Replace the old `VAPlus-*.jar` with the new one.
3. Start the server.

Your `config.yml` and `messages.yml` are kept. New options take their built-in defaults until you
add them, so compare your file with the
[bundled config.yml](../src/main/resources/config.yml) after an update. Missing keys in
`messages.yml` fall back to the built-in texts.

## Uninstalling

Stop the server, delete `VAPlus-*.jar` and, if you do not need the history anymore,
`plugins/VAPlus/`. PacketEvents can stay if other plugins use it.

## Troubleshooting

| Problem | Cause and fix |
|---|---|
| `PacketEvents is not available`, plugin disabled | Install PacketEvents 2.14.0+ (Spigot/Paper build) and restart. |
| Plugin does not load at all | Check the Java version (`java -version`, 21+) and that the server is Paper, Purpur or Folia 1.21+. |
| Players get disconnected after about 15 seconds | They did not answer latency markers. Usually a proxy or another plugin blocks ping packets. `latency.ack-timeout` controls the limit (5000-120000 ms). |
| `[config]` warnings on start or reload | A value in `config.yml` is invalid. The warning names the key; VA+ uses the default for it. |
| A legitimate player is flagged | Run `/ac debug <check> <player>` to see the check's reasoning, set the check to `log-only`, then raise its buffer `limit` or its tolerance options. Please report reproducible false positives as a GitHub issue. |
| Violations are not saved after a restart | Check `storage.type` and the console for a JDBC driver warning. |
| Health bars or tab health show wrong values | That is the anti health indicator. Disable single parts under `anti-health-indicator`, or give staff `ac.healthindicator.bypass`. |
