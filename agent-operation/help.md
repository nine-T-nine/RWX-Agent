# RWX CLI

> Full English version of [`help_zh.md`](help_zh.md). **`help_zh.md` is the authoritative document**;
> this file is synced from it in the final stage of a change, so it can lag behind while text is still moving.

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

- `connect` establishes the game connection asynchronously, defaulting to the original TCP port `5123`. Accepts original direct addresses, `host:port/room-code`, short relay codes and `get|...` master-server descriptors. `--room` appends the original query string for direct relays; a `get|...` descriptor already identifies a room and cannot take `--room`. Player names are 2..20 characters.
- `RELAY-CN` is looked up through the current public master-server list by publisher/room identifier, with no hard-coded address; an unreachable list, no match, or a failed protocol handshake are all reported explicitly. An entry name alone does not prove the service is usable. For creating/joining on a public relay, follow what the entry itself reports.
- A connection receipt only means the connection was requested; `joined` in `connection-status` requires the original room configuration plus a local team assignment, and `in-game` additionally requires the map to be loaded. The status also carries `resolving`, `connecting`, `hosting`, `disconnected`, `error`, and shows the game port, team and errors.
- The timeout covers TCP connect and room registration, range 1000..120000 ms. On timeout the attempt is cancelled and the game connection closed; late sockets are closed too. A password prompt can be answered with `--password` or with an RWX process environment variable via `--password-env`; a wrong password is not retried indefinitely.
- `--reconnect true` reuses the last address and the same client identity. The host decides whether the original team can be restored; matches already in progress normally refuse brand-new players. Multiple agents should each use their own `--data-dir` and must not share one persistent client identity.
- `disconnect` closes the game network connection and returns to the menu, keeping the RWX process and the local CLI; closing a Python/PowerShell CLI client does not exit the game either. `exit` closes the RWX process and its CLI.
- `host` reuses the original hosting: it listens on the given TCP and reliable-UDP game ports and does not publish to the public master server by default. `--spectator true` makes the server's local player a spectator; `--auto-start-players 2` explicitly authorises the host to start once at least two non-spectator human clients are present, and `0` disables auto-start. AI does not count towards that threshold.
- `--managed-room true` enables the persistent-room policy, which implies a server spectator seat and is mutually exclusive with auto-start by player count. The first client receives the original proxy-host authority; after it leaves, authority passes on in join order, and the election happens only in the lobby, frozen during a match. A proxy host's GUI/CLI management requests are sent to the server through original chat packets for validation and execution; a CLI receipt means the request was submitted, so query the room to confirm the result.
- In a managed match, new identities join as spectators automatically and a reconnecting original player keeps their seat. When every online non-spectator player has left or been defeated, the server forces the match to end and returns to the lobby through original packets, keeping clients that are still online. Spectating clients and game AI do not block the end; no winner is assigned. Proxy authority never grants control over other teams' units.
- `connection-status` adds `roomAdmin` (this client's proxy management rights), `roomManager` (the admin name when queried locally on the server) and `spectator`. An ordinary client can only learn its rights from the original room state and the proxy-authority bit.
- An ordinary player can view the room, chat, adjust their own spawn point/faction under the original rules, and in a match query visible information and control their own units. Only the host/original proxy host may change the room, map or AI, or start the game. Agents have no extra privileges; their commands go through the original `Command` network queue.

Example: an agent runs its own RWX locally and then has the local CLI connect to a cloud server's game port:

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

By default it shows, in a fixed order, the battle situation, the economy, the strength of our/allied/currently visible enemy forces, production, upgrades, our health buckets, CLI pending items, units and buildings that need attention, and notable events of the last 30 game seconds. An empty block shows `none`. `--summary` keeps all macro statistics and events without per-entity detail; it is mutually exclusive with the four entity filter switches.

- `--units-acting`: non-building units that are moving, engaging, building, producing, repairing, patrolling, guarding, following, and so on.
- `--units-damaged`: non-building units whose HP is strictly below 70%.
- `--building-acting`: buildings that are constructing, producing, upgrading, repairing, attacking or defending.
- `--building-actionable`: buildings that could upgrade or produce right now given credits and conditions.

The four filters can be combined (union, de-duplicated); when none is passed all four are enabled. Filters affect only entity detail, never the statistics. State comes from actual engine activity; detail includes ID, coordinates, HP, current order, target and waypoints, and buildings add their tech level and a production-queue summary.

- `--event-seconds N`: game-time window, default 30, must be greater than 0 and no more than 180.
- `--event-types`: comma-separated, default `damaged,destroyed,losses,new`; `info` is shown only when named explicitly.
- `--event-allies`: `destroyed` and `losses` only show allies; other types keep their rules, see 6.1.
- `--no-events`: do not query or show events; cannot be combined with other event parameters and does not clear history.
- `--json`: emit standard JSON using the same report data as the text output.

JSON top level is split into `battle`, `economy`, `forces`, `production`, `upgrades`, `healthStatus`, `pending`, `notableUnits`, `notableBuildings` and `events`. With `--summary` the two entity arrays are empty; with `--no-events` the `events` key is omitted. `battle` contains `tick`, `time`, `gameTimeMillis`, `paused`, `speed`, `map`, `controlledTeam`, `outcome`; the economy is `credits`, `incomePerSecond`.

- `forces.self` / `allies` / `visibleEnemies` each contain `unitCount` (excluding buildings), `buildingCount` and structured `typeCounts` (each with `type`, `count`); self and allies also contain `idleUnitCount`. Allies exclude our own forces, enemies are only currently visible entities, and production queues do not count towards strength.
- `production.factoryCount` is the number of our completed buildings that can produce units; `busyFactoryCount` / `idleFactoryCount` count by whether the queue is empty, with an upgrade occupying the factory counting as busy; `productionQueueEntryCount` counts production entries only, not upgrades.
- `upgrades` contains `id`, `type`, `upgrade`, `progress`; progress is 0..1, unknown is null.
- `healthStatus.units` / `buildings` count per type: `criticalCount` (HP <10%), `lowHealthCount` (10% ≤ HP <50%), `injuredCount` (50% ≤ HP ≤80%).
- `pending` contains `pendingOrders`, `waitingForCreditsCount`, `waitingForUpgradeCount`; it is null when pending queries are unsupported.
- `events.items` contains `gameTimeMillis`, `type`, `description` after shared query processing; the window is `windowSeconds`. Events use game time and querying does not consume history.

For example:

```text
rwx-game-state
rwx-game-state --summary --json
rwx-game-state --units-damaged --building-acting --event-seconds 60
rwx-game-state --event-types info --event-allies
rwx-game-state --no-events
```

- `rwx-game-team-list`: all teams, factions, AI, active/defeated state, this team's credits and net income; the economy of other teams is not exposed.

### 2.2 Unit list and detail

`game-state` only gives a short state for entities that need attention; the full list, in-depth information per entity and available actions use the commands below.

- `rwx-game-unit-list [--owner self|ally|enemy|neutral|any] [--type <id or name,...>] [--group <name>] [--units <ids|selector>] [--x X --y Y --radius R] [--hp <percentage>|--full] [--idle] [--exclude-builders] [--exclude <id|name,...>] [--summary] [--delta] [--compact] [--json]`: unit list, by default only entities below 80% HP.
- `rwx-game-unit-show --id <id>`: detail for a single unit, including faction, T level, upgrade level, attack mode, production queue and the full waypoint queue; orders held back by the CLI are listed in `cliPending`.
- `rwx-game-unit-actions --unit <id> [--json]`: actions the unit can currently perform, with action id, price, whether it can be issued, queued count and the reason it is unavailable; `queued` is how many of that action are in the real production queue (including the one being produced), and `available` means at least one is genuinely affordable now.

Common switches for `unit-list`:

This command lists matching entities (units and buildings) to obtain IDs, world coordinates, HP, orders and queues; enemy entities are returned only while currently visible. The default view is "needs attention": only entities below 80% HP; `--hp N` moves that line and `--full` removes the filter and lists everything. Output carries no change section by default — only an explicit `--delta` reports differences against the previous `--delta` query and advances the baseline; `--json` is always read-only.

- `--owner self|ally|enemy|neutral|any`: ownership; when omitted only our own and currently visible enemy entities are included, excluding allies and neutral entities; `ally` means allies only. The default change baseline uses the same faction scope.
- `--type <id or name,...>`: filter by unit type; internal ids are matched exactly, and when nothing matches, localised names are tried (partial names such as "heavy tank" work). This is the only type-filter entry point.
- `--units <ids|selector>` / `--group <name>`: further filter by entity ID, type, group or area; selectors are described in 3.4.
- `--x X --y Y --radius R`: circular area in world coordinates; the centre must be given as a pair and the radius must be non-negative; the `--radius` here is retained and is not subject to *(source `help.md` truncates this sentence)*

`--owner any` includes all observable factions; the default remains self and currently visible enemies. `--idle` selects entities without a current waypoint or production queue; `--exclude-builders` and `--exclude` exclude builders or named types. `--summary` counts all matches without accepting `--hp`, `--full`, `--compact`, `--delta` or `--json`. `--compact` only changes text presentation; `--delta` compares with and updates the previous `--delta` baseline, detecting additions, departures, health decreases and tech increases; `--json` cannot be combined with `--delta` and does not update the baseline.

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

**Agent commands always take priority over every automation rule.** Issuing a move, attack, `attack-move`, stop, patrol, guard, queued task, action or stance command to a unit cancels that unit's not-yet-executed micro-management and keeps returning control; the old rule will not take the unit back on the next sample, when the task ends, or when the unit becomes idle again. Only the selected units are affected; other units stay automated.

To re-authorise a micro rule, explicitly run that rule's `auto-set` enable command; authority is restored only within that rule's configured scope and does not enable other rules. For example:

```text
rwx-game-order-attack-move --units 123 --x 1800 --y 900
rwx-game-auto-set --rule guard --units 123 --x 1200 --y 900 --radius 260
```

The first command makes #123 execute a manual push and the old guard no longer pulls it back; the second explicitly hands #123 back to area defence. `--queue` first withdraws temporary micro orders, keeps the complete task queue from before the takeover, then appends the manual task; pure action and stance commands keep the original tasks.

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

Micro-management is off by default, each rule has exactly one configuration, setting it again updates it, and everything resets when the match changes.

- `rwx-game-auto-set --rule <id> --enabled false`: disable a feature and release the units it took over, keeping the configuration. Re-enable with `--enabled true`, or by omitting `--enabled`.
- `rwx-game-auto-list`: show every feature's switch, current configuration, candidate count, taken-over count, trigger count and recent records.
- `rwx-game-auto-show --rule <id>`: show one feature's configuration, the ids of units it holds and its trigger records.
- `rwx-game-auto-clear --rule <id>`: disable a feature and restore its default configuration.
- `rwx-game-auto-reset`: disable all micro rules, release the held units and restore every default configuration.

For example, disable only the artillery micro rule and inspect the state:

```text
rwx-game-auto-set --rule artillery --enabled false
rwx-game-auto-show --rule artillery
```

Each feature applies to qualifying units of our own by default. To narrow the scope, append the following common filters to the enable command; when no filter is needed just use the minimal command from each section (`turret` and `assist-counter` accept no filters, see 5.3.8 and 5.3.10).

- `--units <ids|all-army|all-units>`: comma-separated unit ids; `all-army` selects non-building units, `all-units` includes buildings. The feature's own conditions still apply.
- `--group <group name>`: use a group saved by `group-save`, e.g. `--group 3`; it follows membership changes automatically. Alternative to `--units`.
- `--type <id or name,...>`: only select the given types.
- `--exclude <id or name,...>` / `--exclude-builders`: exclude given units, types or builders.

Once a micro rule is explicitly enabled it can work on the allowed `attack-move`, `guard-at`, `patrol` or idle tasks; extra restrictions for artillery, repair and similar features are described in their own sections. Any later manual command keeps yielding authority, and authority only returns after another `auto-set`. Tasks such as direct move, attack on a specific target, follow and build are not overridden by combat micro rules; even `unstuck` must be authorised before it may re-issue tasks or side-step.

When a temporary micro task ends, the original task and its remaining queue are restored; if you have already issued a new command, the new command wins. No new decisions are made while paused, at speed 0, or during replay playback. Trigger, restore and failure records are visible through `auto-list` / `auto-show`, keeping at most the latest 60.

The numbers below only order automation-to-automation takeover conflicts; higher wins, and all are below manual commands. `dive` only issues actions and `idle-alert` only reminds, so neither holds task ownership.

| Priority | Rule | Behaviour after a manual command |
|---:|---|---|
| 100 | `retreat` | stop automatic retreat |
| 90 | `unstuck` | stop re-issuing tasks and side-stepping |
| 80 | `assist-counter` | stop automatic assist, counter-attack and retreat |
| 70 | `dive` | withdraw the pending action, stop automatic diving/surfacing |
| 60 | `mech` | stop automatic retreat |
| 50 | `kite` | stop rewriting position and targets |
| 40 | `artillery` | stop artillery micro |
| 35 | `turret` | stop automatic target assignment |
| 30 | `focus-fire` | stop automatic target switching |
| 20 | `guard` | stop engaging and pulling back to the old defence area |
| 15 | `assist-build` | stop automatic construction assistance |
| 10 | `repair` | stop automatic repair |
| 0 | `idle-alert` | keep reminding read-only, issue no commands |

#### 5.3.1 Ranged-unit micro

- `rwx-game-auto-set --rule artillery`: all our artillery units whose name contains `artillery`; takes over only during attack-move, guard-at or idle, never during patrol. While guarding or idle they fight near the original guard point, drifting no more than 120 world units.
- `rwx-game-auto-set --rule kite [--radius R]`: applies to our completed, mobile, non-building attack units regardless of land, sea or air; land artillery uses the `artillery` rule. The unit's range against currently visible, attackable targets must reach `--radius` (default 400 world units). It holds position and fires when the weapon is ready, and opens distance during cooldown.

- `--radius R`: the minimum range against the enemy required for takeover, default 400 world units. Here it is a range threshold.

`kite` only takes over during attack-move, guard-at, patrol or idle; units inside transports, or with hold-fire or return-fire-only stance, are excluded. There is no need to name unit types one by one — qualifying units join automatically, and the common filters may narrow them.

For example, only let units with at least 350 range against the enemy kite:

```text
rwx-game-auto-set --rule kite --radius 350
```

#### 5.3.2 Low-HP retreat (retreat)

`rwx-game-auto-set --rule retreat [--hp N] [--radius R]`: makes mobile attack units retreat at low HP, otherwise they continue their task. It only performs short-range evasion; returning to base or finding a repair facility still needs a separate order.

- `--hp N`: the HP trigger line, 1..100, default 20; triggers when HP ≤ N%.
- `--radius R`: threat search radius, default 300 world units. Each retreat moves 40% of the radius, minimum 90 and maximum 200 world units.

For example, retreat when HP is at most 25% and a threat is within 320:

```text
rwx-game-auto-set --rule retreat --hp 25 --radius 320
```

`rwx-game-auto-set --rule mech [--hp N] [--radius R]`: aggressive mech units move about 60 world units away from a threat when they took damage in the last 2 seconds or when an attackable enemy is found nearby, then resume their task after breaking away.

- `--hp N`: retreat only when HP ≤ N%, 1..100; default 40, i.e. only at 40% HP or below. Set 100 to remove the HP limit.
- `--radius R`: search radius for nearby enemies, default 320 world units; a recent attacker can also trigger the retreat.

For example, let only mechs at half HP or below retreat:

```text
rwx-game-auto-set --rule mech --hp 50
```

#### 5.3.3 Smart diving (dive)

Switches between diving and surfacing according to the engagement target within range: dive when the target is underwater, surface otherwise.

`rwx-game-auto-set --rule dive`

Applies to ordinary submarines, the Nautilus, amphibious jets and similar mod units that have both the `Dive` and `Surface` actions.

```text
rwx-game-auto-set --rule dive
```

#### 5.3.4 Focus fire (focus-fire)

`rwx-game-auto-set --rule focus-fire`: while engaged, units allocate fire intelligently by attacker count, and keep finishing off targets at or below 40% HP.

For example, enable focus fire on group 3 only:

```text
rwx-game-auto-set --rule focus-fire --group 3
```

#### 5.3.5 Area defence (guard)

Makes mobile attack units hold a circular area: they engage enemies inside it and otherwise return to the centre. Units still within twice the radius are pulled back; beyond that the rule no longer takes them over.

The pull only applies to units still authorised. Manual `attack-move`, `move`, `stop`, `guard-at`, `patrol` and similar commands take priority immediately and are not repeatedly pulled back by the old guard just because the unit is still inside twice the radius.

`rwx-game-auto-set --rule guard --x X --y Y [--radius R]`

- `--x X --y Y`: required; world coordinates of the defence centre, which is also the rally point to return to.
- `--radius R`: defence radius, default 300 world units.

For example, have group 3 defend a 260-radius area centred on (1200, 900):

```text
rwx-game-auto-set --rule guard --group 3 --x 1200 --y 900 --radius 260
```

#### 5.3.6 Idle auto-repair and construction assistance (repair/assist-build)

Idle builders or engineers look for nearby completed damaged friendly buildings, unfinished construction or units and repair them, returning to their original position afterwards. Which targets a unit can repair depends on its own abilities.

`rwx-game-auto-set --rule repair [--radius R]`
`rwx-game-auto-set --rule assist-build [--radius R]`
- `--radius R`: target search radius centred on the repairer, default 220 world units.

Applies to idle builder units only. Builders normally repair buildings only; engineers that can repair units also repair friendly units.

```text
rwx-game-auto-set --rule repair --radius 180
rwx-game-auto-set --rule assist-build --radius 200
```

#### 5.3.7 Getting unstuck (unstuck)

`rwx-game-auto-set --rule unstuck`: frees a stuck unit intelligently; after repeated failures it records "cannot get unstuck" and pauses attempts for a while.

```text
rwx-game-auto-set --rule unstuck
```

#### 5.3.8 Smart turrets (turret)

`rwx-game-auto-set --rule turret`

Makes all our turrets attack more intelligently, excluding turrets that do not fire on their own. When you assign an attack target manually, your order takes priority.

Enable, disable and inspect:

```text
rwx-game-auto-set --rule turret
rwx-game-auto-set --rule turret --enabled false
rwx-game-auto-show --rule turret
```

#### 5.3.9 Idle notifications for factories and units (idle-alert)

By default it only writes a reminder when a completed factory of ours has an empty production queue. Idle-unit reminders can be enabled too, which helps spot attack units without a task. It only reminds and never issues commands.

`rwx-game-auto-set --rule idle-alert [--include-units <true|false>]`

- `--include-units <true|false>`: unit reminder switch, default `false`, keeping factory reminders.
- Passing common filters (such as `--group`, `--units`, `--type`, `--exclude`) also enables unit reminders automatically and only reminds about matching idle attack units; builders are excluded.
- Setting it again keeps the existing configuration.

Enable factory reminders only:

```text
rwx-game-auto-set --rule idle-alert
```

Enable unit reminders, then narrow them to group 3:

```text
rwx-game-auto-set --rule idle-alert --include-units true
rwx-game-auto-set --rule idle-alert --group 3
```

Disable unit reminders and inspect the state:

```text
rwx-game-auto-set --rule idle-alert --include-units false
rwx-game-auto-show --rule idle-alert
```

#### 5.3.10 Assist and counter-attack (assist-counter)

Let all our idle attack units and aggressive buildings assist allies, counter-attack enemies and keep evading intelligently.

```text
rwx-game-auto-set --rule assist-counter
rwx-game-auto-set --rule assist-counter --enabled false
```

## 6. History — Real-time events

### 6.1 Unified event query

`rwx-game-events [--types <type1,type2,...>] [--seconds N] [--allies]`

`game-state` and `game-events` share the same event collector, cache and query handler; a query neither consumes nor resets history.

- `--types`: several types are unioned; the default is fixed to `damaged,destroyed,losses,new`. `info` is output only when named explicitly.
- `--seconds`: the last N game seconds, default 30, maximum 180, must be greater than 0.
- `--allies`: switches `destroyed` and `losses` to allies only, excluding our own. `damaged`, `info` and `new` always refer to our own side.

| Type | Content |
|---|---|
| `damaged` | one of our units or buildings took effective damage; consecutive records for the same damaged ID are at least 5 game seconds apart, regardless of whether the attacker changed. |
| `destroyed` | we destroyed an enemy target; with `--allies` only allied kills. De-duplicated by the enemy's real ID, keeping the earliest effective record in the window. |
| `losses` | our losses; with `--allies` only allied losses. After de-duplication by ID they are grouped by team and loss type, and losses no more than 3 game seconds apart are merged into one run. |
| `info` | our unit creation, production, construction start/finish, upgrade start/finish; only real stages that have happened are described. |
| `new` | enemies first or newly discovered within our vision. The latest discovery in the query window is used for one ID; continuous visibility does not refresh it. Discoveries no more than 3 game seconds apart are merged into one run. |

Each output line is fixed as `[MM:SS][type] description`, names and text in English, with no heading, cursor or summary. With no matching events the output is empty text.

```text
rwx-game-events
rwx-game-events --types losses,destroyed --seconds 90
rwx-game-events --types losses --allies
rwx-game-events --types info --seconds 60
```

```text
[09:32][damaged] Tank,123(100,200) was damaged by Heavy Tank(120,210)
[09:33][destroyed] our Tank,123 has destroyed Turret(120,240)
[09:35][losses] we lost Tank x8 by Heavy Tank(120,220)x5, Tank(100,200)x3
[09:36][info] Factory,123 produced Tank,456
[09:37][new] found Tank x3, Turret T1 x1 around (100,200)
```

The raw cache keeps at most 180 game seconds and 1000 entries.

`rwx-game-observe-reset [--keep-groups]`: clears event history and the separate `unit-list --delta` baseline. By default it also clears groups; `--keep-groups` keeps them. Default stances and the set of units they were already applied to are unaffected.

The event stream and CLI queries share one collector and aggregator. Enabling the local CLI also listens on a separate loopback port; the `RWX_EVENTS_READY` log line carries the port, and the default discovery file is `agent-operation/tmp/events-port`. With a custom CLI port file the event port file sits in the same directory; the command port file is unchanged.

```powershell
python agent-operation/tools/rwx-game-events-to-jsonl.py
python agent-operation/tools/rwx-game-events-to-jsonl.py --info --allies
```

The receiver appends the NDJSON messages to `agent-operation/tmp/game-events.jsonl`; press Ctrl+C to stop, and use `--port` or `--port-file` to choose another instance. Its default event types match `game-events`, `--info` explicitly includes information events, and `--allies` applies the query command's allied rule to kills and losses. Reconnects resume at the last session and sequence in the log; the cache keeps at most 180 game seconds and 1000 transport records, so an out-of-range cursor or a queue overflow is reported as `gap` or `overflow` and cannot be guaranteed to be complete. A process restart creates a new session and old in-memory records cannot be recovered.

The protocol is UTF-8 NDJSON: the client sends `{"protocol":"rwx-events-v1","kind":"subscribe","session":null,"after":0,"info":false,"allies":false}` followed by a newline; the server replies with `hello` and then `event`, `update`, `remove`, `cursor`, `gap`, `overflow` or `reset`. `seq` is the transport sequence, while `eventId` and `revision` identify revisions of one aggregate; a `remove` carries reason `replaced` or `expired`. `event.type`, `event.description`, `gameTimeMillis` and `tick` come from real events; `session`, sequences, revisions and gap messages are transport metadata. Aggregate consumers must apply revisions and removals by event ID and must not count an `update` as a new event.

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

Start the game and the command endpoint with `:desktop:rwxCliRun` (or `:desktop:run --args="--rwx-cli"`). `rwx-ping` returns `pong` and is used for liveness checks.

`--headless`: creates no GUI, window, graphics context, audio device or extra terminal, while keeping the original game tick, pathfinding, networking, CLI and command scheduling; implies `--rwx-cli`. The graphics adapter keeps only exact sizing and on-demand CPU pixel reads, and drawing is a no-op; no hidden window or Xvfb is used. The default GUI startup behaviour is unchanged. Requires Java 25.

```powershell
.\gradlew.bat :desktop:run '--args=--headless --no-steam'
python agent-operation/tools/rwx-cli.py rwx-connection-status
```

```bash
bash ./gradlew :desktop:run --args='--headless --no-steam'
python3 agent-operation/tools/rwx-cli.py rwx-connection-status
```

`--data-dir=<directory>` sets the instance's data/identity directory, and `--cli-port-file=<file>` sets the CLI port file explicitly; multiple instances should use separate directories and port files. Pure headless distribution: `:desktop:headlessArchive`, output `desktop/build/distributions/rwx-headless.tar.gz`; on Linux start it with `bash bin/rwx-headless`. Deployment, systemd and network validation are covered in `agent-operation/SERVER.md`.

### 8.2 Some conventions

- The CLI listens on a random port at every start and, once listening succeeds, atomically writes `<repo root>/agent-operation/tmp/port` containing only the port number; it can also be set with `--cli-port-file=<path>`, `-Drwx.cli.portFile=<path>` or `RWX_CLI_PORT_FILE`. This is the local CLI port, not the multiplayer game port.
- The same default file is published by the primary instance holding the process lock; other instances never overwrite it. Each instance additionally has `port.instances/<PID>-<port>.port`, whose full path is printed in the `instancePortFile` field of `RWX_CLI_TOOL_READY`. When the primary instance exits, another instance takes over; exiting cleanup still holds the file lock, so a successor's record cannot be deleted by mistake.
- An abnormal exit may leave a stale file; clients must verify with `rwx-ping`, and an invalid port must not be used directly. The Python tool prefers an explicit port/file, then the environment variable, then the primary file, and finally the per-instance files; with several candidates it requires an explicit choice. The port-record directory is already in `.gitignore`.
- All text sent and received is **UTF-8**. Server replies are always UTF-8; if an older client sends a command line in GBK, the server decodes that line compatibly.
- When started with `--rwx-cli`, the game process's stdout/stderr also switch to UTF-8, so log files are UTF-8 as well; if your terminal itself is on the GBK code page, Chinese text viewed directly in the terminal may appear garbled, but file contents and loopback replies are unaffected.
- Quote parameter values containing spaces with double or single quotes, e.g. `--name "Lake (2p)"`; the server also accepts plain-text command lines.
- Coordinates are world coordinates by default; in a real match 1 tile = 20. Only range parameters explicitly documented as tiles use tiles, e.g. `map-place --radius`, `map-show --terrain --half-width/--half-height`; other range parameters such as `unit-list --radius` use world coordinates.

### 8.3 Command receipt semantics and the command queue

- `submitted`: the command was handed to the engine; it takes effect immediately while paused, otherwise on the next tick.
- `pending`: the command was held by the CLI and is dispatched automatically once credits or the upgrade are ready.
- `partial`: `--count N` submitted only part of the order, and the receipt carries `requested/submitted/pending/failed`.
- `error:`: the whole command failed and nothing was issued.
- Without `--queue` an existing command is replaced; with it the new order is appended to the current queue. A replacement is reported in the receipt.
- While paused or at speed 0 and not recording a replay, commands apply immediately (`appliedImmediately: true`); while recording a replay they are queued to the next tick instead, to avoid replay desync.
- Control rights, target relationships, coordinates, build capability and action availability are validated before submission.
