# Atlas build proposal

Status: scope discussion complete 2026-10-01 (all owner decisions recorded below and in the
feature review). Implemented as Atlas 0.1.0 on 2026-10-01; 0.1.1 (2026-10-05) adds the `trading`
world flag and redacts the database password in config diagnostics. See `parity.md` and
`verification.md`.
Atlas is the rewrite of PixelWorlds. Command `/atlas` only, without old aliases (approved).
Implementation note: data packs are read before plugins load, so the data pack is written when
staff change the world set; only folder work waits for `onLoad` (section 2 below is otherwise unchanged).
The `[seed]` argument was dropped: data pack dimensions use the server seed.
Source inventory and owner decisions: `../../.research/atlas/feature-review.md`.

## 1. Scope in one paragraph

Atlas manages the extra worlds of one Veyra server, the rules inside every world (including the
default overworld, nether and end), portals, safe world teleports and keep-loaded chunk regions.
It runs on EU and NA separately; each server has its own worlds and its own data set in the shared
MariaDB. Atlas does not call Bukkit `createWorld`/`unloadWorld` (Veyra rejects them, and the owner
decided no Veyra lifecycle API is needed). Instead Atlas declares worlds as datapack dimensions,
which Veyra loads at startup.

## 2. World lifecycle without createWorld

Evidence: `PaperWorldLoader.loadInitialWorlds` loads every `LEVEL_STEM` in the registry at
startup, including datapack dimensions, as Bukkit world `world_<namespace>_<path>` with key
`<namespace>:<path>`, stored in `world/dimensions/<namespace>/<path>`. `MinecraftServer.createLevel`
asks `Server#getGenerator(bukkitName)` for a plugin chunk generator.

- Atlas owns one datapack, `world/datapacks/atlas`, generated from MariaDB. Each Atlas world is a
  dimension `atlas:<name>` with a dimension type (overworld, nether, end) and a generator
  (`normal`, `nether`, `end`, `flat`, `void`). `void` is a flat generator without layers in the
  `minecraft:the_void` biome, so no plugin generator or `bukkit.yml` entry is needed.
- Lifecycle commands record a **pending operation** in MariaDB. In `onLoad`, before Veyra loads any
  world, Atlas reads that server's pending operations, performs the file work (delete, clone,
  reset, import), rewrites the datapack and records each result. The worlds are then loaded by
  Veyra itself. Because the source world is not loaded at that moment, clone and reset copy a
  consistent snapshot (fixes old defect 5).
- Old command → new behaviour:

| Old | New | Effect |
| --- | --- | --- |
| create | `world create <name> <generator>` | Available after the next restart; data pack dimensions use the server seed |
| import | `world import <name> <generator>` | Adopts an existing folder in `dimensions/atlas/<name>` after restart; the generator applies to chunks that do not exist yet |
| load / auto-load | `world enable <name>` | Loaded from the next restart on |
| unload | `world disable <name>` | Not loaded from the next restart on; players inside are moved to the overworld spawn by vanilla |
| delete | `world delete <name> confirm` | Disabled and folder deleted at the next startup |
| clone | `world clone <source> <target>` | Verified preparation at the next start, activation at the following start |
| reset | `world reset <name> [source] confirm` | Target undeclared before preparation; original retained, verified copy loads at the following start |
| — | `world pending`, `world cancel <id>` | Show or cancel queued operations |

- Overworld, nether and end can never be deleted, disabled, cloned into or reset.
- Owner decision 2026-10-06: proven prepared copies may finish automatically after
  interruption. Clone/reset preparation and activation may use two starts. See
  [world recovery](world-recovery.md) for durable phases, failure handling and limits.
- Keep-loaded regions still block disable, delete and reset of their world.

## 3. World rules

All hard-coded name rules are removed (decision 2). Every world, including the three defaults,
has a row with its flags and settings. Former special cases become ordinary values staff can set.

Flags (all on/off): the 20 old flags, plus

| New flag | Default | Replaces |
| --- | --- | --- |
| `plugin-hostile-mobs` | on | Plugin spawns of hostile mobs (decision 4) |
| `plugin-friendly-mobs` | on | Plugin spawns of friendly mobs (decision 4) |
| `portals` | on | Hard-coded "no portals in arena worlds" |
| `time-skip` | on | Hard-coded cancelled sleeping in void/spawn worlds |
| `trading` | on | New integration: player-to-player trading (Handshake) |

Mob flags (decision 4): `hostile-mobs`/`friendly-mobs` block every spawn reason (natural, spawner,
spawn egg, breeding, `/summon`, ...) except `CUSTOM`; `CUSTOM` (plugin spawns such as Jar releases
and custom-mob plugins like PixelMobs) is decided only by the two `plugin-*` flags. Nest summons
through vanilla creature spawners and their `CreatureSpawnEvent`/`SpawnerSpawnEvent` game reasons,
so Nest spawns follow the normal `hostile-mobs`/`friendly-mobs` flags instead.

Settings: difficulty, forced game mode (with staff bypass, decision 5), fixed time (ticks or off;
replaces the pinned 6000 of void/spawn worlds), weather (clear/rain/thunder, optionally locked),
spawn point. Settings are applied once when they change and at startup, on the global region
scheduler; entering a world no longer resets its clock or weather (fixes defect 2). The broken
`lock` argument is fixed (defect 1).

## 4. Portals (extended, decision 6)

- Selection with the Atlas selector (Closet item, decision 9), same tool as keep-loaded regions.
- Destination per portal: the target world's spawn, an exact saved location, or a world and
  location on the other server (EU/NA).
