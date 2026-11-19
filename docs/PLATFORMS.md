# Supported server platforms

VA+ is a **Bukkit/Paper plugin**. It compiles against `paper-api 1.21.1-R0.1-SNAPSHOT`, uses
`JavaPlugin`, Bukkit events/entities/schedulers and PacketEvents, and ships a normal `plugin.yml`
(`api-version: '1.21'`, `folia-supported: true`, `depend: [packetevents]`).

## What runs

| Server | Status | Notes |
|---|---|---|
| Paper 1.21 – 26.3 | primary target | `latency.server-tick-markers` uses `ServerTickEndEvent` |
| Purpur | supported | Paper fork, same API; reported as `Purpur` in the enable log |
| Folia | supported | regionised threading; `folia-supported: true`, per-player `EntityScheduler` for kicks/setbacks/alerts, `detectFolia()` via `io.papermc.paper.threadedregions.RegionizedServer` |
| Cardboard/Sinytra, Mohist, Arclight, Youer | best effort | hybrid servers that expose a Bukkit API on Fabric/NeoForge; the jar loads and PacketEvents works, but this combination is not part of the test matrix |
| CraftBukkit/Spigot | should work | no Paper-only API is required for the core pipeline; not tested |

Java: the jar is Java 21 bytecode, so it loads on 1.21.x (Java 21) and on 26.x (Java 25).

At enable the plugin logs the detected platform once:

```
[VA+] Server platform: Paper (git-Paper-196 (MC: 1.21.1))
```

`platform.ServerPlatform` does the detection from `Bukkit.getName()`/`Bukkit.getVersion()` plus the
Folia class check. It is string-based on purpose and adds no dependency on any fork.

## Fabric (native) — not supported by this artifact

A native Fabric mod is **not** what this project is. `paper-api`, `plugin.yml`,
`JavaPlugin#onEnable`, Bukkit events and the Bukkit scheduler do not exist on Fabric, so the same jar
cannot be loaded by a Fabric server. The honest summary:

* **On Fabric with a Bukkit bridge** (Cardboard/Sinytra, Mohist, Arclight): the plugin loads and runs
  as described above. PacketEvents itself has a Fabric platform, so the packet layer is fine there.
* **As a native Fabric mod**: a separate project is required — Gradle + Fabric Loom, a
  `fabric.mod.json`, Mixins into `ServerPlayNetworkHandler`/chunk serialization instead of PacketEvents
  events, Fabric's `ServerTickEvents` instead of the Bukkit scheduler, and its own config/command
  layer. None of that shares code with this Maven/Bukkit build beyond the plain Java packages
  (`world.BlockSight`, `world.mirror`, `check.*` logic could be reused; the `platform/**` and
  `pipeline/**` glue could not).

If native Fabric support is wanted, treat it as a second module/artifact with its own build, not as a
flag in this one.

## Anti-X-Ray prevention and other server-side obfuscation

`checks.xray.options.obfuscate` rewrites outgoing chunk packets. If Paper's own engine-mode anti-xray
(`engine-mode: 1/2`) is enabled for the same world, both systems rewrite the same packets — pick one.
The detection side (`XrayCheck`) always reads the real world mirror, so it keeps working with either
prevention enabled.
