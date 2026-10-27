# RWX CLI

> Full English version of [`help_zh.md`](help_zh.md). **`help_zh.md` is the authoritative document**;
> this file is synced from it in the final stage of a change, so it can lag behind while text is still moving.
> `rwx-help` displays this English manual.

This page can be viewed with `rwx-help`.

- `rwx-help`: show the complete documentation.
- `rwx-help <section-number>...`: show the given heading and everything under it, including the heading itself. e.g. `rwx-help 5.5` shows only 5.5, `rwx-help 5` shows all of chapter 5. Several numbers may be given; output follows document order and overlapping content is shown only once.
- `rwx-help [--session|--observe|--combat|--develop|--automation|--history|--reference|--system]`: keeps the original eight module filters; the English names apply to these h2 headings only, and multiple modules can be combined. Number filters and module filters are mutually exclusive — when numbers are given only numbers are used, e.g. `rwx-help --combat 5.5` shows only 5.5.
- `rwx-help --list [depth]`: list headings and numbers only, one per line, depth-first, with subheadings indented. `depth` is a positive integer, default 1 (h2 only); 2 includes h3, 3 includes h4, and so on with no maximum. It can be combined with filters, e.g. `rwx-help 5 --list 2`; depth always starts at h2.

## 1. Session — Match management

Manages the lifecycle and runtime environment of a match.

### 1.1 Battle room

> These commands can only be used while a **match has not started**.

- `rwx-skirmish`: enter the local battle room.
- `rwx-battleroom-show [--player] [--currentMap] [--enabledMap] [--settings] [--chatHistory]`: show room information; with no parameters it shows players and the current map. Each switch shows the player list, the current map, selectable maps, room settings, or the chat history.
- `rwx-battleroom-add-ai [--difficulty <very-easy|easy|medium|hard|very-hard|impossible|custom AI|id|number>]`: add an AI in the first empty slot, default Hard (1). Accepts the original six English names and the numbers `-2..3`, plus custom AI display names in Chinese or English (e.g. `AI-high`, `AI_S`, `AI-hard-1income`, `AI-非常困难-1倍收入`). Case, spaces, `_` and `-` are insignificant.
- `rwx-battleroom-show-ai`: list every AI that can be added, with number, display name (Chinese/English) and stable id; usable outside the battle room too.
- `rwx-battleroom-set-player --player/--name <spawn point or name> (--move <spawn point> | --switch <A–J|1–10|spec> | --kick | --difficulty <AI>)`: adjust a player's position, faction or AI difficulty.
- `rwx-battleroom-select-map --name <map name>`: select by display name, file name or asset path; when nothing matches, close candidates are given instead of auto-selecting one.
- `rwx-battleroom-settings <options...>`: change room settings; at least one option is required, read the current settings with `rwx-battleroom-show --settings`, and only the host can change them before the match starts.
- `rwx-battleroom-chat <content>`: send a chat message.
- `rwx-battleroom-start [--record]`: start the match; `--record` records a complete replay from tick 0.
- `rwx-battleroom-quit`: leave the battle room; refused while a match is loaded.

Room setting options:

- `--income <0.1..100>`: income multiplier, e.g. `2.5`.
- `--credits <default|0|1000|2000|4000|5000|10000|50000|100000|200000>`: starting credits.
- `--fog <none|basic|los|0|1|2>`: fog mode.
- `--startUnits <1..5>`: starting-unit tier.
- `--aiDifficulty <AI>`: default AI difficulty, same values as `--difficulty`.
- `--maxPlayers <10..100>`: maximum number of players.
- `--teamLayout <2|3|ffa|spectators|random|allvsai|allvs2>`: one-shot team assignment; `ffa` renumbers teamIds, so the host spawn point may change.
- `--revealedMap` / `--noNukes` / `--sharedControl` / `--allowSpectators` / `--teamLock` / `--roomLock` / `--fixedAllyTeams <true|false>`: boolean switches.

### 1.2 Match lifecycle

- `rwx-game-restart`: reload the current map within the same process and return to tick 0; keeps the current room settings (starting credits, starting units, fog, income, …) as well as player spawn points, factions, colours and AI difficulty overrides, and clears the previous match's surrender, defeat and AI battle state.
- `rwx-game-surrender`: submits a replayable surrender command in a local match, effective on the next simulation tick; networked matches still use the original surrender vote. A CLI surrender also turns micro-management off and clears keep rules and pending orders.
- `rwx-game-pause --value <true|false>`: pause/resume; resuming keeps the speed from before the pause.
- `rwx-game-speed --value <1..64>`: set the speed; pausing must go through `rwx-game-pause`.
- `rwx-game-step [--seconds 5] [--timeout-ms N]`: advance the simulation by game time and then pause; `--seconds` is capped at 1800.

Local time control, `step` and single-player `restart` cannot be used in multiplayer; multiplayer commands must be left to the host's scheduling and the original lockstep synchronisation.

### 1.3 Multiplayer connection, hosting and process exit

```text
rwx-connect <host[:port]|RELAY-CN|connect-descriptor> [--name NAME] [--password VALUE|--password-env ENV_NAME] [--room CODE] [--timeout-ms 20000]
rwx-connect --reconnect true
rwx-connection-status
rwx-disconnect
rwx-host [--port 5123] [--name NAME] [--map "Lake (2p)"] [--password VALUE|--password-env ENV_NAME] [--public false] [--spectator false] [--auto-start-players 0] [--managed-room false]
rwx-server-list [--refresh true]
rwx-exit
```

