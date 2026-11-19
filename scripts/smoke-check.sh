#!/usr/bin/env bash
# Checks a Paper/Spigot server log after a live smoke test of VA+ (docs/SMOKE-TEST.md).
# Usage: scripts/smoke-check.sh <server-log> [--replay]
# Exit code 0 = all hard checks passed, 1 = a check failed, 2 = bad usage.
set -u

log="${1:-}"
if [ -z "$log" ] || [ ! -f "$log" ]; then
    echo "usage: $0 <server-log> [--replay]"
    exit 2
fi

status=0
pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; status=1; }
info() { echo "INFO  $1"; }

if grep -q "Server platform:" "$log"; then
    pass "platform detection logged ($(grep -m1 'Server platform:' "$log" | sed 's/.*Server platform: //'))"
else
    fail "no 'Server platform:' line - plugin did not enable"
fi

if grep -qE "Enabled with [0-9]+ checks" "$log"; then
    pass "check registry logged ($(grep -m1 -oE 'Enabled with [0-9]+ checks' "$log"))"
else
    fail "no 'Enabled with N checks' line"
fi

if grep -q "PacketEvents is not available" "$log"; then
    fail "PacketEvents missing - the plugin disabled itself"
fi

if grep -qE "Check '[^']+' (failed|threw)" "$log"; then
    fail "check exceptions reported: $(grep -m3 -E "Check '[^']+' (failed|threw)" "$log" | tr '\n' ' ')"
else
    pass "no check exceptions reported"
fi

if grep -q "Performance governor degraded" "$log"; then
    info "governor degraded during the run (expected only under load; inspect the reason)"
fi

if grep -q "averages .* ns/call" "$log"; then
    info "slow-check warnings present: $(grep -m2 'averages .* ns/call' "$log" | tr '\n' ' ')"
fi

if grep -q "\[config\]" "$log"; then
    info "config warnings present: $(grep -m2 '\[config\]' "$log" | tr '\n' ' ')"
fi

if [ "${2:-}" = "--replay" ]; then
    if grep -q "replay recording to" "$log"; then
        pass "replay recording started: $(grep -m1 'replay recording to' "$log" | sed 's/.*replay recording to //')"
    else
        fail "-Dvaplus.record was expected but no recording line was logged"
    fi
fi

exit "$status"
