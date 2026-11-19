# VA+ (VAPlus) — Architecture & Interface Contracts

Owner: LEAD/ARCHITECT. Agents implement against these contracts; changes go through the LEAD.

## Platform

| Item | Choice |
|---|---|
| Server | Paper / Purpur / Folia, Minecraft 1.21 – 26.3 (`folia-supported: true`) |
| Bytecode | Java 21 (`--release 21`), so one jar loads on 1.21.x (Java 21) and 26.x (Java 25) |
| Compile API | `paper-api 1.21.1-R0.1-SNAPSHOT` (oldest supported, nothing newer leaks in) |
| Packets | PacketEvents 2.14.0 (`provided`, hard `depend: [packetevents]`; 2.12/2.13/2.14 added 26.1/26.2/26.3) |
| NMS | none |
| Hybrid | Bukkit-API bridges on Fabric/NeoForge (Cardboard/Sinytra, Mohist, Arclight, Youer) load the same jar; a native Fabric mod is a separate project — see `docs/PLATFORMS.md` |

## Threading model

| Thread | What runs there |
|---|---|
| Netty event loop of the connection ("owner thread") | every PacketEvents receive/send event of that player, all checks, all `PlayerData` state, the ack tracker |
| Entity scheduler of a player | kicks, setbacks (`teleportAsync`), alert delivery to that staff member, permission refresh (every 100 ticks) |
| Global region scheduler | console commands from punishment actions |
| Async scheduler | latency heartbeat (only *schedules* work onto each owner thread via `ChannelHelper.runInEventLoop`) |
| Command thread | `/ac` (reads volatile snapshots; diagnostic values may be one packet stale) |

Rule: check code never calls thread-bound Bukkit API. Server state reaches the player state through
outbound packets (trackers), not through Bukkit getters.

## Package map & ownership

```
dev.vaplus.anticheat
├── VAPlusPlugin, AnticheatContext ............ LEAD (bootstrap, service hub, no static singletons)
├── api.event.AsyncCheckFlagEvent ............. LEAD (public, cancellable)
├── check ..................................... LEAD — framework
│   └── impl.movement / combat / packet / world / inventory   (phases 2–5, one agent each)
├── violation, alert, config, command, profiling, platform .... LEAD
├── module (AnticheatModule, ModuleContext, ModuleSetup, ComponentKey) ... LEAD — one module per phase
├── player (+ state) .......................... LEAD (containers); new per-player state = module components
├── latency ................................... LEAD (AckTracker contract + MarkerTracker), handed to SIMULATION in phase 2
├── pipeline .................................. LEAD (PacketPipeline LOW, OutboundStateListener MONITOR, processors, CoreTrackingModule)
├── movement (MovementUpdate, LookChange, SetbackHandler) . LEAD contracts
├── simulation (MovementSimulator, SimulationResult) .......... LEAD contract, SIMULATION implements
└── world (BlockInteraction) .................. LEAD contract
```

## Contracts

### Checks
* `Check` — abstract base, one instance per player and check type, created by a `CheckFactory`
  (constructor reference) registered through `ModuleContext.registerCheck`. Bound to a static
  `CheckDescriptor` (id = YAML key, category, display name, `CheckDefaults`, exemption mask via
  `exemptWhile(ExemptionCause...)`).
* Capability interfaces (a check may implement several, e.g. Scaffold = rotation + block):
  `PacketCheck` (raw inbound packets, pre-update state, may cancel), `OutboundPacketCheck`
  (outbound packets, kept separate because the server sends far more), `PositionCheck` (after simulation),
  `RotationCheck`, `BlockCheck`, `ClientTickCheck`. *Deviation from the brief:* these are interfaces
  plus one abstract base instead of four abstract base classes, because Java's single inheritance
  would otherwise block multi-capability checks.
* Hot-path API: `raiseBuffer(amount) → boolean`, `lowerBuffer()`, `violation(weight, details)`,
  `isExempt()`, `debugging()/debug(msg)`, `onSettingsApplied(settings)` (cache options here),
  `onReset()`.
