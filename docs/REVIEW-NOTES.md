# Phase 1 review notes (GLM Flash independent review, 2026-11-12)

## Threading evidence: outbound PacketEvents events run on the connection's event loop

Question: `PlayerData.claimOwnerThread()` is called only from inbound packet processing, while
`MarkerTracker` is not thread-safe and outbound brackets (`GameStateTracker.onSend` →
`acks().bracket(event.getTasksAfterSend(), …)`) mutate owner-thread-confined state. Is the
owner-thread assumption for outbound listeners provable?

Answer: yes, from the exactly installed dependencies (no source-jar needed; bytecode inspected with
`javap -c`). The chain:

1. **Inbound**: `io.github.retrooper.packetevents.injector.handlers.PacketEventsDecoder`
   (`packetevents-spigot 2.14.0`) extends Netty's `MessageToMessageDecoder<ByteBuf>`; `channelRead`
   is always dispatched by Netty on the channel's event loop.
2. **Outbound**: `io.github.retrooper.packetevents.injector.handlers.PacketEventsEncoder`
   (`packetevents-spigot 2.14.0`) extends `io.netty.channel.ChannelOutboundHandlerAdapter`. Its
   `write(ctx, msg, promise)` calls `handleClientBoundPacket(...)` →
   `com.github.retrooper.packetevents.util.PacketEventsImplHelper.handleClientBoundPacket`
   (`packetevents-api 2.14.0`), which calls
   `PacketEvents.getAPI().getEventManager().callEvent(event, postTask)` and
   `com.github.retrooper.packetevents.event.EventManager.callEvent(PacketEvent, Runnable)` invokes
   every listener **synchronously on the calling thread** (plain loop over
   `PacketEvent.call(listener)`, no executor hop).
3. **But the calling thread of the encoder's `write` is always the channel's event loop**:
   `io.netty.channel.AbstractChannelHandlerContext#write(Object, boolean, ChannelPromise)`
   (`io.netty:netty-transport:4.1.72.Final`, the version this project's compile classpath resolves via
   `packetevents-netty-common 2.14.0 → netty-all 4.1.72.Final`, provided scope) looks up the next
   outbound handler's `executor()` and, when `!executor.inEventLoop()`, schedules the write as a
   `WriteTask` via `safeExecute(executor, …)` onto that executor instead of running inline. Netty
   therefore executes outbound handler methods on the channel's event loop regardless of which thread
   (e.g. a region tick thread) called `writeAndFlush`.
4. **Handler→executor binding**: `SpigotChannelInjector` (`packetevents-spigot 2.14.0`) adds the
   handlers with `ChannelPipeline.addAfter(String, String, ChannelHandler)` — the overload without an
   `EventExecutorGroup`, so the handler inherits the channel's own event loop.
5. **`tasksAfterSend`** (our bracket's second marker): `PacketEventsEncoder.lambda$handleClientBoundPacket$0`
   runs `event.getTasksAfterSend()` inside a `ChannelPromise.addListener(...)` callback; socket-write
   promises complete on the event loop, so these tasks also run on the owner thread.

Consequence for the code: `claimOwnerThread()` from inbound is sufficient in practice — every
connection has inbound packets before any tracked state is touched — and outbound events, including
`bracket`'s after-send tasks, run on the same event loop. No routing change was made (changing the
routing could reorder bracketing). Residual caveat, unchanged: this is bytecode-level evidence for
PacketEvents 2.14.0 + Netty 4.1.72, not a live-server test; a PacketEvents upgrade should re-check
this assumption (e.g. a debug assertion `player.isOwnerThread()` in an outbound tracker during
development).

## Wire-id replay window (documented limitation, see MarkerTrackerTest.wireIdWrapAliasesStalePong)

Echo ids are matched only against currently pending markers — a client cannot force-ack a marker it
has never received. But the 14-bit wire-id range is reused after 16384 sent markers: a recorded pong
from a previous rotation aliases the currently pending marker with the same low bits, and the ids are
not authenticated (a pending id is guessable in principle). Not detectable as an anomaly by design;
the ack timeout bounds how long any marker stays pending. Documented in ARCHITECTURE.md and the
`MarkerTracker` javadoc; regression test added, semantics unchanged.

## Messages.yml strict loading (review finding H3, fixed)

`MessageService` previously used `YamlConfiguration.loadConfiguration(file)`, which swallows YAML
syntax errors → every override silently lost. Additionally `VAPlusPlugin.reloadConfiguration()`
loaded messages *before* config validation while its javadoc claimed "a broken file changes nothing".
Fixed: `MessageService.prepare()` (strict parse, throws `LoadException`, keeps the live catalogue) +
`MessageService.apply()` (commit); `reloadConfiguration()` now prepares both files first and commits
them together (atomic: a broken config.yml no longer commits a message reload and vice versa).
Regression tests: `MessageServiceTest` (corrupt user file, corrupt bundled defaults, valid overrides
+ default fallback, bundled-only) — no Bukkit server needed.
