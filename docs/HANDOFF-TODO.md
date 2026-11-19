# Handoff TODO (Phase 2, Runde 3 abgeschlossen — Runde 4 offen) — for the next AI

Project: VA+ / VAPlus anticheat (repository root). Read `docs/ARCHITECTURE.md` and
`docs/PHASE2-BRIEF.md` first, then `docs/research/version-notes.md`. Phases are run strictly one at a time.

Model note (user rule, replaces the old "one agent at a time / Sonnet-Opus" rules): the user now allows
**exactly 1 GLM 5.3 Flash + 2 GPT 6.1 Sol agents working in parallel** (strict mode). On GLM
usage-limit errors the GLM agent falls back to **GPT 6.1 Sol** (unchanged fallback).

## Strict-mode rules (binding for every parallel agent)

- Only touch the files assigned to you (see the round board below); never foreign or shared files.
- No git operations, no deletes, no renames, no drive-by refactorings.
- No new dependencies; `mvn` runs **without `clean`** (parallel agents hold the tree).
- Every claim must be backed by a real tool run; UNCONFIRMED items from version-notes stay marked
  UNCONFIRMED and are never stated as facts.
- Foreign build/test errors: report them, do not fix them.

## Build (Java 21 and Maven 3.9+ required)

```sh
git clone https://github.com/Deepsz8281/VA-Anticheat.git
cd VA-Anticheat
mvn -B clean verify
```

Artifacts: jar at `target/VAPlus-0.1.0-SNAPSHOT.jar` (Java 21 bytecode, contains `config.yml`,
`messages.yml`, `plugin.yml`; `folia-supported: true`, `depend: [packetevents]`).

## Current state (2026-11-13): PHASE 2, ROUND 3 DONE — `mvn -B clean verify`: 695 tests green

Round 3 ran with 2 parallel agents (strict mode). LEAD added `new MovementChecksModule()` as 4th entry
in `VAPlusPlugin.modules()` (after `SimulationModule`) and ran `mvn -B clean verify`: **695 tests, 0 failures**.

- **Agent A — world + trackers** (DONE, +55 tests):
  - `pipeline/WorldTracker` (+ `WorldColumn`, `WorldBlockStates`): CHUNK_DATA / UNLOAD_CHUNK / BLOCK_CHANGE /
    MULTI_BLOCK_CHANGE → `WorldMirror`; JOIN/RESPAWN clear. Unknown global ids stay UNKNOWN (PE's
    `getByGlobalId` defaults to AIR — resolver checks `getGlobalId() == id`).
  - `world/mirror/{ColumnStorage,ArrayColumnStorage,BlockStateResolver,PendingChanges}`; `WorldMirror`
    dense columns, `getBlock(int,int,int)`, `MAX_CHUNKS = 4400`. Windows: block/chunk/world, closed when the
    next marker after the packet is acked (overflow > 256 → whole world pending).
  - `pipeline/AttributeTracker` (`SentAttributes`), `pipeline/EffectTracker` (`SentEffects`), marker-bracketed;
    additive pending API on `AttributeState`/`EffectState`; `MotionHistory.newest()`.
  - Engine skips `world-pending`, `attributes-pending`, `effects-pending`; `SupportScan` allocation-free.
  - End-to-end `simulation/WorldFeedReplayTest` (chunk fill + ack → COMPLETE).
- **Agent B — checks** (DONE, +36 tests):
  - `check/impl/movement/{SpeedCheck,FlyCheck,NoFallCheck,MovementOptions,MovementChecksModule}`, ids
    `speed`/`fly`/`nofall`, MOVEMENT (setback via packet-level path), buffered, silent unless COMPLETE.
  - `config.yml` `checks.speed/fly/nofall` with `options{}` (all tuning values); no parser change needed.
  - Unit tests + replay scenarios (`testutil/replay/scenarios/**`, `*ReplayTest`): legit walk/sprint-jump/
    knockback/fall → no flag; speed hack, hover, ascend, fake ground → flag after buffer.

## Round 4a (2026-11-13): anti-xray, anti-esp, anti-freecam — `mvn -B test`: 734 tests green

One module, `check/impl/world/VisibilityChecksModule`, appended as the 5th entry in
`VAPlusPlugin.modules()` (after `SimulationModule`, whose world-mirror key it resolves; `modules()` now
builds the `SimulationModule` as a local so it can be shared). +39 tests (695 → 734).

- `world/BlockSight` (NEW): exact voxel DDA line of sight with a three-valued answer
  (`VISIBLE`/`OCCLUDED`/`UNKNOWN`), slab ray/box entry distance, and the single "does this block hide
  what is behind it" recipe (empty collision, fluids and see-through full cubes never hide; missing
  mirror data is `UNKNOWN`, never air or wall).
- `check/impl/world/XrayCheck` (`xray`, WORLD, experimental): `DIG_START` on a valuable ore whose six
  neighbours are all mirrored **and** opaque; buffer/VL, fresh window after a flag, duplicate positions
  ignored. Default ore list is configurable (`valuable-blocks`), `only-hidden: false` counts exposed
  ores too.
- `check/impl/world/FreecamCheck` (`freecam`, WORLD): placement hit point against the sent look
  direction (angle + reach), dig start against the look ray/box (reach); eye height 1.62/1.27; skips
  gliding, swimming, vehicles, `insideBlock` placements and creative/spectator.
- `check/impl/world/{NearbyPlayers,NearbyPlayersTracker}` + `EspCheck` (`esp`, COMBAT, experimental):
  player-only position tracker from outbound entity packets (spawn/spawn-player/rel-move/teleport/
  position-sync/destroy, cleared on join/respawn); an occluded target must stay under the crosshair for
  `min-ticks` while moving `min-target-movement` blocks. In front of the viewer is required before the
  miss distance is evaluated (a target behind would otherwise have a small perpendicular distance).
- `config.yml`: `checks.xray/freecam/esp`; `SettingsParserTest` and `MovementCheckConfigTest` now list
the configured checks, new `VisibilityCheckConfigTest` keeps the three sections in sync with code defaults.
- Tests: `BlockSightTest`, `XrayCheckTest`, `FreecamCheckTest`, `EspCheckTest`, `VisibilityCheckConfigTest`.

### Round 4a open risks / UNCONFIRMED
- Unit-tested only: no live-server run. Chunk-mirror resolution/timing, client batching of
  rotation-vs-interaction, and the latency of other players' outbound positions are unverified.
- `NearbyPlayers` is a phase-4 placeholder: player entities only, no metadata/invisibility/passengers/
  scale, 128 entries per viewer, relative deltas are not reordered or interpolated.
