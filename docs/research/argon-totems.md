# Argon totem checks

Reference revisions inspected:

- [Argon](https://github.com/LvStrnggg/argon/tree/738e75f0623581ffbbc116656835352f8df9eb22)
- [TotemGuard](https://github.com/Bram1903/TotemGuard/tree/48655b552acd33db01b0f60056fa2e96abacc5a5)

The implementation is original. TotemGuard AutoTotemB supplies the conceptual reference for
buffered refill-delay consistency. VAPlus uses explicit client ticks on newer clients; the legacy
fallback measures monotonic elapsed time in 50ms units with RTT/freshness and burst guards.

| Argon module | Observed source behavior | VAPlus signals |
|---|---|---|
| Hover Totem | Inventory SWAP into a hotbar slot, then button 40 for offhand when hovering a totem; tick delay 0–20 | Rapid dual-hand pairs at low delay; consistent post-pop offhand refills |
| Auto Inventory Totem | Inventory SWAP button 40, then selected hotbar slot; optional random source and fake inventory auto-open | Same pair/consistency signals; random source slots do not change the input-method sample group |
| Totem Offhand | Select totem hotbar slot, wait equip delay, SWAP_ITEM_WITH_OFFHAND, optional switch back | Known hotbar-totem to offhand transition; consistent post-pop delays |

`autoarmor` still checks fast offhand actions after an acknowledged own pop. `autototem` adds
different evidence and does not independently score fast reaction time again. Both are heuristics,
not client/module identification. A legal F/number-key press produces the same packets as Argon.

## Safety and limitations

- Only a known non-totem -> totem transition counts. Server grants do not count. Modern hashed
  changed-slot reports are supported, but client reports are not authoritative validation.
- Own entity status 35 is tracked behind a marker acknowledgement. Actions before that acknowledgement
  are not judged. Late callbacks cannot resurrect evidence after reset or container changes.
- Server inventory snapshots/slot updates invalidate pending state immediately; revisions prevent
  old acknowledgements from overwriting newer client evidence. Only ten hand slots are retained.
- Arbitrary containers, missing item reports, unknown selection and unsupported layouts suppress
  detection. The inventory view is intentionally not a complete inventory simulator.
- With **observed** CLIENT_TICK_END packets, timing uses actual client ticks. Older clients can
  suppress movement packets while an inventory GUI is open, so those inferred ticks are not used.
  Argon's inspected main branch targets Minecraft 1.21.1: its fallback uses monotonic time, requires
  a recent acknowledgement and RTT <=150ms, and excludes sub-10ms intervals. This reduces but does
  not eliminate sensitivity to network jitter. Disable `check-legacy-timing` to opt out.
- Mouse hover and opening window 0 are not directly observable. No claim of guaranteed detection
  for every delay/configuration: isolated refills, randomized delays and missed acknowledgements
  can evade these signals. Low variance also occurs in practiced manual play.
- `autototem.options.log-only: true` is the shipped default: alerts/logs, no punishment. Validate
  against real captures before disabling shadow mode. This does not change AutoArmor's settings.

## Live validation (not executed here)

1. Enable `/ac debug autototem <player>` and use shadow mode.
2. On a tick-end capable client, exercise both SWAP orders (hotbar -> offhand and reverse) at delay 0.
   Four qualifying pairs exceed the default buffer.
3. Pop and refill with the same input method eight times at a fixed delay <=20 ticks. With six
   samples, three overlapping consistent windows exceed the default buffer.
4. Repeat with variable manual delays, ordinary F swaps, number-key sorting, shields, server-granted
   totems, containers, creative mode and induced network jitter. Collect `/ac logs` for FP tuning.
5. Test 1.21.1 separately: expect legacy timing only with recent low-RTT acknowledgements. Repeat
   with high ping and bunched packets: legacy evidence must be suppressed. Still collect real captures
   to assess jitter-related false positives; synthetic tests cannot establish real-client accuracy.
