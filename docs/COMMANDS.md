# Commands and Permissions

The main command is `/ac`, with the aliases `/vaplus` and `/anticheat`. Tab completion suggests
sub-commands, player names and check ids.

## Commands

| Command | Permission | Description |
|---|---|---|
| `/ac alerts` | `ac.alerts` | Toggle alerts for yourself |
| `/ac info <player>` | `ac.alerts` | Client version, ping and the player's current violation levels |
| `/ac logs <player> [page]` | `ac.alerts` | Violation history of a player |
| `/ac profile [reset]` | `ac.admin` | CPU cost per check (needs `profiling.enabled`) |
| `/ac metrics [reset]` | `ac.admin` | Flags and flagged players per check, engine state |
| `/ac debug <check\|all> [player]` | `ac.admin` | Live reasoning of a check for one player |
| `/ac reload` | `ac.admin` | Reload `config.yml` and `messages.yml` |

### Tips

- **False positive?** `/ac debug <check> <player>` shows why a check raises its buffer. Combine it
  with `options.log-only: true` while you tune the check.
- **Is a new check trustworthy?** `/ac metrics` lists how often each check flags and how many
  different players. A check that flags many regular players needs tuning.
- **Lag on the server?** `/ac profile` shows which checks cost the most time.

## Permissions

| Permission | Default | Description |
|---|---|---|
| `ac.alerts` | op | Receive and toggle alerts, view player info and violation logs |
| `ac.admin` | op | Reload, profile, metrics and debug; includes `ac.alerts` |
| `ac.bypass` | false | The player is exempt from all checks |
| `ac.healthindicator.bypass` | false | The player receives real health, equipment and game modes of others |