- **Connecting**: `rwx-connect` establishes the connection asynchronously, defaulting to TCP port `5123`; it accepts original direct addresses, `host:port/room-code`, short relay codes and `get|...` master-server descriptors. `--room` appends the original query string for direct relays and cannot be combined with `get|...`. Player names are 2..20 characters.
- **Public relay**: `RELAY-CN` is looked up by publisher/room identifier in the public master-server list, with no hard-coded address. An unreachable list, no match or a failed handshake are all reported; an entry name alone does not guarantee that the service works. For creating or joining, follow what the entry itself reports.
- **Connection state**: a connection receipt only means the request was submitted. `joined` in `rwx-connection-status` means the room configuration was received and a local team assigned; `in-game` additionally requires the game to be loaded. The other states are `resolving`, `connecting`, `hosting`, `disconnected` and `error`; the port, team and error are shown too.
- **Timeout and password**: the connection timeout covers TCP connect and room registration, range 1000..120000 ms; on timeout the attempt is cancelled and the socket closed, including connections that arrive late. The password can be supplied with `--password` or through the RWX process environment variable named by `--password-env`; a wrong password is not retried indefinitely.
- **Reconnect**: `--reconnect true` uses the last address and the same client identity; whether the original team can be restored is up to the host, and a match already in progress normally refuses new players. Multiple agents must use their own `--data-dir` and must not share one persistent client identity.
- **Disconnect and exit**: `rwx-disconnect` returns to the game menu but keeps the RWX process and the local CLI; closing a Python/PowerShell CLI client does not exit the game either. `rwx-exit` closes the process and its CLI.
- **Hosting**: `rwx-host` listens on the given TCP and reliable-UDP game ports and does not publish publicly by default. `--spectator true` makes the server's local player a spectator; `--auto-start-players 2` authorises the host to start automatically once at least two non-spectator human clients are present, and `0` disables it; AI does not count towards the threshold.
- **Managed room**: `--managed-room true` implies a server spectator seat and is mutually exclusive with starting by player count. The first client receives the original proxy-host authority; after it leaves, authority passes on in join order, elected in the lobby only and frozen during a match. Proxy management requests go through original chat packets for the server to validate and execute; a CLI receipt only means the request was submitted, so query the room to confirm the result.
- **Seats and ending in a managed match**: a new identity joining a match already in progress becomes a spectator automatically, and a reconnecting original player keeps their seat. Once every online non-spectator player has left or been defeated, the server forces the match to end through original packets and returns to the lobby, keeping online clients and assigning no winner; spectators and AI do not block the end. A proxy host cannot control other teams' units.
- **Permissions**: `rwx-connection-status` includes `roomAdmin` (this client's proxy rights), `roomManager` (the admin name when queried locally on the server) and `spectator`; an ordinary client can only judge its rights from the original room state and the proxy-authority bit. An ordinary player can view the room, chat, adjust their own spawn point/faction under the original rules, and in a match view visible information and control their own units; only the host or the original proxy host can change the room, map or AI, or start the match. Agents have no extra privileges; their commands go through the original `Command` network queue.

Example (local RWX connecting to a cloud server's game port):

```text
rwx-connect <server>:5123 --name AgentA
rwx-connection-status
rwx-game-state --summary
rwx-game-unit-list --owner self --full --compact
```

## 2. Observe — Information and statistics

Queries the current game state and summary statistics.

### 2.1 Match state

`rwx-game-state [--summary] [--json] [--units-acting] [--units-damaged] [--building-acting] [--building-actionable] [--event-seconds N] [--event-types type1,type2,...] [--event-allies] [--no-events]`

By default it shows, in order, the battle situation, the economy, our/allied/visible enemy strength, production, upgrades, our health buckets, CLI pending items, units and buildings that need attention, and notable events of the last 30 game seconds; an empty block shows `none`. `--summary` keeps the macro statistics and events and hides per-entity detail, and is mutually exclusive with the four entity filter switches below:

- `--units-acting`: non-building units that are moving, engaging, building, producing, repairing, patrolling, guarding, following, and so on.
- `--units-damaged`: non-building units with HP <70%.
- `--building-acting`: buildings that are constructing, producing, upgrading, repairing, attacking or defending.
- `--building-actionable`: buildings that could upgrade or produce right now, with credits and conditions satisfied.

All four are selected by default; an explicit selection is unioned and de-duplicated, and only affects entity detail, never the statistics. Activity comes from engine state; detail includes ID, coordinates, HP, current order, target and waypoints, and buildings add their tech level and a production-queue summary.

- `--event-seconds N`: event window, default 30 game seconds, range (0, 180].
- `--event-types`: comma-separated, default `damaged,destroyed,losses,new`; `info` must be named explicitly.
- `--event-allies`: `destroyed` and `losses` only show allies; other events keep their rules (see 6.1).
- `--no-events`: do not query or show events, and do not clear history; cannot be combined with other event parameters.
- `--json`: emit standard JSON sharing the report data with the text output.

JSON fields:

- Top level: `battle`, `economy`, `forces`, `production`, `upgrades`, `healthStatus`, `pending`, `notableUnits`, `notableBuildings`, `events`. With `--summary` the two entity arrays are empty; with `--no-events` `events` is omitted.
- `battle`: `tick`, `time`, `gameTimeMillis`, `paused`, `speed`, `map`, `controlledTeam`, `outcome`; `economy`: `credits`, `incomePerSecond`.
- `forces.self` / `allies` / `visibleEnemies`: `unitCount` (excluding buildings), `buildingCount`, `typeCounts` (each with `type`, `count`). Self and allies also carry `idleUnitCount`; allies exclude our own forces, enemies are only currently visible entities, and production queues do not count towards strength.
- `production`: `factoryCount` is the number of our completed buildings that can produce units; `busyFactoryCount` / `idleFactoryCount` count by whether the queue is empty (an upgrade counts as busy); `productionQueueEntryCount` counts production only, not upgrades.
- `upgrades`: `id`, `type`, `upgrade`, `progress` (0..1, null when unknown).
- `healthStatus.units` / `buildings`: per-type counts of `criticalCount` (HP <10%), `lowHealthCount` (10% ≤ HP <50%), `injuredCount` (50% ≤ HP ≤80%).
- `pending`: `pendingOrders`, `waitingForCreditsCount`, `waitingForUpgradeCount`; null when pending queries are unsupported.
- `events`: `windowSeconds`, `items` (each with `gameTimeMillis`, `type`, `description`). Events use game time and querying does not consume history.

```text
rwx-game-state --summary --json
rwx-game-state --units-damaged --building-acting --event-seconds 60
```

- `rwx-game-team-list`: list all teams, factions, AI, active/defeated state, this team's credits and net income; other teams' economy is not exposed.

### 2.2 Unit list and detail

- `rwx-game-unit-list [--owner self|ally|enemy|neutral|any] [--type <id or name,...>] [--group <name>] [--units <ids|selector>] [--x X --y Y --radius R] [--hp <percentage>|--full] [--idle] [--exclude-builders] [--exclude <id|name,...>] [--summary] [--delta] [--compact] [--json]`: list units and buildings with ID, world coordinates, HP, orders and queues. By default only matching entities with HP <80% are listed, and enemy entities are returned only while currently visible.
- `rwx-game-unit-show --id <id>`: detail for a single entity, including faction, T level, upgrade level, attack mode, production queue, the full waypoint queue and orders held back by the CLI in `cliPending`.
- `rwx-game-unit-actions --unit <id> [--json]`: show action id, price, whether it can be issued, queued count and the reason it is unavailable. `queued` includes the action being produced; `available` means at least one is affordable right now.

`unit-list` parameters:

- `--owner self|ally|enemy|neutral|any`: ownership; by default only our own and currently visible enemy entities, excluding allies and neutral ones. `ally` means allies only; `any` means every observable faction. The default change baseline uses the same faction scope.
- `--type <id or name,...>`: type filter; internal ids are matched exactly, otherwise localised names are matched and partial names work. This is the only type-filter entry point.
- `--units <ids|selector>` / `--group <name>`: filter by ID, group or area; selectors are described in 3.4.
- `--x X --y Y --radius R`: circular area in world coordinates; the centre must be given as a pair and the radius must be non-negative.
- `--hp N`: keep only entities below N% HP; `--full`: drop the default HP filter and list every match.
- `--idle`: keep only entities with no current waypoint and an empty production queue; `--exclude-builders` / `--exclude`: exclude builders, or the given units and types.
- `--summary`: count matches only; cannot be combined with `--hp`, `--full`, `--compact`, `--delta` or `--json`.
- `--compact`: changes the text format only; `--delta`: reports additions, departures, HP drops and tech-level increases against the previous `--delta` query and advances the baseline. An ordinary query shows no change section.
- `--json`: read-only, does not advance the `--delta` baseline; mutually exclusive with `--delta`.

### 2.3 Map and terrain information

- `rwx-game-map-show [--spawns] [--terrain ...]`: map size and tile size; by default it lists the building centre and real occupancy state of every resource pool, unaffected by fog. Buildings under construction count as occupied, destroyed buildings do not; occupied entries additionally list faction, type and building ID. `--spawns` additionally lists each faction's known command centre or first-seen position; enemy bases that have not been scouted remain unknown/approximate.
- `rwx-game-map-path --unit <id> --x X --y Y`: queries static-terrain reachability from that unit to the target point, straight-line blocking, path length and detour ratio.

Window-size parameters of `rwx-game-map-show --terrain`:

- `--x/--y` is the world-coordinate centre; when omitted, the map centre is used.
- `--half-width W --half-height H`: must be given together, the number of tiles left/right and up/down respectively (1..40); the size is `(2W+1) × (2H+1)` tiles, default `W=H=8`, i.e. 17×17 tiles. Rectangles only; areas outside the map are clipped with a notice.
- The default output is classified numbers plus a legend. Tiles actually occupied by buildings are overlaid as: our own `#buildingID`, all allies uniformly `4`, all enemies uniformly `-4`, without distinguishing building types; buildings under construction are overlaid too, destroyed ones are excluded, and enemy buildings are shown only while currently visible. Each cell is aligned to the widest marker in the current window, including negative numbers and `#ID`.
- `--ascii`: switch to a raw terrain/resource character map with a character legend, disabling building-ID overlays; one space between cells, requires `--terrain`.
- `--fog`: cells not currently visible in the terrain map are output uniformly as `-2` (character `?`), including unexplored cells and explored cells outside vision; no masking when game fog is off. This mask covers resource markers in the grid but does not hide occupancy information in the resource-pool list.
- For `range`, the right/bottom edge of world coordinates is the outer edge of the area while the right/bottom edge of tile coordinates is the last tile number; `scale` is computed from the actual clipped extent.

| Class | Default number | `--ascii` character |
|---|---:|---:|
| Land, land bridges | 1 | `.` |
| Water | -1 | `~` |
| Mountain, cliff, obstacle, empty cell | -7 | `^` |
| Lava | -9 | `*` |
| Unoccupied resource pool | 0 | `0` |
| Occupied resource pool | 8 | `8` |
| Fog (only with `--fog`) | -2 | `?` |
| Our buildings | `#ID` | no overlay |
| Allied buildings | 4 | no overlay |
| Visible enemy buildings | -4 | no overlay |

### 2.4 Enemy intelligence and camera

- `rwx-game-threat-list --x X --y Y [--radius 300] [--cluster [--cluster-radius 120]] [--json]`: enemy units inside a non-fogged area, with totals, unit-type/attack-capability statistics, nearest distance and per-unit detail; `--cluster` merges them into clusters and `--cluster-radius` sets the merge radius.
- `rwx-game-camera [--x X --y Y] [--center|--top-left] [--zoom Z | --zoom-by factor]`: reads or moves the GUI viewport without pausing the game. `--x/--y` centre by default and `--top-left` switches to the top-left corner; `--zoom` is absolute and `--zoom-by` is relative to the current zoom; out-of-range values are clamped and the adjustment is reported.

Threat radius, distance and range use world units; `nearestDistance` is relative to the query center. `antiAir`, `antiGround` and `antiSubmerged` count armed entities with the corresponding engine attack capability; they do not imply an available target, range or ability to fire now. `air` follows the engine flying state, `buildings` follows the unit type, and `ground` means non-flying, non-building entities; no separate submerged classification is provided.

## 3. Combat — Combat command

Controls the actions and combat organisation of military units.

### 3.1 Stance and pushes

- `rwx-game-order-attack-mode [--units <ids|selector>] --mode <outOfRange|onlyInRange|returnFire|holdFire|guardArea|aggressive|mixed> [--apply-to-new]`: set attack stance. `returnFire` only returns fire, `holdFire` never fires on its own, `guardArea` guards an area, `aggressive` actively pursues, `outOfRange`/`onlyInRange` constrain by range, `mixed` mixes them.
- `rwx-game-order-attack-mode --apply-to-new --mode off`: clear the default stance for new units.
- `rwx-game-order-push [--units <ids|selector>|--group <name>] --x X --y Y [--rally-x X --rally-y Y] [--idle] [--min-count 1..200] [--type ...] [--exclude ...] [--hp ...] [--exclude-builders]`: rally first, then push; with rally coordinates it issues `move` first and then queues `attack-move`, otherwise it issues `attack-move` directly. Refused when there are fewer units than `--min-count`.

### 3.2 Groups

- `rwx-game-group-save --name <name> --units <ids|selector> [--type ...] [--exclude ...] [--hp ...] [--idle] [--exclude-builders]`: save a group; the name is 1–32 characters of letters/digits/`_`/`-`/`.`.
- `rwx-game-group-list`: list groups with alive/saved counts and type composition.
- `rwx-game-group-show --name <name>`: show a group's alive type composition and casualty count.
- `rwx-game-group-delete --name <name>`: delete a group.

A group can be used directly as `--units group:<name>`; dead units are skipped and the receipt says `skipped dead/missing units`. Changing the map or restarting clears them; at most 64 groups of up to 500 units each.

### 3.3 Movement and engagement

- `rwx-game-order-move --units <ids|selector> --x X --y Y [--queue]`: move directly without seeking enemies.
- `rwx-game-order-attack-move --units <ids|selector> --x X --y Y [--queue]`: advance while engaging, continuing once the threat is cleared.
- `rwx-game-order-attack --units <ids|selector> --target <id> [--queue]`: pursue a specific target; when you only have coordinates use `rwx-game-order-attack-move` instead.
- `rwx-game-order-stop --units <ids|selector>`: clear waypoints and the attack target.
- `rwx-game-order-patrol --units <ids|selector> --x X --y Y [--queue]`: patrol between the current position and the target point.
- `rwx-game-order-guard --units <ids|selector> --target <unitId> [--queue]`: protect an ally, attacking whoever attacks it and repairing when needed.
- `rwx-game-order-guard-at --units <ids|selector> --x X --y Y [--queue]`: hold an area and return when pulled away.
- `rwx-game-order-follow --units <ids|selector> --target <unitId> [--queue]`: pure follow, without avenging the target specifically.
- `rwx-game-order-load-into --units <ids|selector> --target <transportId> [--queue]`: load into a transport.
- `rwx-game-order-load-up --units <transportIds|selector> --target <unitId> [--queue]`: have the transport load the target.

### 3.4 Complex parameter: how `--units` selection works

`--units` accepts the following forms:

- `id1,id2,...`: explicit unit ids.
- `all-army`: all controllable non-building, non-builder units.
- `all-units`: all controllable units, buildings included.
- `group:<name>`: a group saved by `group-save`.
- `area:<x>,<y>[,<radius in tiles>]`: circular area centred on world coordinates, default radius 2 tiles, selecting only our non-building units.
- `area:unit:<id>[,<radius in tiles>]`: centred on one of our units.
- `rect:<x>,<y>[,<H/2>[,<W/2>]]`: axis-aligned rectangle, default half-height/half-width 1 tile each.
- `rect:unit:<id>[,<H/2>[,<W/2>]]`: rectangle centred on one of our units.

Composable filter parameters:

- `--type <id or name,...>`: keep only those types; internal ids or partial Chinese names are accepted.
- `--exclude <id|name,...>`: numbers exclude by id, names exclude by type; both may be mixed.
- `--hp <percentage>`: keep only units/buildings below that percentage of HP.
- `--idle`: only units with no waypoint and an empty factory queue.
- `--exclude-builders`: exclude builders.

Area selectors only take our own, commandable, non-building units; area radius/half-width are tiles and centre coordinates are world coordinates. Type filtering always goes through `--type`; the old `--units type:...` form has been removed.

## 4. Develop — Base development

Directly changes buildings, production, construction and upgrade state.

### 4.1 Building placement

`rwx-game-map-place --builder <id> --type <typeId> --x X --y Y [--radius 0..10]` only checks whether a building could be placed and does not issue a build order. `--radius` is the search range for the nearest placeable point in tiles, default 2; `0` checks the origin only. Checks match `order-build`: whether that builder can build this building, whether the terrain allows it (naval yards and similar need their whole footprint on water), and whether the builder can reach it; when it cannot be placed, the footprint positions that fall on land are reported.

### 4.2 Construction, production and upgrades

- `rwx-game-order-build --builder <id> --type <typeId> --x X --y Y [--queue] [--count 1..20] [--auto-place] [--radius 0..10] [--allow-unaffordable]`: have a builder construct; `--count` queues several at once, and `--auto-place` picks the nearest placeable point within `--radius` (default 2 tiles) when blocked.
- `rwx-game-order-action --units <ids|selector> --action <actionId> [--target <id> | --x X --y Y] [--count 1..20] [--allow-unaffordable] [--after-upgrade]`: perform a production/upgrade/ability action.
- `rwx-game-order-cancel --unit <factory id> [--action <actionId>] [--count N | --all]`: cancel production/upgrades from the original queue; a refund that has not been executed yet is shown as unsettled.
- `rwx-game-order-assist --units <builder ids|selector> --target <building under construction id> [--queue]`: assist construction; assisting a complete factory's production queue is not supported.
- `rwx-game-order-rally --units <factoryIds|selector> --x X --y Y`: set the factory rally point.
- `rwx-game-order-repair --units <ids|selector> --target <id> [--queue]`: repair allies.
- `rwx-game-order-reclaim --units <ids|selector> --target <id> [--queue] [--force]`: reclaim; reclaiming your own units requires `--force`.
- `rwx-game-nuke-status --unit <silo id> [--x X --y Y]`: stock, queue, cooldown, range, blast radius and impact-area intelligence.

### 4.3 Pending and deferred execution parameters

`--allow-unaffordable`: do not fail when unaffordable — the order enters the pending queue, is dispatched by the sampler once credits suffice, and is still written to the replay. Applies to production, upgrades and construction (an upgrade action also becomes pending when short of credits instead of erroring).

`--after-upgrade`: when the factory is upgrading and the next-level action does not exist yet, dispatch automatically after the upgrade completes; implies `--allow-unaffordable`.

## 5. Automation — Automatic control

**Manual orders take priority over every automation rule.** Issuing a move, attack, `attack-move`, stop, patrol, guard, queued task, action or stance command to the selected units withdraws their not-yet-executed micro orders and keeps returning control; an old rule does not come back because of the next sample, the end of a task or a unit becoming idle again. Other units are unaffected.

Authority is restored only by explicitly running the corresponding rule's `auto-set` enable command again, within that rule's configured scope, and other rules are not enabled. With `--queue`, temporary micro orders are withdrawn first and the complete task queue from before the takeover is kept, then the manual task is appended; pure action and stance commands keep the original tasks.

### 5.1 Automatic production ("keep")

`keep` makes a factory top up production automatically:

- `rwx-game-keep-set --unit <factory id> --action <production action id> --count N`: keep N of that action in the queue at all times.
- `rwx-game-keep-list`: show rules, queue depth, top-up count and state.
- `rwx-game-keep-clear [--id N]`: delete one rule or all of them.

A rule only tops up while the action is available and affordable; it waits during upgrades. Repeating `keep-set` for the same factory and action updates the count. Rules are cleared with the map/match, at most 32.

When a production, upgrade or ability action is issued manually to a factory, or an `order-cancel` succeeds, that factory's old keep rules are paused and not-yet-executed top-up orders are withdrawn, so cancelled production is not immediately re-added. An existing original production queue is handled normally by the manual command; re-running the corresponding `keep-set` restores that rule, and `keep-list` shows the paused state.

### 5.2 Pending queue

- `rwx-game-pending-list`: show pending items, why they wait, and the last 20 dispatched/discarded records.
- `rwx-game-pending-clear [--id N]`: cancel one item; omitting `--id` clears all.

At most 20 pending items; an item that cannot be dispatched within 10 minutes is discarded, as is one whose unit died, whose factory was destroyed or whose action became invalid.

A pending request always uses the units selected when it was accepted and does not widen later because `all-army` or a group changed. A newer non-queued task (including stop) cancels old pending tasks of the selected units; a multi-unit pending item only loses the conflicting members. `order-cancel` also removes pending production for the matching action of that factory, or all pending production when the action is omitted, so production is not restarted later.

### 5.3 Micro-management rules

Micro-management is off by default. Each rule has exactly one configuration, setting it again updates that configuration, and everything resets when the match changes.

- `rwx-game-auto-set --rule <id> --enabled false`: disable a rule and release the units it took over, keeping the configuration; re-enable with `--enabled true` or by omitting `--enabled`.
- `rwx-game-auto-list`: list every rule's switch, configuration, candidate count, taken-over count, trigger count and recent records.
- `rwx-game-auto-show --rule <id>`: show one rule's configuration, the ids of units it holds and its trigger records.
- `rwx-game-auto-clear --rule <id>`: disable a rule and restore its default configuration.
- `rwx-game-auto-reset`: disable every rule, release the units and restore all default configurations.

A rule applies to qualifying units of our own by default. When enabling it, the common filters below can narrow the scope (`turret` and `assist-counter` accept no filters):

- `--units <ids|all-army|all-units>`: comma-separated unit ids, or `all-army` for non-building units and `all-units` to include buildings; the rule's own conditions still apply.
- `--group <group name>`: use a saved group, updated automatically when its members change; mutually exclusive with `--units`.
- `--type <id or name,...>`: the given types only.
- `--exclude <id or name,...>` / `--exclude-builders`: exclude given ids, types or builders.

An authorised rule may work on the allowed `attack-move`, `guard-at`, `patrol` or idle tasks; the per-rule sections describe the specific limits. Tasks such as direct move, attack on a specific target, follow and build are not overridden by combat micro rules; even `unstuck` must be authorised before it may re-issue tasks or side-step. When a temporary micro task ends the original task and its remaining queue are restored, unless a new manual order has already arrived. No new decisions are made while paused, at speed 0 or during replay playback; `auto-list` / `auto-show` keep at most the latest 60 trigger, restore and failure records.

Automation conflicts are ordered by the priority below: higher wins, but all are below manual orders. `dive` only issues actions, and `idle-alert` only reminds without taking over tasks.

| Priority | Rule |
|---:|---|
| 100 | `retreat` |
| 90 | `unstuck` |
| 80 | `assist-counter` |
| 70 | `dive` |
| 60 | `mech` |
| 50 | `kite` |
| 40 | `artillery` |
| 35 | `turret` |
| 30 | `focus-fire` |
| 20 | `guard` |
| 15 | `assist-build` |
| 10 | `repair` |
| 0 | `idle-alert` |

#### 5.3.1 Ranged-unit micro

- `rwx-game-auto-set --rule artillery`: applies to our artillery whose name contains `artillery`; it takes over only during `attack-move`, guard or idle, never during patrol. While guarding or idle, units fight near the original guard point, drifting no more than 120 world units.
- `rwx-game-auto-set --rule kite [--radius R]`: applies to our completed, mobile, non-building attack units (land, sea or air; land artillery uses `artillery`). It only takes over units whose range against a currently visible, attackable target reaches `--radius`; default 400 world units, and this parameter is a **minimum-range threshold**. Units hold position and fire when the weapon is ready, and open distance during cooldown.

`kite` only takes over during `attack-move`, guard, patrol or idle; units inside transports, or with hold-fire or return-fire-only stance, do not take part. Qualifying units join automatically, and the common filters can narrow the scope.

#### 5.3.2 Low-HP retreat (retreat)

- `rwx-game-auto-set --rule retreat [--hp N] [--radius R]`: makes mobile attack units evade over a short distance while HP ≤ N%, otherwise they continue their task; it does not return to base or look for repair facilities. `--hp` ranges 1..100, default 20; `--radius` is the threat search radius, default 300 world units. Each retreat covers 40% of the radius, but no less than 90 and no more than 200 world units.
- `rwx-game-auto-set --rule mech [--hp N] [--radius R]`: an aggressive mech with HP ≤ N% moves about 60 world units away from the threat when it was attacked in the last 2 seconds or an attackable enemy is nearby, and resumes its task once clear. `--hp` ranges 1..100, default 40, and 100 removes the HP limit; `--radius` is the enemy search radius, default 320 world units, and a recent attacker can also trigger the retreat.

#### 5.3.3 Smart diving (dive)

`rwx-game-auto-set --rule dive`: switches diving and surfacing according to the engagement target within range — dive when the target is underwater, surface otherwise. Applies to ordinary submarines, the Nautilus, amphibious jets and similar mod units that have both the `Dive` and `Surface` actions.

#### 5.3.4 Focus fire (focus-fire)

`rwx-game-auto-set --rule focus-fire`: while engaged, allocates fire by attacker count and keeps finishing off targets at HP ≤40%. Common filters such as `--group` can narrow the scope.

#### 5.3.5 Area defence (guard)

`rwx-game-auto-set --rule guard --x X --y Y [--radius R]`: makes mobile attack units defend a circular area, engaging enemies that enter and otherwise returning to the centre. `--x` and `--y` are the required world coordinates of the centre, which is also the rally point to return to; `--radius` defaults to 300 world units. A unit up to twice the radius from the centre can be pulled back, and beyond that the rule no longer takes it over. It only applies to units still authorised, and manual orders take priority.

```text
rwx-game-auto-set --rule guard --group 3 --x 1200 --y 900 --radius 260
```

#### 5.3.6 Idle auto-repair and construction assistance (repair/assist-build)

- `rwx-game-auto-set --rule repair [--radius R]`
- `rwx-game-auto-set --rule assist-build [--radius R]`

It only applies to idle builder units, searching nearby for damaged completed friendly buildings, unfinished construction or units to repair/assist, and returning to the original position afterwards. Whether a specific target can be repaired depends on the unit's abilities: builders normally repair buildings only, while some engineers can also repair units. `--radius` is the search radius centred on the repairer, default 220 world units.

#### 5.3.7 Getting unstuck (unstuck)

`rwx-game-auto-set --rule unstuck`: tries to free a stuck unit; after repeated failures it records "cannot get unstuck" and stops trying for a while.

#### 5.3.8 Smart turrets (turret)

`rwx-game-auto-set --rule turret`: makes our turrets attack more intelligently, excluding turrets that do not fire on their own; a manually assigned target takes priority. Accepts no common filters.

#### 5.3.9 Idle notifications for factories and units (idle-alert)

`rwx-game-auto-set --rule idle-alert [--include-units <true|false>]`: by default it only reminds when a completed factory of ours has an empty production queue, and issues no commands.

- `--include-units true`: also remind about idle attack units; default `false`, and factory reminders are always kept.
- Adding common filters (such as `--group`, `--units`, `--type`, `--exclude`) enables unit reminders automatically and only for matching idle attack units, excluding builders.
- Setting it again keeps the existing configuration.

```text
rwx-game-auto-set --rule idle-alert --include-units true
rwx-game-auto-set --rule idle-alert --group 3
rwx-game-auto-set --rule idle-alert --include-units false
```

#### 5.3.10 Assist and counter-attack (assist-counter)

`rwx-game-auto-set --rule assist-counter`: lets all our idle attack units and aggressive buildings assist allies, counter-attack enemies and keep evading intelligently. Accepts no common filters; disable it with `--enabled false`.

## 6. History — Real-time events

### 6.1 Unified event query

`rwx-game-events [--types <type1,type2,...>] [--seconds N] [--allies]`

It shares the event collector, cache and query handler with `game-state`; a query neither consumes nor resets history.

- `--types`: types are unioned; the default is `damaged,destroyed,losses,new`, and `info` must be named explicitly.
- `--seconds N`: the last N game seconds, default 30, range (0, 180].
- `--allies`: `destroyed` and `losses` only cover allies (excluding our own), while `damaged`, `info` and `new` still cover our own side only.

| Type | Content and aggregation |
|---|---|
| `damaged` | one of our units or buildings took effective damage; the same damaged ID is recorded again only after at least 5 game seconds, regardless of whether the attacker changed. |
| `destroyed` | we destroyed an enemy, or with `--allies` an ally did; de-duplicated by the enemy's real ID, keeping the earliest effective record in the window. |
| `losses` | our losses, or an ally's with `--allies`; de-duplicated by ID, then grouped by team and loss type, with losses no more than 3 game seconds apart merged into a run. |
| `info` | our unit creation, production, construction start/finish and upgrade start/finish; only stages that really happened are reported. |
| `new` | enemies first or newly discovered in our vision; the latest discovery in the window is used for one ID, continuous visibility does not refresh it, and discoveries no more than 3 game seconds apart are merged. |

Each line is formatted as `[MM:SS][type] description`, using English names and text, with no heading, cursor or summary; with no matches the output is empty text. The raw cache keeps at most 180 game seconds and 1000 entries.

```text
rwx-game-events --types losses,destroyed --seconds 90
rwx-game-events --types losses --allies
rwx-game-events --types info --seconds 60
```

```text
[09:35][losses] we lost Tank x8 by Heavy Tank(120,220)x5, Tank(100,200)x3
[09:37][new] found Tank x3, Turret T1 x1 around (100,200)
```

`rwx-game-observe-reset [--keep-groups]`: clears event history and the separate `unit-list --delta` baseline, and by default clears groups too; `--keep-groups` keeps them. Default stances and the set of units they were already issued to are unaffected.

**Live event stream**

A game started with the local CLI enabled opens an event socket on loopback; the `RWX_EVENTS_READY` log line reports the port, and the default port file is `agent-operation/tmp/events-port`. With a custom CLI port file the event port file sits in the same directory; the CLI command port file is unchanged. Use the existing receiver script:

```powershell
python agent-operation/tools/rwx-game-events-to-jsonl.py
python agent-operation/tools/rwx-game-events-to-jsonl.py --info --allies
```

The script applies a second filter on receipt and appends only `kind == "update"` records to `agent-operation/tmp/game-events.jsonl`, keeping the newest 500 (the tail is rewritten when that limit is exceeded); `hello`, `event`, `remove`, `cursor` and transport diagnostics are not stored, and diagnostics go to stderr instead. Press Ctrl+C to stop. Use `--port` or `--port-file` to choose an instance. Event types default to those of `game-events`; `--info` adds information events, and `--allies` switches kills and losses to the allied scope.

On disconnect the receiver reconnects from the last session and sequence number, following every sequence whether or not it was stored, so a reconnect neither replays nor reports a false gap; after a restart the resume point is the newest stored update. The transport cache keeps at most 180 game seconds and 1000 records. An out-of-range cursor or a queue overflow is reported as `gap` or `overflow` on stderr, with no guarantee of completion. A process restart creates a new session, and the old process's in-memory records cannot be recovered.

**Subscription protocol**

UTF-8 NDJSON: the client sends the following line followed by a newline:

```json
{"protocol":"rwx-events-v1","kind":"subscribe","session":null,"after":0,"info":false,"allies":false}
```

The server replies with `hello` and then sends `event`, `update`, `remove`, `cursor`, `gap`, `overflow` or `reset`. `seq` is the transport sequence; `eventId` and `revision` identify aggregate revisions; `remove.reason` is `replaced` or `expired`. `event.type`, `event.description`, `gameTimeMillis` and `tick` come from real events, while `session`, sequences, revisions and gap messages are transport metadata. Aggregate consumers must apply revisions and removals by `eventId` and must not count an `update` as a new event.

### 6.2 Replay

- `rwx-game-replay-start [--name <file name>]`: start recording a match that has already begun, from its current state.
- `rwx-game-replay-stop`: stop and write the file.
- `rwx-game-replay-status`: show recording state, file, size and the number of recorded commands.

Replay files are written to `../replays`. Pausing and `gameSpeed` are not written into the replay; commands issued while paused are recorded on the same tick. To record from tick 0 at match start, use `rwx-battleroom-start --record`.

## 7. Reference — Game encyclopedia

Queries static data about unit types, buildings, economy and technology.

`--name` accepts an internal id, an internal short name, a localised name or a custom unit's `displayText`; case, spaces, underscores and hyphens are insignificant.

Units and buildings share one definition structure: `id`, `name` (English), `name_zh` (Chinese), `kind`, `cost`, `hp`, `speed`, `vision`, `weapon`, `ability`, `actions`, `produces`, `builds`, `upgrade`, `requires`, `from`, `placement`, `features`, `about`. Fields that do not apply structurally are omitted entirely (a tank has no `produces`, for instance); values use tiles, seconds and credits uniformly.

### 7.1 Units

- `rwx-query-unit [--name <name>] [--category <builder|transport|land|air|sea|lab>] [--level 0..2] [--detail] [--json]`: with no parameters it lists a name index of every unit and building; `--name` prints one unit definition; `--category` prints a table for that category (transports include passenger capacity).
- `--detail`: expand per-item actions (id, cost, target), common orders, aliases and the full game description.
- `--json`: JSON with the same fields as the current tier; `--detail --json` emits the expanded form.
- `--level` applies only to `land`, `air` and `sea`: `0` is any level, `1` is producible by an un-upgraded factory, `2` needs an upgraded factory.

### 7.2 Buildings

- `rwx-query-building [--name <name>] [--category <turret|defense|economy|nuke>] [--detail] [--json]`: with no parameters it lists a name index of every building; `--name` prints a building definition (including upgrade path and footprint); `--category` prints a table for that category.

### 7.3 Production capacity

- `rwx-query-production --factory <land|air|sea|lab|commander|name|id> [--level 0..2] [--maxCost <cost>]`: what the given factory can produce or build; producing units adds HP/range columns, producing buildings adds a footprint column.

## 8. System — System utilities

CLI endpoint and connection-related features.

### 8.1 Startup and connection

Start the game and command endpoint with `:desktop:rwxCliRun`, or `:desktop:run --args="--rwx-cli"`; Java 25 is required. `rwx-ping` returns `pong` for a liveness check.

`--headless` implies `--rwx-cli` and creates no GUI, window, graphics context, audio device or extra terminal. Game ticks, pathfinding, networking, CLI and command scheduling remain available; Xvfb is not required.

`--data-dir=<directory>` sets the instance data and identity directory; `--cli-port-file=<file>` sets its CLI port file. Use separate directories and port files for multiple instances.

Pure headless distribution: `:desktop:headlessArchive`, producing `desktop/build/distributions/rwx-headless.tar.gz`; on Linux run `bash bin/rwx-headless`. See [SERVER.md](SERVER.md) for deployment.

Reuse a persistent connection throughout the match: prefer `rwx-cli.py --stdio` when the host can retain the process and its streams, or use persistent TCP if the host already supports it. Otherwise use a one-shot or batch client. See [START.md](START.md) for startup and starting a match; see [tools/README.md](tools/README.md) for other clients and the protocol.

### 8.2 Some conventions

- **Port file**: the CLI listens on a random port at start and, once listening succeeds, atomically writes `<repo root>/agent-operation/tmp/port` (the port number only). The path can also be set with `--cli-port-file=<path>`, `-Drwx.cli.portFile=<path>` or `RWX_CLI_PORT_FILE`. This port is for the local CLI only and is not the multiplayer game port.
- **Multiple instances**: the default port file is published by the primary instance holding the process lock, and other instances never overwrite it. Each instance also has `port.instances/<PID>-<port>.port`, with the path in the `instancePortFile` field of `RWX_CLI_TOOL_READY`. When the primary instance exits another takes over; exit cleanup still holds the file lock, so a new primary's record is not deleted by mistake.
- **Port validation**: an abnormal exit may leave the file behind, so clients must verify the port with `rwx-ping`. The Python tool discovers the port from an explicit port/file, then the environment variable, then the primary file, then per-instance files; with several candidates an explicit choice is required. The port-record directory is already in `.gitignore`.
- **Encoding**: all text sent and received is UTF-8 and server replies are always UTF-8; a command line sent in GBK by an older client is decoded compatibly. With `--rwx-cli` the process's stdout/stderr and logs are UTF-8 as well; a GBK terminal may show Chinese garbled, which affects neither files nor loopback replies.
- **Command arguments**: quote values containing spaces with single or double quotes, e.g. `--name "Lake (2p)"`; the server accepts plain-text command lines.
- **Coordinates**: world coordinates by default, and in a real match 1 tile = 20 world units. Only range parameters explicitly documented as tiles use tiles (e.g. `map-place --radius`, `map-show --terrain --half-width/--half-height`); other range parameters such as `unit-list --radius` use world coordinates.

### 8.3 Command receipt semantics and the command queue

- `submitted`: the command was handed to the engine; whether it applies immediately depends on pause and replay-recording state, see below.
- `pending`: the command was held by the CLI and is dispatched automatically once credits or the upgrade are ready.
- `partial`: `--count N` submitted only part of the order, and the receipt carries `requested/submitted/pending/failed`.
- `error:`: the whole command failed and nothing was issued.
- Without `--queue` an existing command is replaced; with it the new order is appended to the current queue. A replacement is reported in the receipt.
- While paused or at speed 0 and not recording a replay, commands apply immediately (`appliedImmediately: true`); while recording a replay they are queued to the next tick instead, to avoid replay desync.
- Control rights, target relationships, coordinates, build capability and action availability are validated before submission.