- `xray` assumes vanilla ore rendering. A server-side ore obfuscation plugin (engine-mode anti-xray)
  invalidates the "invisible ore" premise → disable the check there.
- `esp` is a heuristic (marked experimental): a player deliberately tracking a wall or a teammate can
  satisfy it; keep `min-ticks`/`min-target-movement` high on PvP servers. It never fires through glass
  or leaves (they are see-through) and stays silent while the viewer's chunks are not mirrored.
- `freecam` also reports interactions beyond `max-reach` (6.0) because no reach check exists yet;
  revisit the split when the combat Reach check lands.

## Round 4b (2026-11-13): xray prevention, hot-path cleanup, platform detection — `mvn -B test`: 749 tests green

+15 tests (734 → 749). No new dependency; still no NMS and no `clean` in the build.

- `obfuscation/XrayObfuscator` (NEW, `OutboundTracker`, registered in `VisibilityChecksModule` after the
  simulation module's `WorldTracker`): on `CHUNK_DATA` it replaces fully enclosed valuable ores with
  `fake-block` and re-encodes the packet (`WrapperPlayServerChunkData#setColumn` +
  `markForReEncode(true)`). Reads the wrapper from `getLastUsedWrapper()` when available, so the chunk
  is not parsed twice. Options: `checks.xray.options.{obfuscate,fake-block,max-per-chunk}`.
- `obfuscation/OreObfuscation` (NEW, pure): palette pre-check per section (sections without an ore
  state are skipped without scanning 4096 blocks), copy-on-first-replacement, new `Column` with a new
  section array and preserved heightmaps/biome/block counters. Unknown neighbours, exposed ores,
  partial chunks and exhausted `max-per-chunk` leave the packet untouched.
- `obfuscation/OreStates` (NEW): protocol state ids for the ore list + fake block, `boolean[]` lookup,
  built once per settings instance; resolution failures disable prevention quietly.
- `obfuscation/BlockView` (NEW): read interface (own sections + mirror) that makes the obfuscation
  logic unit-testable without a server.
- `world/BlockSight.rayBoxEntry`: allocation-free slab test (previously three `double[3]` per call;
  it is the per-interaction hot path). No end-to-end benchmark was run.
- `platform/ServerPlatform` (NEW) + one enable-time log line: Paper/Purpur/Folia/hybrid detection from
  Bukkit name+version and the existing Folia class check; no fork dependency. Tests: `ServerPlatformTest`.
- `docs/PLATFORMS.md` (NEW): supported servers, Java 21/1.21+/26.x matrix, why a native Fabric mod is
  not this artifact, and what a port would need.
- Config: `checks.xray.options.obfuscate/fake-block/max-per-chunk`; `VisibilityCheckConfigTest` keeps
  the key set and defaults in sync.

### Round 4b open risks / UNCONFIRMED
- No live-server run: the rewritten chunk is verified by unit tests on `Column`/`Chunk_v1_18` only.
  Client rendering, Via ordering and re-encode behaviour on a real connection are UNCONFIRMED.
- Heightmap/biome data is carried over with the constructor variant the read path used; a proxy that
  changes the protocol version between read and write is not covered and was not tested.
- Ores hidden at chunk-send time stay fake after a tunnel exposes them until the chunk is resent
  (Paper engine-mode behaviour). Reveal packets are deliberately not sent (mirror poisoning).
- Paper's own engine-mode anti-xray does not conflict (it rewrites the same way), but running both is
  redundant; one of them should be disabled.
- No performance benchmark: the changes remove per-call allocations and per-section scans, they are
  not measured against a load test.

### Round 4 — OPEN (LEAD proposal, in this order)
1. **Detection-strength fix (engine):** the engine seeds each tick from client-reported motion, so sustained
   hacks shrink to a small steady-state offset (B's estimate: ground speed < ~0.35 b/t undetected). Seed from
   the previous *predicted* best candidate (clamped to reported position) or accumulate offset across ticks.
2. **Client-predicted placing/breaking:** inbound USE_ITEM_ON / PLAYER_DIGGING open windows resolved by
   outbound ACKNOWLEDGE_BLOCK_CHANGES — LEAD decision needed on the bypass risk (fake placements keeping
   a permanent skip window open; cap per tick / only blocks adjacent to the hitbox).
3. **Block coverage:** extend `VanillaBlocks` (slabs, stairs, carpets, short grass, torches…) with sourced
   data; engine: wall collision/sliding, step-up, edges — otherwise most real terrain stays `complex-environment`.
4. `ReplayScenario`: `create(AnticheatModule...)` + `move(dx,dy,dz,onGround)` (javadoc says ground is passed
   through, but it is hard-coded false) → fold B's `CheckReplay` into it.
5. **Live smoke test** on Paper 1.21.x/26.x (+ Via, Folia if available): setback reaction, marker ordering,
   chunk height timing, false-positive rate; then tune thresholds.

### Round 3 open risks / UNCONFIRMED
- Same-dimension respawn: whether the client keeps chunks is UNCONFIRMED (mirror clears conservatively).
- Client-side effect expiry is not tracked; REMOVE_ENTITY_EFFECT is treated as authoritative.
- `setback-vl: 1.0` → first violation already sets back (packet-level setback reaction UNCONFIRMED).
- Fly `check-downward` flags any unmodelled downward effect (0.1 tolerance); `nofall.flag-missing-ground` off.
- Pre-1.18 chunk section formats are dropped (irrelevant for supported 1.21+).

## Round 2 record (2026-11-13) — `mvn -B clean verify`: 604 tests green

Round 1 (A/B/C below) was verified complete at round-2 start (`mvn -B test`: 523 green, Agent B's
teleport queue/gate/setback API done, the `TeleportQueueTest$Lifecycle` failure is gone).
Round 2 ran with 2 parallel agents (strict mode, disjoint files); LEAD integrated
`new SimulationModule()` as 3rd entry in `VAPlusPlugin.modules()` (order matters — it looks up
components of the two modules before it) and ran `mvn -B clean verify`: **604 tests, 0 failures, BUILD SUCCESS**.

- **Agent 1 — simulation** (DONE, +40 tests):
  - LEAD decision applied: `AttributeState` now uses vanilla semantics `(base + Σ add_value) ×
    (1 + Σ add_multiplied_base) × Π(1 + add_multiplied_total_i)`, clamped to Wiki ranges; helpers
    `version()`, `hasModifier`, `valueWithout`, `minValue/maxValue`.
  - `player/state/VelocityState` + `pipeline/VelocityTracker` (ENTITY_VELOCITY = SET, EXPLOSION knockback =
    ADD, marker-bracketed SENT → WINDOW → ACKED; fields verified via javap on PE 2.14.0).
  - `simulation/{PhysicsConstants,SupportScan,PredictionState,PredictionSimulator,SimulationModule}`:
    candidate-input prediction (forward/strafe/sprint/jump/item-use × velocity prefixes, ≤ 20 ticks);
    conservative — anything not modelled (walls, edges, step-up, liquids, elytra, levitation, 26.2
    friction/air-drag/bounciness ≠ default, flying, vehicles, pending teleport…) → `SKIPPED` with reason.
  - Replay harness `testutil/replay/{ReplayScenario,VanillaMotion}` + `PredictionSimulatorTest` (24).
- **Agent 2 — platform/pipeline** (DONE, +44 tests):
  - M15: `latency/ServerTickMarkers` component (`"server-tick-markers"` in CoreTrackingModule;
    `acknowledgedServerTick()`, `lastMarkedServerTick()`, `pendingTicks()`), `platform/ServerTickMarkerService`
    (Paper: `com.destroystokyo.paper.event.server.ServerTickEndEvent` MONITOR; Folia: per-player 1-tick
    `EntityScheduler.runAtFixedRate`, lifecycle via ConnectionListener; Folia detected via
    `io.papermc.paper.threadedregions.RegionizedServer`). Toggle `latency.server-tick-markers` (default true).
  - L11: `ExemptionState.holdUntilLoaded/releaseLoadHolds/awaitingLoad/expiredLoadHolds`; `GameStateTracker`
    is also an InboundTracker for PLAYER_LOADED (in PE 2.14.0 from `ServerboundPacketType_1_21_4`), gated on
    server ≥ 1.21.4; `MAX_HOLD_TICKS` expiry stays the fallback.
  - Item 7: `ViolationDispatcher` setback → `SetbackHandler.request(player, SetbackTarget, checkId)` when the
    TeleportQueue resolves and a safe position exists; otherwise old Bukkit `teleportAsync` path.

### Round 3 plan (DONE in round 3 except item 5 = engine extensions, moved to round 4)
1. **World feeder** (blocker for the engine to do anything live): outbound CHUNK_DATA / UNLOAD_CHUNK /
   BLOCK_CHANGE / MULTI_BLOCK_CHANGE → `SimulationModule.worldMirrorKey()`; clear on join/respawn/world change.
   Until then every live simulation is `SKIPPED world-unknown`.
2. **Attribute + effect trackers**: UPDATE_ATTRIBUTES → `AttributeState`, ENTITY_EFFECT/REMOVE_ENTITY_EFFECT →
   `EffectState` (marker-bracketed like VelocityTracker).
3. **Checks Speed / Fly / NoFall** against `SimulationResult` offsets (never own physics), +
   `checks.<id>.options{}` in config.yml + replay regression tests.
4. Small API wishes from Agent 1: `WorldMirror.getBlock(int,int,int)` overload (avoid BlockPos alloc),
   `MotionHistory.newest()`.
5. Engine extensions (each needs sourced constants): wall collision/sliding, step-up, liquids, levitation,
   26.2 friction/air-drag/bounciness.

### Round 2 open risks / UNCONFIRMED
- Packet-level setback is invisible to the server; vanilla reaction to our negative TELEPORT_CONFIRM ids
  (and its own "moved wrongly" correction) is UNCONFIRMED → live smoke test before release.
- Per-tick markers double marker traffic (~40/s at 50 ms heartbeat); wire-id wrap ~every 410 s instead of
  ~820 s (aliasing still bounded by ack timeout). Disable via `latency.server-tick-markers: false`.
- Folia path only unit-tested (RegionTicker); Paper tick-end ordering vs packet writes not live-verified.
- PLAYER_LOADED resend after every respawn / ViaVersion synthesis for old clients: UNCONFIRMED (expiry fallback).
- `GameStateTrackerLoadTest.serverVersionGate` skips its assertions if another test leaves a global
  PacketEvents API installed (passes in isolation) — fragile.
- Engine UNCONFIRMED values (marked `UNCONFIRMED_*` in `PhysicsConstants`): sprint/effect modifier ids,
  hitbox 0.6×1.8, slow-falling only while vy ≤ 0, sprint preconditions, 1.0E-7 input cutoff, item-use ×0.2
  before 1.21.11, uncertainty sizes, Math.sin vs client trig table.

## Round 1 record (2026-11-12) — A/B/C board

Baseline: Phase 1 complete (397 tests green, review + follow-ups done — details under "Phase 1 final
state" below). Phase 2 round 1 runs with three parallel agents in strict mode (see
`docs/PHASE2-BRIEF.md` for the full contract):

- **Agent A (GLM 5.3 Flash, replacing the aborted predecessor)** — world/mirror package +
  `player/state/AttributeState.java`. Status: **DONE this round** — the 8 mirror classes and
  AttributeState from the predecessor were reviewed, kept as-is (complete, sourced, compiles clean)
  and now covered by 76 new tests (`BlockPosTest`, `BlockKeyTest`, `BlockPropertiesTest`,
  `BlockSnapshotTest`, `ChunkMirrorTest`, `WorldMirrorTest` (incl. ChunkPos),
  `VanillaBlocksTest`, `AttributeStateTest`); targeted run green, full `mvn -B test` run 504 tests.
  OPEN for LEAD (documented, not changed): the AttributeState modifier formula follows the brief,
  but the Minecraft Wiki Attribute/Operations page (fetched 2026-11-12) confirms vanilla semantics
  with the two groupings swapped (`add_multiplied_base` = one `(1+Σamounts)` factor,
  `add_multiplied_total` = per-modifier `Π(1+amount)`); the two coincide for ≤1 modifier per
  multiply op, so only multi-modifier entries differ. Also: 1 foreign failure from Agent B,
  `TeleportQueueTest$Lifecycle.epochDiscard` (not touched by A).
- **Agent B (GPT 6.1 Sol)** — pipeline/** additions (TeleportQueue, MovementProcessor gate,
  SetbackHandler request-API, CoreTrackingModule) + movement/** + platform/TeleportSetbackHandler.
  Status: **DONE** (verified 2026-11-13: all its tests green, full run 523).
- **Agent C (GLM 5.3 Flash)** — `docs/PHASE2-BRIEF.md` (NEW), this board, `player/state/MotionHistory.java`
  (+ `TickSnapshot`), `pipeline/MotionTrackingModule.java` (its only new pipeline file),
  `VAPlusPlugin.modules()` list extension, new tests (`MotionHistoryTest`, `MotionTrackingModuleTest`).
  Status: **DONE this round** — 411 tests green (`mvn -B test`, no clean; 397 baseline + 14 new).

The final `mvn -B clean verify` runs in the integration follow-up of the same session (after all three
agents report).

## Phase 1 final state (reference, 2026-11-12): COMPLETE — all steps 1–22 done, review + follow-ups done

- `mvn clean verify`: **397 tests, 0 failures, 0 errors**, build success.
- Last green state before the review fixes was 356 tests; the delta is the new/renamed QA + regression tests.
- Old Sonnet/Opus model note superseded first by the GLM 5.3 Flash choice, then by the parallel
  strict-mode rule at the top of this file.

### Independent review (step 22) — DONE
The GLM 5.3 Flash read-only review covered the full production areas plus `PlayerDataTest`,
`CheckTest` and `MarkerTrackerTest` and ran its own `clean verify` (392 tests green at that point):
**no blockers**. Its bounded follow-up points were applied in a second round (see below); the
bytecode-level threading evidence and the applied findings are documented in `docs/REVIEW-NOTES.md`.

### Second-round follow-ups (all applied, 397 tests green)
- **Threading**: outbound PacketEvents events + `tasksAfterSend` (brackets) provably run on the
  connection's event loop — evidence from the exactly installed dependencies (`packetevents-spigot`
  2.14.0 encoder/decoder, `packetevents-api` 2.14.0 `EventManager`, `netty-transport` 4.1.72.Final
  `AbstractChannelHandlerContext.write` scheduling, no `EventExecutorGroup` in the pipeline binding).
  No routing change; documented in `docs/REVIEW-NOTES.md`. Re-check on PacketEvents upgrades.
- **Wire-id replay window**: "cannot force-ack" was too absolute — ids are not authenticated and the
  14-bit wire-id range is reused after 16384 sent markers, so a replayed pong aliases the currently
  pending marker with the same low bits. ARCHITECTURE.md + `MarkerTracker` javadoc/comments made
  precise (comment-only, semantics unchanged); deterministic regression test
  `MarkerTrackerTest.wireIdWrapAliasesStalePong` documents the aliasing.
- **Messages.yml strict/atomic loading (H3, real bug)**: `MessageService.load()` swallowed YAML
  syntax errors (`loadConfiguration`) and `reloadConfiguration()` committed message changes before
  config validation. Now: `MessageService.prepare()` (strict, throws `LoadException`, keeps the live
  catalogue) + `apply()` (commit); `VAPlusPlugin.reloadConfiguration()` prepares both files first and
  commits them together — a broken config.yml no longer commits a message reload and vice versa;
  first enable falls back to the built-in defaults with a severe log. Regression tests without a
  live server: `MessageServiceTest` (corrupt user file, corrupt bundled defaults, valid overrides +
  default fallback, bundled-only).
- `CheckTest.Misc.exemptionMask` now covers both the timed grant and the bracket-hold path including
  hold expiry (60 client ticks → `expiredHolds()`).
- `config.yml` placeholder docs now list `%details%` for punishment actions.

### First-round work (step 21 tail + 22 prep)
- Fixed the 3 stale `PlayerDataTest` tests against the new ack-timeout semantics
  (`GlobalSettings` now validates 5000–120000 ms, default 15000; `0` no longer disables the timeout):
  - `ackTimeoutKick` uses 5000 ms and now asserts the **claimKick 5 s rate limit** (kick is requested
    again after the window; a kick no longer sets `disconnecting`).
  - `answeredMarkersDoNotTimeOut` uses 5000 ms.
  - `timeoutDisabled` now asserts that 0 / 4999 / 120001 are rejected and the default is 15000.
- Added a regression test for the descriptor `exemptionMask` OR-combination in `Check.isExempt()`
  (`CheckTest.Misc.exemptionMask`): an active masked cause exempts in survival, an unmasked cause (JOIN)
  does not.
- Re-verified production against ARCHITECTURE.md/HANDOFF (read-only): MarkerTracker (wire ids
  `-32768…-16385`, refused-send → next sequence, `MAX_TASKS` cap, `scheduledTasks()`), CheckBundle
  receive/send split + epoch settings sync, PlayerData components-before-checks + kick semantics,
  MovementState safe position/`advanceWorldEpoch`/`invalidatePosition`, ExemptionState hold expiry
  (60 client ticks, `expiredHolds`), ConfigSnapshot `max<=limit` → derived headroom + explicit-only
  warning, TextTemplates backslash → U+2216, InputTracker server-version gating, OutboundStateListener
  MONITOR + cancelled-packet skip, GameStateTracker respawn bracket, ConnectionListener permission-task
  lifecycle (`removeIfAttached`, `shutdown()`), ConfigService `LoadException`/`loadBundled()`,
  AlertService async delivery, AcCommand snapshot/clamp/global-region-scheduler, messages.yml alert
  rewording, `AsyncCheckFlagEvent` sync javadoc. All consistent with the architecture; no code
  changes were needed in this round beyond the test fixes above.

### Regression-test coverage for the review fixes (all present)
MarkerTracker refused-send/next-sequence/bracket-aliasing/`MAX_TASKS`/wire-id-wrap aliasing;
ExemptionState hold expiry (from-first-hold, nested holds, grant survives expiry); MovementState
setback-requested consumption + world epoch; ConfigSnapshot `maxEqualsLimit` + ack-timeout clamping
(SettingsParserTest); backslash escape (TextTemplatesTest + MessageCatalogTest); ModuleContext
components/tick listeners/simulator single-install; Check exemption mask (grant + hold + expiry);
MessageService strict loading (`MessageServiceTest`).

## Remaining Phase 1 work: NONE

- Step 21 (tests): done — 397 green.
- Step 22 (docs + independent review): done — review completed by the user with GLM 5.3 Flash
  (read-only, no blockers); its bounded follow-up points are applied (see "Second-round follow-ups"
  above). Phase 2 can start on the user's "continue".

## Known weaknesses / limits (documented in ARCHITECTURE.md, no action in Phase 1)
- No productive detection checks and no simulation engine yet (InactiveSimulator only) — by design;
  that is Phase 2.
- Restart, not `/reload`: late-enable state resync is unsupported (players online before enable are
  only re-attached, game mode/abilities come from the next packets).
- Via/Folia/26.x live compatibility is NOT proven by unit tests: PacketEvents 2.14.0 covers 26.3 on
  paper, but no live-server smoke test was run; Folia 26.x existence is unconfirmed (version-notes §1.2).
- The owner-thread guarantee for outbound events is bytecode-level evidence for PacketEvents 2.14.0 +
  Netty 4.1.72 (`docs/REVIEW-NOTES.md`), not a live-server test; re-check on dependency upgrades.
- Marker wire ids are not authenticated and are reused after 16384 sent markers (replayed-pong
  aliasing window, bounded by the ack timeout) — documented, not fixable without protocol changes.
- Floodgate: console-command actions should prefer `%uuid%` (names can collide); send markers via
  `sendPacketSilently` only after verifying ordering with Via on a live server.
- `latency.ack-timeout` can no longer be disabled (clamped 5000–120000, default 15000); the
  `timeoutMillis > 0` guard in `PlayerData.heartbeat()` is harmless dead code kept for safety.
- Unit tests run without a Bukkit server; platform classes (AlertService delivery, BukkitPunishment
  executor, ConnectionListener, AcCommand, OutboundStateListener event objects) are only covered by
  compile-time checks and reviewer reading, not by tests.

## Deferred by LEAD decision (do NOT do in Phase 1; put into the Phase 2+ briefs)
- H5 core teleport model: outbound PLAYER_POSITION_AND_LOOK queue (relative flags, 1.21.2 velocity),
  TELEPORT_CONFIRM matching, MovementProcessor gate before hooks, packet-level setback API
  `request(player, target, reason)` registered in that queue → Phase 2 (SIMULATION), contract by LEAD.
- M12 per-client-tick snapshot ring buffer + engine-owned MotionState (component) → Phase 2;
  ring buffer (`MotionHistory`) + `MotionTrackingModule` already landed in Phase 2 round 1 — the
  engine-owned interpretation (exact per-tick deltas) is still open.
- M13 AttributeState base + modifier lists, 26.2 friction/air-drag/bounciness → Phase 2.
- M15 per-server-tick marker (Paper ServerTickEndEvent / Folia 1-tick entity task) → Phase 2.
- L11 hold JOIN/RESPAWN exemption until PLAYER_LOADED (1.21.4+) → Phase 2.
- M14 combat contract: INTERACT_ENTITY / 26.x ATTACK / PUNCH / STAB normalisation, `AttackCheck`
  capability dispatched after the next movement/tick end, core EntityTracker (spawn, rel-move,
  ENTITY_POSITION_SYNC, destroy, passengers, scale, BUNDLE) → Phase 4; RELEASE_USE_ITEM/SWAP/DROP
  interaction kinds → Phase 3 (NoSlow/FastUse).
- L12 vehicle movement (VEHICLE_MOVE, STEER_BOAT, minecart) → Phase 3.
- M9 parse-once packet views for PacketChecks → Phase 5.
- L8 send markers with `sendPacketSilently` → only after verifying ordering with Via on a live server.
- L1 late-enable state resync (hot reload unsupported; document "restart, don't /reload").
- L13 Floodgate names in console commands → document, prefer `%uuid%`.

---

## History: steps 1–20 (all DONE, kept for reference)

The REVIEWER findings were applied; the main compile was clean and tests were finished by the QA agent
(steps 1–21). Renames: `RotationUpdate`→`LookChange` (accessor `lookChange()`),
`TransactionTracker`→`MarkerTracker`, `ViolationProcessor`→`ViolationDispatcher` (tests renamed as well).
Key implementation notes:
- `latency/MarkerTracker`: short-range wire ids `-32768…-16385` (`toWireId`, `isMarkerId`), refused send
  returns the *next* sequence, head-indexed task array, `MAX_TASKS` cap, `scheduledTasks()`.
- New `module` package: `AnticheatModule`, `ModuleContext`, `ModuleSetup`, `ComponentKey`;
  `pipeline/ClientTickListener`, `pipeline/CoreTrackingModule`; `check/CheckModule` deleted.
- `check/PacketCheck` is receive-only; new `check/OutboundPacketCheck`; `CheckBundle` has separate
  receive/send arrays.
- `CheckDescriptor.Builder.exemptWhile(ExemptionCause...)` → `long exemptionMask()`;
  `Check.isExempt()` = game-mode exemption OR `player.exemptions().anyActive(descriptor.exemptionMask())`.
- `CheckDefaults` rejects `bufferMax > 0 && bufferMax <= bufferLimit`; `ConfigSnapshot` derives
  `limit + headroom` for `max <= limit` and warns only when `buffer.max` is set explicitly.
- `PlayerData`: components created before checks, `component(ComponentKey)`, `onClientTick()` runs
  tick listeners before tick checks; kick handling `claimKick(nowNanos)` (5 s rate limit),
  `disconnecting` only on quit/disconnect/transport close.
- `ExemptionState`: holds expire after `MAX_HOLD_TICKS = 60` client ticks, counts `expiredHolds()`.
- `MovementState`: `finishChecks()` stores the safe position only without a requested setback;
  `advanceWorldEpoch()` (server side, onSend) vs `invalidatePosition()`.
- `GameStateTracker`: RESPAWN → `advanceWorldEpoch()` immediately; first ack → `invalidatePosition()` +
  hold RESPAWN; second ack → game mode, `effects.clear()`, attributes reset unless `KEEP_ATTRIBUTES`,
  pose flags unless `KEEP_ENTITY_DATA`, `vehicle.dismount()`, `checks().resetAll()`.
- `InputTracker(boolean sneakFromInputPacket)` gated on the SERVER version (≥ 1.21.6).
- `PacketPipeline`: early return on already-cancelled inbound packets (after pong handling); outbound
  state tracking in a separate MONITOR listener (`OutboundStateListener`) that skips cancelled events.
- `MovementProcessor` ignores (does not cancel) non-finite packets; `InteractionProcessor` calls
  `nanoTime()` only inside handled branches.
- Config: `ConfigService.load()` without Bukkit defaults, throws `LoadException` on YAML errors; plugin
  keeps the previous snapshot on reload failure, falls back to `loadBundled()` on first start;
  `latency.ack-timeout` clamped to 5000–120000 (default 15000).
- `TextTemplates.escape` replaces `\` with U+2216 before `escapeTags`.
- `AlertService` captures placeholders on the netty thread, renders/delivers via the async scheduler.
- `BukkitPunishmentExecutor`: kick only after `player.claimKick(now)`; no `markDisconnecting` there
  (except when closing the transport because no Bukkit player exists).
- `ConnectionListener`: permission-refresh `ScheduledTask` per UUID, cancelled on quit and in
  `shutdown()`; idempotent attach; `PlayerDataRegistry.removeIfAttached` on quit.
- `AcCommand`: snapshot violation levels before sorting, page clamped to 1..10000, non-player
  non-console senders via `Bukkit.getGlobalRegionScheduler()`; `DebugService.supportsViewer`.
- `messages.yml`: alert template reworded (`%player% » %check% · VL %vl%` style, hover/click kept).
- `AsyncCheckFlagEvent` javadoc notes synchronous firing from tick threads.
- `VAPlusPlugin` onEnable: `modules(sneakFromInputPacket)` → `ModuleContext.install` → `complete()`
  → context `.setup(setup)`; MONITOR listener registered/unregistered; `connections.shutdown()` in
  onDisable.

## Round 5 — completed (detection quality, combat, operations)

All 10 review items were implemented in this round. `mvn -B test` is green with the round-5 test files
included (see the final count in the round report); the jar is `target/VAPlus-0.1.0-SNAPSHOT.jar`.

1. **Engine seed / detection strength** — `PredictionSimulator` keeps an engine-owned seed for small
   residuals and chains predicted positions (`SimulationResult.cumulativeOffset/cumulativeTicks`), new
   `prediction` check (MOVEMENT, experimental, `cumulative-threshold` 0.15, `min-ticks` 5). Replay test
   proves a 0.005/tick excess accumulates to >0.03 after 10 ticks while a legit walk stays <1e-3.
2. **Block coverage** — `BlockProperties.collisionHeight` (validated, default 1.0), farmland/dirt_path
   (0.9375) and all carpets (0.0625) in `VanillaBlocks`, `SupportScan` accepts a uniform low floor at the
   feet and derives `floorTop` from the height. Simulator tests: standing/walking on both explains exactly.
3. **Combat module** — `check.impl.combat.{Reach,ThroughWall,Killaura,Autoclicker}Check` +
   `CombatChecksModule` (needs `VisibilityChecksModule` + `SimulationModule`), `CheckBundle.dispatchAttack`,
   `PlayerData.entityAttack()`, `InteractionProcessor` handles `INTERACT_ENTITY`. `NearbyPlayers` tracks
   invisibility/sneaking from `ENTITY_METADATA`; `CombatChecksTest` covers all four + spectator exemption.
4. **BadPackets** — pitch, movement rate, placement cursor, dig sequence, sprint+sneak; config section
   `checks.badpackets`; `BadPacketsCheckTest`.
5. **Shadow mode + metrics** — `options.log-only`, `[shadow]` prefix, no setback/punishment,
   `violation.CheckMetrics`, `/ac metrics [reset]`; `ShadowModeAndMetricsTest` (baseline setback, shadow
   suppression, per-player counters). `TestContexts` now passes `registry.size()` slots to the dispatcher.
6. **Live smoke test + telemetry** — `docs/SMOKE-TEST.md` (steps, expected lines, per-check stimuli) and
   `scripts/smoke-check.sh` (platform/check-count/exception checks, `--replay`). **Not executed**: no
   live server in this environment; the metrics command and the log patterns are the tested part.
7. **Replay harness with real traffic** — `diagnostics.{ReplayFrame,ReplayRecorder,ReplayFile}`, system
   property `-Dvaplus.record`, closed in `onDisable`, tolerant parser, roundtrip/format tests and
   `ReplayHarnessTest` replaying recorded-style frames through the engine.
8. **Entity state** — invisibility/sneaking flags in `NearbyPlayers` (+ swap-removal), used by Reach
   (sneak hitbox 1.5) and Killaura (chest 0.75). **Open**: passengers, entity scale and mob hitboxes are
   not tracked, so combat only judges player targets.
9. **Setback fairness** — `SetbackConfirmations` (ring, 10k keys), `setback.confirmations`/`setback.window`,
   wired in `ViolationDispatcher` before the setback request; mechanism tests use 1 confirmation, the rule
   has its own `setbackNeedsConfirmations` test.
10. **Watchdog / auto-degrade** — `PerformanceGovernor` (`degraded`, `shadowOnly`, `preventionPaused`,
    thresholds 1500 µs/player/tick and TPS 15), plugin watchdog task + `slowChecks` warnings + recovery log,
    X-Ray prevention paused while degraded.

### Remaining gaps (honest list)

* No live-server validation and no benchmark: all numbers come from unit/replay tests.
* `through-wall`, `killaura` and `autoclicker` are `experimental` (packet-level limits); keep them in
  shadow mode on competitive servers before enforcing.
* Combat targets: player entities only; passengers/scale/mobs untracked.
* Replay format stores movement only, not block changes or attacks; replay on flat known terrain.
* Slabs/stairs/fences stay COMPLEX because `BlockKey` has no state properties.

## Round 6 (2026-11-15): timer check, killaura multi-target — `mvn -B -o test`: 798 tests green

+15 tests (783 → 798). No new dependency, no `clean`.

- `check/impl/packet/TimerCheck` (`timer`, PACKET, registered in `PacketChecksModule`): client clock vs
  real time, see ARCHITECTURE.md. New `MarkerTracker.lastAckedSentAtNanos()` (send time of the newest
  acknowledged marker) is the clock's lower bound. Config `checks.timer` (`clock-drift-millis`).
  Tests: `TimerCheckTest` (20 TPS, jitter, 1.1x, 2x, 1% skew, lag burst, banking, silent stall,
  latency drop, tick-rate change, stale rate ack, join exemption, legacy tick source).
- `KillauraCheck` `multi-target`: attacks on two different entity ids within one client tick
  (tick-end clients only, buffer weight 2.0), option `check-multi-target`. Tests in `CombatChecksTest`.

### Round 6 open risks / UNCONFIRMED
- No live-server run. The vanilla claims behind both signals are from reading client behaviour, not from
  a capture: (a) the crosshair target is picked once per tick, so in-tick target switches cannot happen;
  (b) pre-1.21.2 clients send exactly one movement packet per tick apart from teleport answers and the
  item-use duplicate. Verify both with a recorded session before enforcing punishments.
- `timer` has no mitigation (no setback, excess ticks are not cancelled); it alerts and feeds the
  `packet` punishment profile only.
- Speed-ups below ~2% stay under the buffer decay (0.01 per tick) by design.
- Timer needs marker traffic for its lower bound: with `latency.server-tick-markers: false` only the
  heartbeat markers remain, which widens the bankable window to heartbeat interval + drift.
- Round 6 addendum: `BadPacketsCheck` `held-slot` (HELD_ITEM_CHANGE outside 0..8, buffer weight 2.0,
  option `check-held-slot`); 799 tests green.

## Round 7 (2026-11-15): triggerbot check — `mvn -B -o package`: 812 tests green

- `check/impl/combat/TriggerbotCheck` (`triggerbot`, COMBAT, experimental, 5th check of
  `CombatChecksModule`), design in ARCHITECTURE.md, config `checks.triggerbot`. Tests:
  `TriggerbotCheckTest` (sweep/flick/delayed bot flag; human reaction, spam clicking, flicks with near
  misses, tracking, lag window, new target, legacy client do not).

### Round 7 open risks / UNCONFIRMED
- No live data: `max-reaction-ticks: 2`, the 3-tick interpolation allowance and the 1.5-block near-miss
  margin are reasoned values. Calibrate with `/ac debug` and `log-only` before any punishment.
- UNCONFIRMED vanilla ordering the check relies on: attack packet before the tick's movement packet, and
  the pick using the previous tick's eye position with the current look direction.
- Bypasses by design: a triggerbot with a delay above `max-reaction-ticks`, one that adds fake swings
  aimed next to the target, and one used only while already spam-clicking. Raising
  `max-reaction-ticks` trades the first against false positives on skilled flick aim.
- A perfectly accurate flick aimer (no near misses, 5+ cold hits within 100 ms in a row) looks the same
  as a triggerbot to this check.
- Player targets only (`NearbyPlayers`); high ping widens the lag window and yields fewer samples.

## Round 8 (2026-11-16): 15 checks/features + storage — `mvn -B -o clean verify`: 1063 tests green

888 → 1063 tests. No new Maven dependency. Drafts from `.agent-work/<id>` were reviewed and integrated by
the lead (phase, criticals, step, jesus, vehicle, mace, inventorymove, autoarmor, storage); the others
were written new. Every new check has a `checks.<id>` section; the three config tests read the shared
list `testutil/ConfiguredChecks.ROUND_8`.

| id | category / module | signal (short) |
|---|---|---|
| `velocity` | MOVEMENT / movement | best candidate that honours every *acknowledged* velocity must explain the move (new `SimulationResult.ackedVelocityOffset`, velocities offered during a skipped move are tainted) |
| `scaffold` | WORLD / visibility, exp. | clicked full-cube face not visible from the eye (current or previous position), hit point off the face plane |
| `phase` | MOVEMENT / movement, exp. | box ends inside or tunnels in one tick through mirrored full solid cubes |
| `crasher` | PACKET / packet | **cancels**: packet flood (600/s), oversized packets, impossible window clicks, oversized books / tab completes |
| `criticals` | COMBAT / combat, exp. | attack during a faked take-off (airborne claim lower than a jump, unexplained by the engine) |
| `ghosthand` | WORLD / visibility, exp. | place/use/dig-start through a wall of `OpaqueCubes` (voxel walk eye → hit point) |
| `fastbreak` | WORLD / visibility | nuker (2 blocks started per tick), finish in the start tick, known-hardness block faster than the best legal tool |
| `step` | MOVEMENT / movement | rise per tick from ground above max(step height, jump), packets of one tick summed |
| `jesus` | MOVEMENT / movement, exp. | resting on a liquid surface for > min-ticks |
| `vehicle` | MOVEMENT / movement, exp. | boat speed off ice, boat hover/rise in free air, mount high jump (VEHICLE_MOVE) |
| `mace` | COMBAT / combat, exp. | attack right after a fake rise / faster-than-terminal drop |
| `elytra` | MOVEMENT / movement, exp. | gliding hover, or never descending for 100 ticks without a boost |
| `inventorymove` | INVENTORY / inventory, exp. | keys held / turning / sustained speed while a container is provably open |
| `autoarmor` | INVENTORY / inventory, exp. | offhand action ≤2 ticks after own totem pop, clicks while moving, click+attack in one tick, armor shift-click bursts |
| `fastuse` | WORLD / visibility | item use every tick / bursts / rate above human clicking (client-only 4-tick delay) |

Persistence: `storage/*` (`ViolationStorage`, `PersistentViolationLog`, `JdbcViolationStore`, settings +
parser). New top-level `storage:` section (default SQLite `violations.db`, retention 30 days); the
in-memory log stays the session cache; one writer thread, bounded queue, failures degrade to memory.
JDBC drivers are looked up at runtime (not bundled). Tests run real SQLite by loading the driver jar from
the local Maven repository (skipped if absent).

### Core fixes found while integrating (affect existing checks)
- **Mount state was never fed**: nothing called `VehicleState.mount()` and the VEHICLE exemption was never
  granted. New `pipeline/PassengerTracker` (SET_PASSENGERS / DESTROY_ENTITIES; mount at send, dismount
  after the second marker, VEHICLE grant around both).
- **Gliding pose was never fed, and `glideRequested` stuck forever**: after the first elytra start the
  engine skipped every movement (`elytra`) for the rest of the session. New `pipeline/PoseFlagsTracker`
  (own ENTITY_METADATA flags 0x80 gliding / 0x10 swimming, off after the second marker) and request expiry
  after 60 ticks (`PoseState.ageGlideRequest`).
- Draft bugs fixed: MaceCheck `now - Long.MIN_VALUE` overflow (flagged real falls), JdbcViolationStore kept
  the connection open when a newer schema was refused, Step/Mace test setup errors.

### Round 8 open risks / UNCONFIRMED
- Still no live-server run; every number is unit/replay tested only. All `exp.` checks belong in
  `log-only` until calibrated.
- **Exemption causes never granted anywhere**: TELEPORT (handled by the teleport gate instead), WORLD_CHANGE,
  ATTRIBUTE_CHANGE, KNOCKBACK, EXPLOSION, PORTAL, ELYTRA_BOOST, WIND_CHARGE, RIPTIDE, BOUNCE, LUNGE. Checks
  list them in `exemptWhile` but rely on their own guards (velocity state, world scans). Next round should
  feed at least RIPTIDE/WIND_CHARGE/ELYTRA_BOOST (item use + release tracking).
- `mace`: Riptide launch in rain on land with a trident held longer than `wind-charge-ticks` can flag.
- `fastbreak` speed signal knows neither held tool nor enchantments: lower bound from `max-tool-speed`
  and a 31-block hardness table (wiki values, UNCONFIRMED this session).
- `crasher` limits are generous TUNING values; it cancels packets, so watch the log for drops after updates.
- Vanilla client claims used by scaffold/fastbreak/fastuse/elytra (pick with interpolated eye, one start
  per tick, 4-tick use delay, glide formula) are read from client code, not verified by a capture.

## Round 9 (2026-11-18): Meteor-Client combat coverage — `mvn -B -o clean verify`: 1077 tests green

Problem reported by the user: only speed/fly flagged, KillAura & co. never did. Root causes:
1. `NearbyPlayers` tracked **player entities only**, so every combat check (killaura, reach, hitbox,
   triggerbot, through-wall) ignored attacks on mobs and end crystals.
2. Meteor's KillAura **rotates to the target** by default, so the killaura angle test never fires.

Changes:
- `world/EntityDimensions` (NEW): vanilla width/height table for mobs, animals, golems, end crystals,
  armor stands. Size-variable entities (slime, magma cube, phantom) and the ender dragon are left out.
- `NearbyPlayers` / `NearbyPlayersTracker`: track every entity in that table (`isPlayer`, `halfWidth`,
  `height`, `centerY`), `MAX_TRACKED` 256, a new player evicts a mob when full. ESP still judges players only.
- Reach / ThroughWall / Killaura / Triggerbot / Hitbox use the per-entity box (through-wall skips
  boxes wider than 2 blocks).
- `combat/AimCheck` (NEW, `aim`, experimental) + `SensitivityEstimator`: mouse-grid (GCD) learnt from
  non-combat rotations, off-grid rotations within `combat-window` ticks of an attack; snap-to-target and
  snap-back at an attack. Skips teleports, vehicles, pitch clamp, unknown grid.
- `KillauraCheck` new signals: `item-use` (attack during the server-acknowledged hand-active flag,
  confirmed `item-use-confirm-ticks` later), `container` (attack while an acknowledged server container
  is open), `no-swing` (1.21.2+ clients: attack without ANIMATION before tick end).
- config.yml: `checks.aim`, new killaura options. Tests: `MeteorCombatTest` (+14).

Meteor module → check map: KillAura/CrystalAura/AnchorAura (killaura, aim, reach, hitbox, through-wall,
no-swing), AimAssist/BowAimbot-style rotations (aim), Multitask/AutoEat+aura (killaura item-use),
GUIMove (inventorymove, killaura container), AutoTotem/Offhand/AutoArmor (autoarmor), Criticals,
Hitboxes, Reach, AutoClicker, Velocity, NoSlow, Jesus, Spider, Step, Phase, Flight, Speed, NoFall,
Timer, Scaffold, FastUse, SpeedMine/PacketMine (fastbreak), GhostHand, ESP, Freecam, Xray, BoatFly
(vehicle), ElytraFly (elytra).

### Round 9 open risks / UNCONFIRMED
- No live-server run. Verify with Meteor on Paper 1.21.x: KillAura (rotation Always/OnHit/None) on
  zombies and on a second account, CrystalAura, Multitask, GUIMove; watch `/ac debug aim` for the
  learnt grid and off-grid counts.
- `aim` false-positive sources: zoom mods (OptiFine/Zoomify change sensitivity), controller mods,
  macOS/Wayland fractional cursor deltas (grid halves; estimator should adapt), Geyser/Bedrock players.
  Run it with `log-only: true` first on a live server.
- Baby mobs and the `scale` attribute are judged against the adult box (more lenient for reach/hitbox,
  centre offset for angle checks).

## Round 10 (2026-11-18): silent aura + more checks — `mvn -B -o clean verify`: 1089 tests green

- `aim` (rewritten, needs the entity tracker now): new **lock** signal (sent rotation within
  `lock-degrees` of the exact hitbox centre on yaw and pitch, minimum over the target's last 20 tracked
  positions sampled along each segment, from the current and previous eye position; 5 of the last 8
  attacks). **snap** generalised to silent rotations: jump away ≥ `snap-min-degrees`, attack, jump back
  within `snap-hold-ticks` to within max(`snap-return-degrees`, `snap-return-ratio`·jump) of the start.
- `packet/PostCheck` (NEW, `post`): attack/swing/use/place/dig/slot after the movement packet of the same
  tick (1.21.2+). Excludes the teleport answer (after TELEPORT_CONFIRM) and the full-position re-sync
  right before USE_ITEM. Catches Meteor KillAura rotation `OnHit` and post-motion hooks.
- `packet/BlinkCheck` (NEW, `blink`): >1 movement packet per tick (allowance for teleport answer and
  pre-use re-sync) and > `max-silent-ticks` tick ends without movement (vanilla forces one every 20).
- `movement/SprintCheck` (NEW, `sprint`): sprinting ≥ `min-ticks` ticks without forward in the
  1.21.2+ input packet (omni/rage sprint). Swimming, gliding, vehicles, flight not judged.
- Tests: `SilentAuraTest`, `PostAndBlinkCheckTest`, `SprintCheckTest` (+12).

### Round 10 open risks / UNCONFIRMED
- Vanilla packet order inside a tick (input → movement → tick end) and the pre-use full-position packet
  are read from client code, not captured. Run `post` and `blink` with `log-only: true` first.
- `lock` assumes the client aims at the target it renders on the server's recent path; heavy entity
  interpolation corner-cutting can hide a lock (misses, not false flags).
- Not done: KeepSprint (needs the client's attack-cooldown state), AutoWeapon/AutoTool slot timing.

## Round 11 (2026-11-18): scaffold rework — `mvn -B -o clean verify`: 1094 tests green

`check/impl/world/ScaffoldCheck` now has six signals (ideas modelled on Grim's publicly named
RotationPlace / AirLiquidPlace / FarPlace / PositionPlace / FabricatedPlace checks and on silent-rotation
detection; written from scratch — no Grim (GPL-3) or Intave (proprietary) code was copied):
face, cursor (unchanged), **ray** (look ray must cross the clicked face at the hit point; candidates:
eye along last→current position × standing/crouching eye × rotations between the one sent before and
the one sent after the placement, ≤0.5° steps; judged at the next rotation or tick end), **reach**
(closest block point vs `block_interaction_range` + 0.3), **air** (clicked air/water/lava/bubble column;
own placements of the last 60 ticks skipped), **snap** (≥60° away, place, back within 3 ticks to within
max(5°, 10%)). Ray is skipped while attributes are pending or `scale` ≠ 1. Meteor Scaffold with
rotations is additionally caught by `post` (it places from the post-motion hook).

### Round 11 open risks / UNCONFIRMED
- Whether the client's crosshair at input time uses the rotation between the two sent rotations (model
  assumption). Run with `log-only: true` and watch `/ac debug scaffold` for `signal=ray miss=` values.
- Telly/god-bridging with very fast flicks could approach the snap pattern; thresholds are conservative.