* `CheckBundle` dispatches through flat arrays, skips disabled checks and bypassing players,
  isolates exceptions (`CheckErrorReporter`, 1 log/min/check), times each call (`CheckProfiler`)
  and re-applies settings when the registry epoch changes (reload never touches check state
  from a foreign thread).

### Violations
`violation()` → `ViolationDispatcher.authorize` (API event, cancellable) → commit level →
`apply`: log record → alert (≥ `alert-vl`, rate limit `alerts.min-interval`) → setback (MOVEMENT
only, ≥ `setback-vl`) → `ActionThreshold`s crossed (`kick`, `cmd:`, `notify`). Level decays
linearly (`vl-decay-per-minute`), computed lazily, no timers.

### Latency compensation (`AckTracker`)
Markers are pings with ids in the signed-short sub-range `-32768…-16385` (fits ViaBackwards' ping→
window-confirmation mapping for pre-1.17 clients, which needs its `handle-pings-as-inv-acknowledgements`
option); the client echoes them in order. Handles are
monotonic `long`s. `bracket(tasksAfterSend, onFirstAck, onSecondAck)` sends one marker before and
one after an outbound packet: between the two acks both old and new state must be tolerated
(trackers *hold* an `ExemptionCause`), after the second ack the state is committed. Echo ids are
matched only against currently pending markers: duplicate, stale or never-sent ids that match nothing
are ignored (a client cannot force-ack a marker it has never received); skipped ids count as anomalies.
Two residual limits: the ids are not authenticated (a pending id is guessable in principle), and the
14-bit wire-id range is reused after 16384 sent markers, so a recorded pong from a previous rotation
aliases the currently pending marker with the same low bits. The ack timeout bounds how long any
marker stays pending and thus both windows.
A heartbeat marker every `latency.heartbeat-interval` ms measures RTT; a marker unanswered for
`latency.ack-timeout` ms disconnects the player.

### Movement
`MovementProcessor.process(primitives…)` (replayable without PacketEvents) → `MovementUpdate`
(reused) → `MovementHook`s (teleport recognition, phase 2) → `MovementSimulator.simulate(player,
update, result)` → `PositionCheck`s → safe position stored unless a setback was requested.
`elapsedTicks` is exact once the first tick-end packet arrived (1.21.2+ protocol, detected at
runtime rather than trusted from the client version, so Via translation is handled).

`SimulationResult` — status `NOT_RUN | SKIPPED | COMPLETE`, best-candidate offsets (total,
horizontal, signed vertical), predicted delta and ground state, ticks simulated, uncertainty.
Movement checks consume only this; they never re-implement physics.

### Player state (`PlayerData`)
`MovementState`, `AbilityState` (game mode, flight), `EffectState` (`TrackedEffect` amplifiers),
`AttributeState` (vanilla defaults), `PoseState` (sneak/sprint/glide + 1.21.2+ key input),
`VehicleState`, `ExemptionState` (bit mask; timed grants + holds), `MarkerTracker`,
`CheckBundle`, reused `MovementUpdate`/`LookChange`/`BlockInteraction`/`SimulationResult`.

### Modules (`module`)
Every phase contributes exactly one `AnticheatModule`, appended to `VAPlusPlugin.modules(...)`. Through
`ModuleContext` it registers checks, inbound/outbound trackers, movement hooks, client-tick listeners,
typed per-player components (`registerComponent` → `ComponentKey`, resolved by
`PlayerData.component(key)`, created before checks) and at most one `MovementSimulator`. Outbound
trackers run at MONITOR priority (`OutboundStateListener`) and skip cancelled packets, so only state
that really reaches the client is tracked. Holds expire after 60 client ticks (withholding guard).

