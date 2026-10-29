# Commands and tab completion

All management commands use `/atlas` and require `atlas.admin`; OP is explicitly admitted. Players normally use portals directly. The Atlas Selector is issued only by staff through `/atlas selector`; its plain-text overflow recovery message tells the recipient to run `/overflow` when inventory space is available. It has no clickable command action.

World names contain 1–48 lowercase letters, numbers, underscores or hyphens. World arguments accept the vanilla names `overworld`, `nether`/`the_nether` and `end`/`the_end`, or an Atlas world name/key. Commands run on the local backend; teleport targets are online players on that backend unless the command explicitly describes a cross-region portal destination.

## World management

| Command | Result |
| --- | --- |
| `/atlas world list` | Lists worlds and their loaded/enabled state and generator. |
| `/atlas world create <name> <generator>` | Declares a new dimension using `normal`, `flat`, `void`, `nether` or `end`; it loads after the next start. Existing folders are refused. |
| `/atlas world import <name> <generator>` | Declares an existing standalone dimension or complete native save under `<level>/dimensions/atlas/<name>`. Complete saves retain their source generator and metadata; use enable to load an initialized imported save at runtime. |
| `/atlas world enable <world>` / `disable <world>` | Loads or safely unloads an existing initialized Atlas world immediately, and persists the startup declaration. Disable refuses players, unfinished transitions, portals, keep-loaded regions and active external tickets. A drain timeout keeps the original instance; an uncertain partial close must be investigated. |
| `/atlas world close <world>` / `open <world>` | Close persists staff-only access, blocks ordinary entries and evacuates ordinary players to the configured local Toolbox `/spawn`. Staff with `atlas.world.closed.bypass` remain. Open restores ordinary access without reloading. Missing/closed spawn refuses close before changing access. |
| `/atlas world delete <world> confirm` | Queues folder deletion for startup. Vanilla worlds and worlds with portal/region references are refused; references are checked again immediately before removal. |
| `/atlas world clone <source> <name>` | Prepares a verified copy on the next start, then activates it on a later start. The source may be any world with a folder. |
| `/atlas world reset <world> [source] confirm` | Prepares and verifies a replacement; the original is retained. The existing reset source is used when omitted. |
| `/atlas world pending` | Shows queued operations. It is not a history of terminal receipts. |
| `/atlas world cancel <id>` | Cancels an operation only while its stored phase permits cancellation, restoring the prior declaration as applicable. |

Newly generated worlds require startup; initialized imported or disabled worlds can be enabled while running. Enable/disable and close/open are separate operations. Clone/reset may require separate preparation and activation starts. A proven sealed copy may resume after interruption; changed or incomplete evidence remains uncertain and is not overwritten or discarded. See [world recovery](world-recovery.md).

## Information and travel

| Command | Result |
| --- | --- |
| `/atlas info <world>` | Shows loaded/enabled state, generator, player count, settings and effective flags; `*` marks an override. |
| `/atlas tp <world> [player] [--unsafe]` | Teleports the sender or named online local player to the stored spawn or a safe nearby spot. The destination world must be loaded. |
| `/atlas spawn [world] [player] [--unsafe]` | Uses the given world or the selected player's/current sender's world. |
| `/atlas setspawn [world]` | A player stores their current position/direction in the current world (or the matching explicit world). |
| `/atlas setspawn <world> <x> <y> <z> <yaw> <pitch>` | Console form with an explicit world and complete position. |

Append `--unsafe` to travel directly to the stored spawn, or the native world spawn when none is stored, without searching for safe ground. This uses the existing Atlas admin permission and retains teleport-event and closed-world access checks. Example: `/atlas tp comet --unsafe` or `/atlas tp comet PlayerName --unsafe`.

## Rules

| Command | Result |
| --- | --- |
| `/atlas flag <world> <flag> <on\|off\|default>` | Sets or clears one of the 25 per-world flags. `default` removes the override. |
| `/atlas set <world> difficulty <peaceful\|easy\|normal\|hard\|default>` | Sets the difficulty or restores the server default. |
| `/atlas set <world> gamemode <survival\|creative\|adventure\|spectator\|none>` | Applies a forced game mode; `none` clears it. `atlas.gamemode.bypass` preserves a player's current mode. |
| `/atlas set <world> time <day\|noon\|night\|midnight\|0–23999\|unlock>` | Sets a fixed clock value or unlocks the time cycle. Not available in nether-type dimensions. |
| `/atlas set <world> weather <clear\|rain\|thunder\|none>` | Applies weather; `none` clears Atlas's explicit weather setting. The `weather` flag controls natural changes. |

