# Live smoke test (10 minutes, real server)

This is the procedure for validating a build on a real Paper server. It is **not** executed by the
unit tests and was **not** executed in this repository (no server is available here); the checklist
exists so the next run is reproducible and its output can be archived with the build.

## 0. Build and install

```sh
# Java 21 and Maven 3.9+
mvn -B package -DskipTests
cp target/VAPlus-0.1.0-SNAPSHOT.jar <server>/plugins/
```

`packetevents` must be installed (`depend: [packetevents]`). Start the server once and keep
`logs/latest.log`.

Expected at enable:

```
[...] [VAPlus] Server platform: Paper (git-Paper-... (MC: 1.21.x))
[...] [VAPlus] Enabled with 12 checks.
```

The exact check count depends on the build; it must match the number of configured check sections.

## 1. Join and baseline

1. Join with a vanilla 1.21.4 client. No alert, no kick, no console warning within 60 s.
2. `/ac info <you>` shows the player with 0 violations.
3. `/ac metrics` prints the engine line, `Players: 1, checks: N` and `No violations recorded yet.`
4. Fly/walk/jump for ~30 s (creative and survival), sneak, sprint-jump, swim, use an elytra once.
   `/ac metrics` must still report no violations.

## 2. Positive test (one check at a time)

Use a test client or a second account; enable `/ac debug <check> <player>` first.

| Check | Stimulus | Expected |
|---|---|---|
| `speed` | sprint on flat ground with Speed II, then a hacked client at 1.5x | no flag in the legit case, flags in the hacked case |
| `fly` | hover in survival | flags after the buffer, setback to the last safe position |
| `reach` | hit a player from >3.1 blocks | alert `entity=… distance=… limit=3.10` |
| `killaura` | attack a player who is behind you | 4 aligned attacks, then one alert |
| `autoclicker` | 20 CPS constant clicker | alert after 12 samples |
| `badpackets` | crafted pitch > 90 or cursor > 1.05 | one alert, no kick |
| `timer` | client timer 1.1x for 10 s; then `/tick rate 40` with a vanilla client | alerts within ~5 s at 1.1x; no alert during or after the tick-rate change |
| `triggerbot` | triggerbot (0-50 ms delay) against a strafing player for 1 min; then a legit player flick-hitting and spam-clicking for 5 min with `/ac debug` | alert within ~5 fast cold hits; legit player shows `slow acquisition` / `miss swing` lines and no alert |
| `killaura` (multi-target) | multi-aura hitting two players in one tick (1.21.2+ client) | alert on the second in-tick switch |
| `prediction` | hack that adds a small constant per tick (0.005) | alert `cumulative=…` after ~30 ticks |

For every alert: `/ac logs <player>` shows the record, `/ac metrics` increments exactly that check,
and the punishment profile configured for the category runs (or the setback, if the check allows it).

## 3. Shadow mode

Set `options.log-only: true` in one check section, `/ac reload`, repeat its stimulus:

* alert and log entry still appear, details prefixed with `[shadow]`
* no setback, no punishment
* `/ac metrics` counts the flags (this is the FP-tuning signal)

Then set `log-only: false` again and `/ac reload`.

## 4. Telemetry and watchdog

1. `/ac profile` lists the per-check cost; no check should average above ~50 µs/call on an idle server.
2. Force load (many players / a stress plugin). If the governor degrades, the console logs
   `Performance governor degraded (...)` and enforcement pauses; it logs
   `Performance governor recovered` afterwards. Alerts/logs must keep working while degraded.
3. `/ac metrics reset` clears the counters.

## 5. Replay recording (optional)

Start the server with `-Dvaplus.record=plugins/VAPlus/replay/session.txt`, move around for a minute,
stop the server. The file contains one `M,<ms>,<x>,<y>,<z>,<yaw>,<pitch>,<ground>` line per accepted
movement packet and is parsed by `ReplayFile` (tests: `ReplayRecorderTest`, `ReplayHarnessTest`).
Block changes are not part of the format, so replay it against flat, known terrain.

## 6. Log check

```bash
scripts/smoke-check.sh <server>/logs/latest.log --replay
```

Exit code 0 and `PASS` for platform, check count and check exceptions is the minimum bar. Record the
command output with the build artifact.