#### Current limitations (Phase 2, after round 3)
* Checks: `speed`, `fly`, `nofall` (`check/impl/movement`, `MovementChecksModule`) consume only
  `SimulationResult` and stay silent unless it is `COMPLETE`. Thresholds in `config.yml` are tuning values,
  not vanilla facts, and are not live-tuned yet. The visibility checks (`xray`, `freecam`, `esp`,
  `check/impl/world`, `VisibilityChecksModule`) are described in their own subsection below.
* `SimulationModule` installs `PredictionSimulator` (replacing `InactiveSimulator`, kept only as the context
  fallback) plus `WorldTracker` (CHUNK_DATA/UNLOAD/BLOCK_CHANGE/MULTI_BLOCK_CHANGE → `WorldMirror`),
  `AttributeTracker`, `EffectTracker`. Uncertainty windows skip the engine with `world-pending`,
  `attributes-pending`, `effects-pending`.
* Most real terrain still skips (`complex-environment`): the `VanillaBlocks` table is small and the engine
  models no walls/edges/step-up/liquids/partial shapes.
* Client-predicted block placing/breaking opens no window yet → false-positive risk while bridging/pillaring.
* The engine seeds each tick from the client-reported motion, so a sustained hack shows a smaller
  steady-state offset than its first tick (Agent B's estimate: ground speed hacks below ~0.35 b/t stay under
  the 0.03 threshold — not measured).
* `AttributeState` follows vanilla modifier semantics (Minecraft Wiki Attribute page): `(base + Σ add_value)
  × (1 + Σ add_multiplied_base) × Π(1 + add_multiplied_total_i)`, clamped to the attribute range.
* Packet-level setbacks (ViolationDispatcher → `SetbackHandler.request`) are not visible to the server;
  how vanilla reacts to our negative TELEPORT_CONFIRM ids is UNCONFIRMED until a live smoke test.
* Hot reload is unsupported: after `Bukkit /reload` (or a late enable) existing players are re-attached
  but their game mode / abilities are only learned from the next matching packets — restart instead.
* Via/Folia/26.x live compatibility is not proven by unit tests. PacketEvents 2.14.0 covers 26.3, and
  protocol decisions use the server version, but no smoke test on a live server (ViaVersion pre-1.17
  ping mapping, Folia schedulers, 26.x) has been run. Folia builds for 26.x are unconfirmed.
* Console-command punishment actions should prefer the `%uuid%` placeholder (Floodgate and offline
  players can make `%player%` ambiguous). Sending markers with `sendPacketSilently` is deferred until
  ordering with Via is verified on a live server.

#### Visibility checks (round 4a: anti-xray, anti-esp, anti-freecam)