- Cross-server portals: a travel ticket in MariaDB and a BungeeCord `Connect` to Velocity, the same
  pattern Fence uses; the target server consumes the ticket on join and teleports on the player's
  entity scheduler. Tickets expire after a configurable time. Needs a Velocity route to test.
- Per portal: cooldown (default 2.5 s), optional sound and particle effect.
- Per-portal permission (owner choice 2026-10-01): a portal can be marked restricted; it then also
  requires `atlas.portal.<name>`. Atlas registers that node at runtime with default `op`.
- Visible portal blocks (owner choice 2026-10-01): a portal can be filled with a chosen block
  (for example nether portal, end gateway or a decorative block) on its region thread. Vanilla
  portal travel from those blocks is cancelled inside Atlas portals, so only Atlas decides the
  destination. Removing the portal or the fill restores air.
- Requires `atlas.portal.use` and the world flag `portals`. Lookups use a per-world chunk index
  instead of scanning every portal on every block move.

## 5. Teleports (decision 8)

One `teleportAsync` on the player's entity scheduler. The safe-spot search runs on the target
region after `getChunkAtAsync`. No sync teleport, no console `execute`, no Bukkit scheduler and
no INFO log per teleport. Failure gives the player one message.

## 6. Keep-loaded regions

Same behaviour as before (wand, `set`, `delete`, `list`, chunk cap 1024 configurable, X/Z only),
stored in MariaDB per server. Tickets are added and released on the global region scheduler.

## 7. Commands and permissions (decision 3)

Command `/atlas`, no old aliases (pre-live rule).

| Node | Default | Allows |
| --- | --- | --- |
| `atlas.admin` | op | Every management command and the selector |
| `atlas.portal.use` | true | Using portals |
| `atlas.gamemode.bypass` | op | Keeping your own game mode in a world with a forced game mode (decision 5; own node approved 2026-10-01) |
| `atlas.portal.<name>` | op | Using a portal marked restricted; registered at runtime per restricted portal |

Subcommands: `world …` (section 2), `tp <world> [player]`, `spawn [world] [player]`,
`setspawn [world]`, `info <world>`, `flag <world> <flag> <on|off|default>`,
`set <world> <difficulty|gamemode|time|weather> <value>`, `portal …`, `keeploaded …`,
`selector`, `reload`, `help`. Tab completion shows only what the sender may use.

## 8. Data model (MariaDB, decision 7)

Database `pixelretreat_veyra`; every table has a `server` column (`EU`/`NA` from Postbox).

| Table | Content |
| --- | --- |
| `atlas_world` | name, built-in yes/no, dimension type, generator, seed, enabled, reset source, spawn, difficulty, game mode, fixed time, weather, created at |
| `atlas_world_flag` | world, flag, value (only values that differ from the configured default) |
| `atlas_portal` | name, world, bounds, destination kind/server/world/location, cooldown, effect, restricted, fill block |
| `atlas_keep_loaded_region` | name, world, chunk bounds |
| `atlas_pending_operation` | id, kind, world, source, requested by/at, state, result |
| `atlas_travel_ticket` | id, player, origin, target, destination, created/expires/consumed at |

No old data exists: no PixelWorlds JAR or configuration is installed anywhere and no instance has
extra dimensions. Nothing to import.

## 9. Threads

- MariaDB: small HikariCP pool and a bounded executor; never on a region or entity thread except
  the bounded startup read in `onLoad`, before any world ticks.
- Listeners read an immutable in-memory snapshot of this server's rules, replaced atomically
  after every change; no locking on hot paths.
- World settings, game rules, time, weather and chunk tickets: global region scheduler.
- Teleports and per-player game mode: the player's entity scheduler.
- Startup file work: bounded parallel copy/delete workers, finished before worlds load.
- All plugin-owned tasks are cancelled on disable.

## 10. Dependencies and integrations

`depend: [Campfire, Closet, Postbox]`. No Compass menu (PixelWorlds had none).

| Consumer | Contract |
| --- | --- |
| Jar (PixelCatch) | Typed Bukkit service `AtlasRules`: `allowsSpawn(World, EntityType, SpawnOrigin)` replaces `PixelWorldsApi.allowsCreatureSpawn`; a release checks `allowsSpawn(..., PLUGIN)` and its `CUSTOM` spawns follow the `plugin-*` flags. |
| PixelMobs | Its `CUSTOM` spawns follow the `plugin-hostile-mobs`/`plugin-friendly-mobs` flags. |
| Nest | Uses vanilla creature spawners and their `CreatureSpawnEvent`/`SpawnerSpawnEvent` game reasons, so its spawns follow the normal `hostile-mobs`/`friendly-mobs` flags. |
| Handshake | Player-to-player trading: `AtlasRules.flagEnabled(world, AtlasFlag.TRADING)` with the `trading` flag, default on. |
| Colosseum | No runtime world creation/unload (owner decision). The arena model still requires owner discussion: predeclared worlds with a startup reset, or a bounded pool of loaded arenas. |

The per-consumer rows are tracked in `../../PLUGIN_INTEGRATIONS.md` (Atlas → Jar,
Atlas → Nest/PixelMobs, Atlas → Handshake, Colosseum).

## 11. Tests

Unit: flag resolution, spawn-origin mapping, datapack JSON generation, pending-operation state
machine, chunk maths, portal index, message catalog. MariaDB: every repository, ticket consumption
once, pending operations. Startup: file operations against a temporary dimensions root. Server:
single VeyraTest01 start; a cross-server portal needs a Velocity pair.
