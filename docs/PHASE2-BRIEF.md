# Phase 2 Brief — Simulation (LEAD-Vertrag, Runde 1)

Gültig für: dieses Repository nach abgeschlossenem Phase-1-Review (397 Tests grün,
Messages-Reload prepare/apply-atomar). Bindend für alle Phase-2-Agenten; Abweichungen nur über den LEAD.
Belege: `docs/ARCHITECTURE.md`, `docs/HANDOFF-TODO.md` (Deferred-Liste), `docs/research/version-notes.md`.

## 1. Phase-2-Ziel (laut ARCHITECTURE.md, Phase plan Punkt 2)

**Simulation** — kompensierter Welt-Mirror (Chunk/Block-Mirror), Effects/Attribute/Velocity/Teleport-Tracker,
Multi-Tick-Movement-Engine, Speed/Fly/NoFall, Replay-Harness. Daraus abgeleitet (ARCHITECTURE
"Deferred contract work"):

- Core-Teleport-Queue: outbound `player_position`-Sync mit Relative-Flags, TELEPORT_CONFIRM-Matching,
  Gate im MovementProcessor vor den Hooks, packet-level Setback-API `request(player, target, reason)`,
  die in dieser Queue registriert wird.
- Per-Client-Tick Snapshot-Ring-Buffer + engine-owned Motion-State-Komponente (Handoff M12).
- AttributeState-Basis + Modifier-Listen inkl. 26.2 `friction_modifier`/`air_drag_modifier`/`bounciness`
  (Defaults: friction 1, air_drag 1.0 (0–2048), bounciness 0 — version-notes §3.1/§3.2, [WIKI-Java_Edition_26.2]).
- Per-Server-Tick-Marker (M15), JOIN/RESPAWN-Hold bis PLAYER_LOADED (L11, 1.21.4+).
- Checks: Speed, Fly, NoFall. Replay-Harness: aufgenommene Szenarien laufen durch
  `MovementProcessor.process(primitives…)` ohne Server (ARCHITECTURE "Concepts").

Nicht in Phase 2: Phase-3+-Checks (Jesus, Step, Velocity, NoSlow, Elytra, Vehicle, Timer, Phase, Combat),
EntityTracker (M14, Phase 4), parse-once Packet-Views (M9, Phase 5).

## 2. Runde-1-Board (Status zu Rundenbeginn: offen → am Ende je Status melden)

| Agent | Bereich | Dateien (exklusiv) | Status |
|---|---|---|---|
| A (GPT 6.1 Sol) | world/mirror-Paket + AttributeState | `world/**` (NEU), `player/state/AttributeState.java` | offen |
| B (GPT 6.1 Sol) | Teleport-Queue + Pipeline-Gate + Setback-API + Core-Tracker | `pipeline/TeleportQueue.java` u. neue pipeline-Dateien, Gate in `pipeline/MovementProcessor.java`, `movement/SetbackHandler` request-API, `movement/**`, `platform/TeleportSetbackHandler.java`, `pipeline/CoreTrackingModule.java` | offen |
| C (GLM 5.3 Flash) | Motion-Snapshot-Fundament + Docs | `docs/PHASE2-BRIEF.md` (NEU), `docs/HANDOFF-TODO.md`, `player/state/MotionHistory.java` (+ `TickSnapshot`), `pipeline/MotionTrackingModule.java` (einzige neue pipeline-Datei von C), `VAPlusPlugin.java` (NUR `modules()`-Liste), neue Tests | offen → erledigt in Runde 1 (siehe Abschlussbericht) |

Fremde Dateien NIEMALS anfassen. Bestehende Phase-1-Dateien außer den oben genannten sind tabu;
Messages-Reload (MessageService/VAPlusPlugin) ist inzwischen strikt prepare/apply-atomar — nicht als
"alt" annehmen.

**Runde-1-Status (2026-11-13):** A, B, C erledigt (523 Tests grün).

**Runde 2 (2026-11-13, 2 parallele Agenten) — erledigt, `mvn -B clean verify` 604 Tests grün:**
Agent 1 (`simulation/**`, AttributeState, VelocityState/VelocityTracker, `testutil/replay/**`) lieferte
Punkt 1 + Velocity-Tracker aus Punkt 2 + Replay-Harness aus Punkt 6; Agent 2 (`platform/**`,
`violation/**`, `latency/**`, GameStateTracker, CoreTrackingModule, ExemptionState) lieferte die Punkte
4, 5 und 7. Offen für Runde 3: World-Feeder, Attribute/Effect-Tracker, Checks Speed/Fly/NoFall (Punkt 3)
+ config-Optionen (Rest von Punkt 6) — Details in `docs/HANDOFF-TODO.md`.

