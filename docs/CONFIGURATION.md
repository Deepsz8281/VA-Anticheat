# Configuration

VA+ is configured in `plugins/VAPlus/config.yml` and `plugins/VAPlus/messages.yml`. `/ac reload`
applies changes without a restart; only `logging` and `storage` need one. Invalid values never
break the plugin: they are reported as `[config]` warnings and replaced by the default.

The [bundled config.yml](../src/main/resources/config.yml) documents every key with comments. This
page explains how the parts fit together.

- [Global settings](#global-settings)
- [Categories](#categories)
- [Per-check settings](#per-check-settings)
- [How a flag becomes a punishment](#how-a-flag-becomes-a-punishment)
- [Punishment profiles](#punishment-profiles)
- [Anti health indicator](#anti-health-indicator)
- [Anti xray](#anti-xray)
- [Storage](#storage)
- [Messages](#messages)

## Global settings

| Section | Key | Default | Meaning |
|---|---|---|---|
| `alerts` | `enable-on-join` | `true` | Staff with `ac.alerts` get alerts automatically after joining |
| | `console` | `true` | Mirror alerts to the console |
| | `min-interval` | `0` | Minimum ms between two alerts of the same check for the same player |
| `latency` | `heartbeat-interval` | `50` | ms between latency markers (min. 10) |
| | `ack-timeout` | `15000` | Disconnect players that leave a marker unanswered this long (5000-120000) |
| | `server-tick-markers` | `true` | Extra marker per server tick for the movement engine |
| `setback` | `enabled` | `true` | Pull players back to their last legit position on movement flags |
| | `cooldown` | `250` | Minimum ms between two setbacks |
| | `confirmations` | `2` | Violations needed inside `window` before a setback (1-8) |
| | `window` | `4000` | ms window for `confirmations` |
| | `block-movement` | `true` | Drop position packets while a setback waits for its confirmation |
| `profiling` | `enabled` | `true` | Measure check cost for `/ac profile` |
| `logging` | `memory-per-player` | `200` | Violations kept in memory per player for `/ac logs` (restart) |
| | `memory-max-players` | `500` | Players kept in the memory log (restart) |

## Categories

Every check belongs to one category: `movement`, `combat`, `packet`, `world` or `inventory`.
A category switch disables all of its checks at once:

```yaml
categories:
  inventory:
    enabled: false
```

## Per-check settings

Every check can be configured under `checks.<id>`. All keys are optional; missing keys use the
check's built-in defaults. The check ids are listed in [Checks](CHECKS.md).

```yaml
checks:
  speed:
    enabled: true
    buffer:
      limit: 3.0        # the buffer must exceed this before a violation counts
      decay: 0.1        # removed from the buffer on legitimate behaviour
      max: 10.0         # buffer cap (0 = none)
    vl-decay-per-minute: 6.0
    alert-vl: 1.0       # alerts from this violation level on
    setback-vl: 1.0     # movement checks set back from this level on (-1 = never)
    profile: movement   # punishment profile, or an inline "actions:" list
    options:
      log-only: false   # shadow mode: alert and log, but never set back or punish
      threshold: 0.03   # check-specific tuning values
```

`options` differ per check. The bundled config lists them with their defaults and an explanation.

**Shadow mode.** `options.log-only: true` keeps a check alerting and logging but disables
setbacks and punishments. Use it for new or experimental checks and watch `/ac metrics`.

**Inline actions.** Instead of `profile`, a check can carry its own list in profile format:

```yaml
checks:
  reach:
    actions:
      - vl: 10
        run: [notify]
      - vl: 25
        run: ["cmd: tempban %player% 1d Reach"]
```

## How a flag becomes a punishment

1. A check sees suspicious behaviour and raises its **buffer**. Legitimate behaviour lowers it by
   `buffer.decay`.
2. Once the buffer exceeds `buffer.limit`, a **violation** is registered and the check's
   **violation level (VL)** goes up by 1. The VL decays by `vl-decay-per-minute`.
3. From `alert-vl` on, staff receive an **alert**.
4. Movement checks request a **setback** from `setback-vl` on (subject to `setback.confirmations`).
5. When the VL reaches an entry of the **punishment profile**, its actions run.

Raise `buffer.limit` to make a check more tolerant, lower it to make it stricter.

## Punishment profiles

Profiles are lists of thresholds. Each entry fires once when the VL reaches `vl`; with
`repeat` > 0 it fires again every `repeat` levels above that.

```yaml
punishment-profiles:
  combat:
    - vl: 20
      repeat: 10
      run: [notify]
    - vl: 50
      run: [kick]
  alerts-only: []
```

| Action | Effect |
|---|---|
| `kick` | Disconnect with `kick-message` from messages.yml |
| `kick: <message>` | Disconnect with a custom MiniMessage text |
| `cmd: <console command>` | Run a console command, e.g. your ban plugin |
| `notify` | Send `staff-notice` from messages.yml to staff with alerts on |
| `notify: <message>` | Send a custom MiniMessage line to staff |

Placeholders: `%player%` `%uuid%` `%check%` `%check_id%` `%category%` `%vl%` `%count%` `%ping%`
`%version%` `%details%`

Every category has a profile with its own name (`movement`, `combat`, `packet`, `world`,
`inventory`); checks use their category's profile unless they name another one. `alerts-only` never
punishes.

## Anti health indicator

Rewrites outgoing packets so cheat clients cannot read information vanilla does not show: health
bars and target HUDs, exact absorption, armor durability, enchantments, stack sizes and game modes of
other players. Your own data, the vehicle you ride, wolves, withers and the ender dragon are never
changed. Players with `ac.healthindicator.bypass` receive the real data.

```yaml
anti-health-indicator:
  enabled: true
  players-only: false      # true: only other players are hidden, mobs keep their health
  health:
    enabled: true
    spoof-value: 20        # value sent instead of the real health (1-1024)
    randomize: true        # random value between 1 and spoof-value
  absorption: true
  experience: true
  air-ticks: true
  items:
    enabled: true
    stack-amount: true
    durability: true
    enchantments: true     # the enchantment glint stays visible
  gamemode: true
  hearts-scoreboard: true  # hides tab-list health; turn off if your server shows health on purpose
  food-saturation: false
```

## Anti xray

Besides detecting xray, the `xray` check can **prevent** it: fully enclosed ores are replaced by a
fake block in the chunk packets, so an xray client never receives them.

```yaml
checks:
  xray:
    options:
      obfuscate: true
      fake-block: stone
      max-per-chunk: 512
```

## Storage

```yaml
storage:
  type: sqlite          # memory, sqlite or mysql (mariadb is an alias)
  file: violations.db   # sqlite only, relative to the plugin folder
  url: ""               # mysql: jdbc:mysql://host:3306/db
  user: ""
  password: ""
  retention-days: 30    # 0 keeps everything
  queue-size: 10000
  batch-size: 200
```

Storage changes need a restart. JDBC drivers are not bundled; without a driver VA+ logs a warning
and keeps violations in memory.

## Messages

`messages.yml` holds every text in [MiniMessage](https://docs.advntr.dev/minimessage/format.html)
format. `%prefix%` inserts the prefix; the other placeholders are listed per message. Missing keys
fall back to the built-in defaults, so you only need to keep the messages you change.
