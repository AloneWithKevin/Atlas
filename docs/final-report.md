# Atlas final report

Atlas 0.1.1 (2026-10-05) is an incremental patch release on Atlas 0.1.0 (2026-10-01). Atlas is the
rewrite of PixelWorlds: it manages the extra worlds of one Veyra server, the rules inside every
world, portals, safe teleports and keep-loaded chunk regions. `parity.md` holds the line-by-line
PixelWorlds comparison with the numbered owner decisions; `verification.md` holds the test evidence.

## Behaviour

- **Worlds** are data pack dimensions written from MariaDB; Minecraft loads them at startup. Staff
  declare, enable, disable, delete, clone and reset worlds with `/atlas world …`; folder work runs
  in `onLoad` through `atlas_pending_operation`, before any world is loaded.
- **Rules** are 25 per-world flags with configured defaults, per-world overrides set with
  `/atlas flag <world> <flag> <on|off|default>`, plus difficulty, forced game mode (with the
  `atlas.gamemode.bypass` exception), fixed time, weather and spawn settings. Vanilla dimensions
  have rows too; there are no hard-coded world-name rules.
- **Trading** (`trading`, default on) is the per-world rule Handshake asks before and during a
  trade. It is a normal flag: independent per world, overridable and clearable through the shared
  flag command, and visible in `/atlas info`.
- **Portals** are selector bounds with a target (world spawn, exact location or the other region),
  cooldown, sound, particle, optional `atlas.portal.<name>` restriction and fill block. Vanilla
  portal travel inside an Atlas portal is cancelled so only Atlas decides the destination.
- **Teleports** use one `teleportAsync` on the player's entity scheduler with a region-scheduled
  safe-spot search. **Keep-loaded regions** are chunk rectangles held with plugin-owned chunk
  tickets.

## Data

All state is in the shared MariaDB database `pixelretreat_veyra`, scoped by the `server` column
(`EU`/`NA`): `atlas_world`, `atlas_world_flag`, `atlas_portal`, `atlas_keep_loaded_region`,
`atlas_pending_operation` and `atlas_travel_ticket`. No PixelWorlds data existed to import. The
plugin keeps no data files; the local `config.yml` holds settings and credentials only, with an
empty password default in the repository.

## Threads

Database and data pack work runs on the bounded `AtlasWorkers` pool (rejecting when full). Rule
reads are lock-free against an immutable `RulesSnapshot` in an `AtomicReference`. Game rules,
time, weather, difficulty, world spawn and chunk tickets run on the global region scheduler;
player messages, game modes, teleports and portal effects on the player's entity scheduler. No
game thread waits on a future. Queued folder work runs before worlds load; all plugin-owned work
is cancelled on disable.

## Integrations

- Jar release and `CUSTOM` PixelMobs spawns follow the `plugin-hostile-mobs`/`plugin-friendly-mobs`
  flags through `AtlasRules.allowsSpawn`; Nest summons through vanilla creature spawners and
  follows the normal `hostile-mobs`/`friendly-mobs` flags.
- Handshake resolves `AtlasFlag.fromKey("trading")` and asks `flagEnabled(world, TRADING)`.
- Colosseum: Atlas has no runtime world creation/unload by owner decision. Whether arenas use
  predeclared worlds with a startup reset or a bounded pool of loaded arenas still requires owner
  discussion.

These rows are tracked in `../../PLUGIN_INTEGRATIONS.md`.

## Removed by owner decision

The 27 `pixelworlds.*` nodes are replaced by `atlas.admin`, `atlas.portal.use`,
`atlas.gamemode.bypass` and the runtime `atlas.portal.<name>` node; rank allocation is recorded in
`PixelRetreat - Documentation/Ranks.md`, not duplicated here. Runtime world creation/unload and
the reflective `worldService()` have no successor. The proposal's `[seed]` argument was dropped.

## Verification

- **Atlas 0.1.0** (2026-10-01): 26 tests, 26 passed, 0 skipped, including the real-MariaDB
  repository test; recorded in `verification.md`. The in-game test is still pending.
- **Atlas 0.1.1** (2026-10-05): new and extended tests cover the trading flag key/default, per-world
  override and clearing, the `AtlasRules` answers before readiness and after reloads, rejected
  missing/wrong-type config, password redaction, flag completion/access and the reload supplier
  and result messages, plus trading row persistence in the MariaDB test. **38 tests passed,
  0 failures, 0 errors, 0 skipped**, including isolated real MariaDB. Runtime/API JAR inspection
  passed; `verification.md` records the command, artifact checks and SHA-256 hashes.

## Open questions and known limits

- In-game testing on a Veyra server is done by the owner and has not happened yet.
- Installed `config.yml` files need `default-flags.trading` before Atlas 0.1.1 starts; a missing key
  stops startup by design (no fallback), and the distributed default is `true`.
- Cross-server portals still need a Velocity route and an in-game test.
- Colosseum's arena model (owner discussion), Velocity routing and game acceptance remain external
  gates.
- Handshake's remaining preconditions (Campfire ignore API, installed dependencies) are listed in
  its own handoff, not in this plugin.