**Runde 3 (2026-11-13, 2 parallele Agenten) — erledigt, `mvn -B clean verify` 695 Tests grün:**
Agent A: World-Feeder (`pipeline/WorldTracker`), Attribute/Effect-Tracker, Pending-Skips in der Engine;
Agent B: Checks Speed/Fly/NoFall (`check/impl/movement`) + `config.yml`-Optionen + Replay-Szenarien.
Runde-4-Vorschlag (Engine-Seeding, Client-Placing-Fenster, Block-Abdeckung, Live-Smoke-Test) in
`docs/HANDOFF-TODO.md`.

## 3. Runde-2-Plan (nach Runden-Review durch LEAD)

1. Movement-Engine: `MovementSimulator`-Implementierung über die MotionHistory (M12) +
   `SimulationResult`-Pflege; Ablösung von `InactiveSimulator` (Status `NOT_RUN` → `COMPLETE`/`SKIPPED`).
2. Rest-Tracker: Effects/Attribute-Verdrahtung (A), Velocity-Tracker, Teleport-Tracker via TeleportQueue (B).
3. Checks Speed/Fly/NoFall gegen `SimulationResult`-Offsets (Checks implementieren nie Physik selbst,
   ARCHITECTURE "Movement"-Abschnitt).
4. M15: per-Server-Tick-Marker — Paper `ServerTickEndEvent` bzw. Folia 1-Tick-Entity-Task (Handoff).
5. L11: JOIN/RESPAWN-Exemption-Hold bis PLAYER_LOADED (1.21.4+; Einführungsversion des
   PLAYER_LOADED-Signals in den version-notes nicht belegt — vor Implementierung verifizieren).
6. Replay-Harness + Regression-Tests; config.yml-Zusätze (`checks.<id>.options{}` für die neuen Checks,
   soweit vom Config-Schema getragen).
7. ViolationDispatcher-Umstellung: Setback-Pfad (MOVEMENT, ≥ `setback-vl`) von der Bukkit-Plattform
   auf die packet-level Setback-API (B) — erst wenn die Queue steht.

## 4. Threading-Regeln (bindend, ARCHITECTURE "Threading model")

- Alles Check-/State-/Tracker-Code läuft owner-thread confined auf dem Netty-Event-Loop der Verbindung;
  `PlayerData`-State hat keine volatile-Felder außer den von Command-/Scheduler-Threads gelesenen.
- Kein thread-gebundenes Bukkit-API aus Check-Code: Server-Zustand erreicht den Player-State nur über
  outbound Pakete (Tracker), nie über Bukkit-Getter (ARCHITECTURE-Regel).
- Kick/Setback/Alert über Entity-Scheduler bzw. Global-Region-Scheduler; der async Scheduler ruft
  ausschließlich `ChannelHelper.runInEventLoop`-artiges Scheduling (Heartbeat-Muster).
- `MotionHistory` ist owner-thread confined (Javadoc in der Klasse); `MovementState.worldEpoch` bleibt
  der einzige volatile Ausnahmefall (Region-Thread-Lesezugriff bei Setbacks).

## 5. Offene Risiken (nicht lösen, dokumentieren)

- **PE-2.14-API-Gaps**: PacketEvents 2.14.0 deckt 26.3 ab ([PE-REL]), aber keine Release-Note erwähnt
  player_input / client_tick_end / Positions-Paket-Details (version-notes §2.3, Punkt "No PacketEvents
  release note mentions ..."). API-Lücken gegen 1.21.2+ sind zur Laufzeit zu erwarten → version-gated,
  nicht an der Client-Version vertrauen.
- **1.21.2 Velocity-Flags**: Teleport-Relative-Flags X/Y/Z/Yaw/Pitch/Velocity 0x0001–0x0100 (inkl.
  rotate-velocity 0x0100) sind für 26.3 belegt ([WIKI-PROTO], §2.1); die Einführungsversion des
  Velocity-Feldes (1.21.2) ist **UNCONFIRMED** (version-notes §2.2). TeleportQueue deshalb
  versions tolerant bauen (Flags-Maske pro Protokoll).
- **Folia 26.x unbestätigt** (version-notes §1.2, offene Liste Punkt 3): Folia-Branches vor
  Implementierung von M15 auf Folia prüfen; Folia-Existenz für 26.x ist kein Fakt.
- **Live-Tests ausstehend**: Via/Folia/26.x-Kompatibilität ist nicht durch Unit-Tests belegt
  (ARCHITECTURE "Current limitations"); Smoke-Tests auf Live-Server stehen aus.
- **UNCONFIRMED-Badliste** (version-notes §6): Attribut-Einführungsversionen (außer die in §3.1
  genannten), Mace-/Wind-Charge-/Lunge-Velocity-Zahlen, 26.x-Minecart-/Schwimm-/Sprint-Änderungen.
  Nichts davon als Fakt in Code oder Docs verwenden.

## 6. Modellnotiz (Nutzerregel)

Genau 1 GLM-5.3-Flash + 2 GPT-6.1-Sol-Agenten dürfen parallel im Strict-Mode arbeiten; bei
GLM-Usage-Limit-Fehlern fällt der GLM-Agent auf GPT 6.1 Sol zurück. (Ersetzt die alte
"nur ein Agent"-Regel aus Phase 1.)