One module, `check/impl/world/VisibilityChecksModule`, installed after `SimulationModule` (it resolves
that module's world-mirror key). X-Ray is detection *and* prevention: `obfuscation.XrayObfuscator`
rewrites outgoing chunk packets so hidden ores never reach the client (see "X-Ray prevention" below).
ESP and freecam are detection only — hidden entities are not withheld, the client keeps vanilla data
and cheats are reported instead.

* `xray` (WORLD, experimental). On `DIG_START` the mirror is asked for the targeted block (the client
  announces the block before the server sends its `BLOCK_CHANGE`, so the pre-break state is still
  there). A valuable ore (configurable id list) only counts when all six neighbours are mirrored *and*
  opaque (`world.BlockSight.obscures`); an open or non-mirrored neighbour ignores the find. Buffer + VL;
  a violation starts a fresh window, repeated reports of one position count once. Silent when creative
  (category exemption) or when the mirror is incomplete. Does not catch X-Ray used only for orientation,
  and blind digging toward a base can produce the same signature (rare).
* `freecam` (WORLD). Compares the interaction with the rotation the client itself sent:
  placements use the packet's hit point (angle to the look direction plus reach), dig starts require the
  look ray to intersect the target block's box (`BlockSight.rayBoxEntry`) within reach. Eye height is
  1.62 standing / 1.27 sneaking; gliding, swimming, vehicles and `insideBlock` placements are skipped.
  Undetectable by design: freecam used only to look around, and freecam while gliding/swimming. Far
  interactions (> `max-reach`, 6.0) are reported here too because no reach check exists yet.
* `esp` (COMBAT, experimental). `NearbyPlayers` (module component, fed by the outbound
  `NearbyPlayersTracker`: spawn entity/spawn player/relative move/teleport/position sync/destroy,
  cleared on join/respawn) holds other players' positions. Every `interval` client ticks the crosshair
  must stay on an **occluded** target (`BlockSight.lineOfSight` answers `OCCLUDED`; `UNKNOWN` never
  counts) for at least `min-ticks` while the target moves at least `min-target-movement` blocks. The
  direction test (dot product) runs before the perpendicular miss distance, so targets behind the viewer
  never match. Heuristic by nature: deliberately tracking a wall or a teammate can satisfy it.
* `world.BlockSight` is the single opacity/line-of-sight definition: empty-collision blocks, fluids,
  see-through full cubes (glass, ice, leaves, bars, …) never hide anything, and missing mirror data
  answers `UNKNOWN` instead of air or wall.

#### X-Ray prevention (round 4b)

`obfuscation.XrayObfuscator` is an outbound *transformer* (an `OutboundTracker`, registered after the
simulation module's `WorldTracker` so the mirror is already fed; it never writes player state). On
`CHUNK_DATA` it replaces every valuable ore whose six neighbours are known and opaque with
`fake-block` (default `stone`). `OreObfuscation` is the pure part: palette pre-check per section,
scan only matching sections, copy a section only when it really contains a replacement, build a new
`Column` with a new section array (the mirror keeps the original, true sections) and hand the packet
back via `WrapperPlayServerChunkData#setColumn` + `markForReEncode(true)`. `OreStates` caches the
protocol state ids for the configured ore/fake-block names once per settings instance; unresolvable
names disable rewriting instead of throwing. Config lives in `checks.xray.options`
(`obfuscate`, `fake-block`, `max-per-chunk`) and reuses the shared `valuable-blocks` list.

Trade-off (identical to Paper's engine mode): an ore that was hidden when its chunk was sent stays
fake after a player digs next to it, until the chunk is resent (rejoin, chunk reload). No reveal
packets are sent in v1: a fake→real→fake cycle would feed synthetic block changes back into the
outbound mirror and poison it.

Limits: `NearbyPlayers` is a player-only, position-only placeholder for the phase-4 entity tracker
(no metadata/invisibility/passengers, 128 entries per viewer, relative deltas not reordered). All three
checks are unit-tested only; no live-server run has validated chunk timing, client batching of
rotation-vs-interaction or the latency of other players' outbound positions.

## Deferred contract work (decided by LEAD after the phase-1 review)
* Phase 2 (landed in rounds 1–2): core teleport queue + packet-level setback API, MotionHistory ring
  buffer, AttributeState, per-server-tick marker (`latency/ServerTickMarkers`), JOIN/RESPAWN hold until
  PLAYER_LOADED (server ≥ 1.21.4), VelocityState/VelocityTracker, PredictionSimulator; round 3: world
  feeder, attribute/effect trackers, Speed/Fly/NoFall. Still open: see `docs/HANDOFF-TODO.md` round 4.
* Phase 3: RELEASE_USE_ITEM/SWAP/DROP interaction kinds, vehicle movement routing.
* Phase 4: attack normalisation (INTERACT_ENTITY, 26.x ATTACK/PUNCH, STAB) + `AttackCheck` dispatched after
  the next movement/tick end, core entity tracker (spawn, moves, ENTITY_POSITION_SYNC, passengers, scale, BUNDLE).
* Phase 5: parse-once packet views for PacketChecks.

## Configuration schema
* `config.yml`: `alerts.*`, `latency.*`, `setback.*`, `profiling.enabled`, `logging.*`,
  `categories.<category>.enabled`, `punishment-profiles.<name>: [{vl, repeat, run: [...]}]`,
  `checks.<id>: {enabled, buffer{limit,decay,max}, vl-decay-per-minute, alert-vl, setback-vl,
  profile | actions, options{}}`. Resolution: check section → descriptor defaults (Bukkit's own
  defaults mechanism is not used). Bad values produce warnings (shown on `/ac reload`) and fall back;
  a file with a YAML syntax error is rejected as a whole and the previous settings stay active
  (bundled defaults on first start).
* `messages.yml`: MiniMessage templates, `%prefix%` + `%placeholder%` (values escaped).

## Concepts and originality
| Module | Concept (from public docs/READMEs) | Implementation |
|---|---|---|
| `latency.MarkerTracker` | acknowledgement packets bracket server state so a late ack cannot cause false positives (double latency compensation) | original: tagged ping ids, primitive ring buffers, sorted task list, forged-ack protection |
| `pipeline.MovementProcessor` + `simulation` contract | simulate possible inputs per tick and score the offset; multiple ticks between packets | original contract; engine follows in phase 2 |
| `check.*` | per-player check instances, buffer + decay, configurable punishments | original structure (capability interfaces, epoch-synced settings, slot-indexed profiler) |
| `module.*` | — | original: module registration surface with typed per-player components |
| `check.impl.world` (`xray`, `freecam`, `esp`) | ore ratios, interaction/look consistency, aim at hidden entities | original: mirror-based "all six neighbours opaque" test, exact voxel DDA with UNKNOWN tri-state, hit-point/ray geometry against the sent rotation |
| regression tests | recorded scenarios replayed through the pipeline; every bug becomes a test | `MovementProcessor.process` takes primitives so scenarios replay without a server |

No code, class structure or distinctive naming was taken from GrimAC (GPL-3.0) or Intave (Polyform
Perimeter). Intave's cloud/ML detection is not replicated; later phases use local statistics.

## Phase plan
1. **Framework** (this phase) — contracts, PlayerData, check framework, violations/alerts, config, commands, ack tracker, game-state/input trackers.
2. **Simulation** — compensated world (chunk/block mirror), effects/attributes/velocity/teleport trackers, multi-tick movement engine, Speed/Fly/NoFall, replay harness.
3. **Movement checks** — Jesus, Step, Spider, Velocity, NoSlow, Elytra, Vehicle, Timer, Phase.
4. **Combat** — entity tracking, Reach, Hitbox, KillAura heuristics, AutoClicker statistics, Criticals, Mace/Wind Charge/Spear lunge.
5. **Packet/World/Inventory** — BadPackets, crash protection, Scaffold, Nuker/FastBreak, FastPlace, GhostHand, FastUse, InventoryMove, ChestStealer.
6. **Persistence & polish** — SQLite/MariaDB log, live debug offsets, README.

---

## Round 5 additions (detection quality and operations)

* **Shadow mode / FP tuning**: every check section accepts `options.log-only: true`. `Check.logOnly()`
  is read by `ViolationDispatcher`, which still publishes the flag, writes the record (details prefixed
  `[shadow] `) and alerts, but skips setbacks and threshold actions. The `PerformanceGovernor` forces the
  same path for all checks while the server is degraded (`shadowOnly()`), and pauses X-Ray prevention
  (`preventionPaused()`); the plugin watchdog (60 s task) drives degrade/recover from TPS and measured
  check cost and logs slow checks once.
* **Per-check telemetry**: `violation.CheckMetrics` counts flags per slot and the distinct players behind
  them (lock-free adder, bounded player set); `/ac metrics [reset]` prints `flags / players (/player)`
  plus the engine state. The same counters are the false-positive signal while a check runs in shadow.
* **Setback confirmations**: `violation.SetbackConfirmations` requires N violations of the same
  (player, check) inside a window before a setback is requested (`setback.confirmations`, default 2,
  `setback.window`, default 4000 ms). Single spikes no longer rubber-band a player.
* **BadPackets** (`check.impl.packet`): pitch outside ±90° or non-finite, movement rate above
  `max-movement-packets-per-second`, placement cursor outside the block box, DIG finish/cancel without a
  matching start, sprint+sneak at once. PACKET category: no game-mode exemption.
* **Timer** (`check.impl.packet.TimerCheck`, id `timer`, PACKET): a client clock advances one tick
  length per client tick (tick-end packet on 1.21.2+, movement packet before; teleport answers and the
  duplicate position+look packet are skipped) and must never overtake `nanoTime`. Each tick in the
  future raises the buffer and is taken back out. The clock is pulled up to
  `MarkerTracker.lastAckedSentAtNanos() - clock-drift-millis` (120 ms), which bounds banked ticks; it is
  anchored only after the first RTT sample (or 40 ticks). `/tick rate` is followed from outbound
  `TICKING_STATE` (faster at once, slower after a marker ack). Stalls without acks count as lag.
* **Triggerbot** (`check.impl.combat.TriggerbotCheck`, id `triggerbot`, COMBAT, experimental): keeps a
  64-tick ring of eye position, look direction and the last attacked player's tracked position. Only a
  *cold* attack (no swing for more than `min-idle-ticks`) is judged, one tick late: the check walks back
  to the last tick whose pick ray (eye of tick n-1, look of tick n) missed every target position inside
  the lag window (`ceil(rtt/50) + 3` ticks, each box grown by `off-margin`). Reaction <=
  `max-reaction-ticks` raises the buffer (x`sweep-weight` when the view keeps turning after the hit),
  slower lowers it, no off tick or missing history gives no sample. `ANIMATION` without an attack, dig
  or block action whose ray passes within 1.5 blocks of the target is a near miss and pays back
  `miss-relief`. Tick-end clients only.
* **Combat module** (`check.impl.combat`): `ReachCheck` (eye to closest hitbox point, 3.1 + 0.1 box
  tolerance), `KillauraCheck` (angle between the sent look vector and the target chest, 75°, needs 3
  aligned samples), `AutoclickerCheck` (CPS above 16 **and** interval stddev below 2 ms over 12+ samples),
  `ThroughWallCheck` (both closest point and center of the hitbox occluded). Targets come from
  `NearbyPlayers` (player entities the server actually sent) and carry invisibility/sneaking metadata;
  passengers, scale and mob hitboxes are not tracked yet.
* **Engine accuracy**: `PredictionSimulator` keeps the seed engine-owned when the per-packet residual is
  below `SEED_RESIDUAL_LIMIT` and chains its own predicted positions (`SimulationResult.cumulativeOffset()`,
  `cumulativeTicks()`), so a constant per-tick excess accumulates instead of being absorbed into the seed.
  Skipped packets, teleports and errors above `MAX_CUMULATIVE_OFFSET` restart the chain. `PredictionCheck`
  flags sustained chains (`cumulative-threshold`, `min-ticks`, experimental).
* **Block coverage**: `BlockProperties` carries `collisionHeight`; `SupportScan` treats a uniform layer of
  low state-independent blocks at the feet (farmland/dirt path 15/16, carpets 1/16) as the floor and uses
  the real top height for every floor layer. State-dependent shapes (slabs, stairs, fences) stay COMPLEX.
* **Replay harness**: `-Dvaplus.record=<file>` appends one `M,<ms>,<x>,<y>,<z>,<yaw>,<pitch>,<ground>` line
  per accepted movement packet (`diagnostics.ReplayRecorder`, closed in `onDisable`); `ReplayFile` parses it
  tolerantly and `testutil.replay.ReplayHarness` feeds the frames through the real pipeline (world blocks
  are not recorded, so replay on flat known terrain). `docs/SMOKE-TEST.md` plus `scripts/smoke-check.sh`
  document and check the live procedure.
