# Commands

Every command is `/atlas …` and requires `atlas.admin`. World arguments accept `overworld`,
`the_nether` (or `nether`), `the_end` (or `end`) and the name of an Atlas world (`arena` or
`atlas:arena`). New world names use 1–48 lowercase letters, numbers, `_` or `-`.

## Worlds

World changes are written to MariaDB and the Atlas data pack at once, but Minecraft only loads
dimensions at startup. A change therefore takes effect at the **next server start**.

| Command | What it does |
| --- | --- |
| `/atlas world list` | Every world with its generator and state: loaded, loads at next start, disabled but still loaded, or disabled. |
| `/atlas world create <name> <generator>` | Declares a new world with fresh terrain. Generators: `normal`, `flat`, `void`, `nether`, `end`. Refused if the folder already exists. |
| `/atlas world import <name> <generator>` | Declares a world whose folder staff placed in `<level>/dimensions/atlas/<name>`. The generator is used for chunks that do not exist yet. |
| `/atlas world enable <world>` / `disable <world>` | Declares or undeclares an Atlas world from the next start. Disabling is refused while portals or keep-loaded regions are in the world. Players inside a removed world are moved to the overworld spawn by Minecraft. |
| `/atlas world delete <world> confirm` | Disables the world now and deletes its folder at the next start. Refused for vanilla dimensions and while portals or keep-loaded regions remain. |
| `/atlas world clone <source> <name>` | Declares a new world as a copy of `source` (any world whose folder exists, including the vanilla ones). The folder is copied at the next start, while nothing is loaded, so the copy is consistent. The new world remembers `source` as its reset source. |
| `/atlas world reset <world> [source] confirm` | Replaces an Atlas world with a fresh copy of `source` (default: its reset source) at the next start. The old folder is replaced only after the copy succeeded. Refused while keep-loaded regions are in the world. |
| `/atlas world pending` | Lists queued delete, clone and reset operations. |
| `/atlas world cancel <id>` | Cancels a queued operation and undoes what it declared (a cancelled clone removes the new world, a cancelled delete re-enables the world). |

## Information and teleports

| Command | What it does |
| --- | --- |
| `/atlas info <world>` | Loaded/enabled state, generator, player count, settings and all flags (`*` marks an override). |
| `/atlas tp <world> [player]` | Teleports you or an online player to the world's stored spawn, or to a safe spot near its vanilla spawn. The world must be loaded. |
| `/atlas spawn [world] [player]` | Same as `tp`; without a world it uses the target's current world. |
| `/atlas setspawn [world]` | Stores your position (including direction) as the spawn of the world you stand in. |

## Rules

| Command | What it does |
| --- | --- |
| `/atlas flag <world> <flag> <on\|off\|default>` | Overrides a flag in one world; `default` removes the override. Works for vanilla dimensions too. |
| `/atlas set <world> difficulty <peaceful\|easy\|normal\|hard\|default>` | Fixed difficulty, or leave vanilla's. |
| `/atlas set <world> gamemode <survival\|creative\|adventure\|spectator\|none>` | Forced game mode for everyone without `atlas.gamemode.bypass`, applied on join, on entering the world and when changed. |
| `/atlas set <world> time <day\|noon\|night\|midnight\|0-23999\|unlock>` | Fixed time of day: the clock stops at this time and sleeping cannot skip it. `unlock` lets time run again. Not available in nether-type worlds, which have no clock. |
| `/atlas set <world> weather <clear\|rain\|thunder\|none>` | Puts the world in this weather. With the `weather` flag off it stays like that; otherwise it changes naturally afterwards. |

Flags: `hostile-mobs`, `friendly-mobs`, `plugin-hostile-mobs`, `plugin-friendly-mobs`, `tnt-damage`,
`crystal-damage`, `fire-damage`, `explosion-damage`, `pvp`, `fall-damage`, `hunger`, `drowning`,
`block-break`, `block-place`, `leaf-decay`, `crop-trampling`, `item-drop`, `item-pickup`, `weather`,
`time-cycle`, `time-skip`, `mob-griefing`, `keep-inventory`, `portals`. See
[configuration](configuration.md#flags) for what each one controls.

## Selector, portals and keep-loaded regions

`/atlas selector` delivers the Atlas Selector (a Closet item). Left-click a block for corner 1,
right-click a block for corner 2. It never breaks blocks. Selections are forgotten when you quit.

| Command | What it does |
| --- | --- |
| `/atlas portal create <name> <world>` | Creates a portal from your selection that leads to the spawn of `<world>`. |
| `/atlas portal target <name> spawn <world>` | Destination: the spawn of a world on this server. |
| `/atlas portal target <name> here` | Destination: your exact current position and direction. |
| `/atlas portal target <name> server <region> <world> [x y z [yaw pitch]]` | Destination on the other server (EU or NA), at a position or that world's spawn. |
| `/atlas portal cooldown <name> <millis>` | Time before the same player can use any portal again (default 2500, max 600000). |
| `/atlas portal sound <name> <namespaced-key\|none>` | Sound played to the traveller, for example `minecraft:block.portal.travel`. |
| `/atlas portal particle <name> <PARTICLE\|none>` | Particle shown to the traveller; only particles without extra data. |
| `/atlas portal restrict <name> <on\|off>` | When on, the portal also requires `atlas.portal.<name>`. |
| `/atlas portal fill <name> <block\|none>` | Fills the portal with a block (for example `nether_portal` or `end_gateway`) without block physics; `none` removes it. Limited to `portals.max-fill-blocks`. |
| `/atlas portal list` / `info <name>` / `delete <name>` | List, inspect or delete portals. Deleting removes the fill blocks. |
| `/atlas keeploaded set <name>` | Keeps the chunks under your selection loaded (X/Z only; at most `keep-loaded.max-chunks-per-region`). |
| `/atlas keeploaded delete <name>` / `list` | Delete a region, or list regions with the active chunk ticket count. |

`/atlas reload` reloads `messages.yml` through Campfire and re-reads worlds, portals and regions
from the database. `config.yml` changes need a restart.