Flags: `hostile-mobs`, `friendly-mobs`, `plugin-hostile-mobs`, `plugin-friendly-mobs`, `tnt-damage`, `crystal-damage`, `fire-damage`, `explosion-damage`, `pvp`, `fall-damage`, `hunger`, `drowning`, `block-break`, `block-place`, `leaf-decay`, `crop-trampling`, `item-drop`, `item-pickup`, `weather`, `time-cycle`, `time-skip`, `mob-griefing`, `keep-inventory`, `portals`, `trading`. Definitions are in [configuration](configuration.md#world-flags).

## Selector, portals and keep-loaded regions

An authorized staff member runs `/atlas selector [online-local-player]`. In game, left-click a block for corner 1 and right-click a block for corner 2. The selection is local to that player and is cleared on quit. The tool does not break blocks. Closet delivers one exact selector; Atlas does not create a replacement when delivery is unresolved.

| Command | Result |
| --- | --- |
| `/atlas portal create <name> <world>` | Uses the player's two-corner selection and initially targets the world's spawn. |
| `/atlas portal create <name> <target-world> <source-world> <x1> <y1> <z1> <x2> <y2> <z2>` | Console form with explicit source world and ordered bounds. |
| `/atlas portal target <name> spawn <world>` | Sets a same-backend world-spawn target. |
| `/atlas portal target <name> here` | Uses the player's current position and direction. |
| `/atlas portal target <name> location <world> <x> <y> <z> <yaw> <pitch>` | Sets an explicit same-backend destination; all coordinates are required in console. |
| `/atlas portal target <name> server <EU\|NA> <world> [x y z [yaw pitch]]` | Sets a cross-region destination at its spawn or a specified position. The configured proxy name and active route must match. |
| `/atlas portal cooldown <name> <millis>` | Sets the per-player portal cooldown (0–600000 ms; new portals use the configured default). |
| `/atlas portal sound <name> <namespaced-key\|none>` | Sets a registered sound or clears it. |
| `/atlas portal particle <name> <PARTICLE\|none>` | Sets a particle that requires no extra data or clears it. |
| `/atlas portal restrict <name> <on\|off>` | Toggles the additional per-portal `atlas.portal.<name>` permission. |
| `/atlas portal fill <name> <block\|none>` | Fills/removes the portal volume with a placeable block, within the configured volume cap. |
| `/atlas portal list`, `/atlas portal info <name>`, `/atlas portal delete <name>` | Lists, inspects or deletes portal records; deletion also removes its fill. |
| `/atlas keeploaded set <name>` | Uses a player's selection; only X/Z matter. |
| `/atlas keeploaded set <name> <world> <x1> <y1> <z1> <x2> <y2> <z2>` | Console form with explicit world and corners. |
| `/atlas keeploaded delete <name>` / `/atlas keeploaded list` | Deletes or lists local keep-loaded regions and active ticket counts. |
| `/atlas reload` | Reloads authored messages and rebuilds the world, portal, region and rules snapshots. Configuration changes need restart. |
| `/atlas help` | Shows the registered command help. |

## Completion and input rules

Completion follows the current command position and sender access. It suggests current world names, portal/region names, valid generators, flag/setting values, supported regions, registry-backed sound/material names and particles without extra data. Console variants include their required explicit world and coordinate arguments. Once a remote region value is invalid, completion does not suggest destination worlds for it. Coordinates and new names remain free input; Atlas validates them when executing the command.

Non-finite coordinates, reversed bounds, coordinate overflow, and portal volumes that overflow or exceed the configured cap are refused. A remote destination must name the other current EU/NA region. Permission is checked when the command executes and, for player movement, again at the entity-owned callback.

There is no Atlas inventory GUI. The in-game selector is a two-corner tool, not a menu.

Teleport attempts dispatch the standard cancellable player-teleport event on the
original entity owner before the native async move. A cancellation or changed
destination refuses the move before dismount/eject. This lets the owning Comet
policy block ordinary plugin entry while keeping staff teleport access.
