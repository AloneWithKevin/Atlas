# Atlas final report

The selector now uses Closet's shared inventory-first delivery contract. Exact
confirmed capacity remainder goes to Market; pending/offline operations retain
their original ID in the shared caller journal and resume after join/restart or
a repeated command. Closet owns the frozen goods and durable receipts; Atlas
retains selector behavior/access. Current dependency pins compile and assemble
successfully. No new permissions, commands, configuration values or world
behavior are added. Server rollout and actual game acceptance are separate.

Atlas 0.1.8 (2026-10-06) settles queued worker results on shutdown, waits for
region ticket reconciliation before reporting reload completion, discards older
reads and prevents delayed callbacks from restoring released tickets. Six targeted
lifecycle regressions pass. API, catalog, permissions, content and database contracts
remain unchanged; see [verification](verification.md).

Atlas 0.1.7 (2026-10-06) binds the remaining eight fixed console/target templates
to messages.yml. The catalog now contains 112 required keys. Configured overrides
drive all four destination formats, bounds and warnings; exceptions and credentials
are not appended to diagnostics. Delete refusal records a stable message-key
meaning, rendered when reported; historical receipts are untouched. Lifecycle,
travel, permissions, coordinates, assets and player actions are unchanged.
Full verification passes 69 tests, including 20 isolated database cases.

Atlas 0.1.6 (2026-10-06) reviews the remaining 97 catalog entries and gives 42
values warmer English, including arrival and permission messages and selector
lore. Existing exact approvals, all 104 keys, placeholders, palette, glyph, units,
conditions and gameplay are preserved. Precise help, information, console text,
destructive confirmations and tool identity remain clear. This is an editorial
release; new diagnostic/formatter centralization contracts remain separate.

Atlas 0.1.5 (2026-10-06) adds atomic lifecycle queue/cancellation and bounded,
checksum-verified clone/reset preparation. Proven publication resumes after an
interruption; uncertain trees stay disabled and original reset data is retained.
Preparation and activation use separate starts. Early startup failure uses generic
bundled console text without exposing exception details. Three lifecycle messages
were explicitly approved on 2026-10-06; remaining text review stays open.
The complete isolated MariaDB build passes 64 tests without skips. Public API,
configuration keys, permissions and content assets are unchanged. See
[world recovery](world-recovery.md) and [verification](verification.md).

Atlas 0.1.4 (2026-10-06) makes the four ordinary player portal messages friendlier
and moves content documentation outside Closet's validated asset root.
Atlas 0.1.3 (2026-10-05) adds a registered selector mini-glyph to the 0.1.2 message-palette
corrections and the verified 0.1.1 trading patch
on Atlas 0.1.0 (2026-10-01). Atlas is the
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

- **Atlas 0.1.4** (2026-10-06): six resource tests passed, zero failures/errors/skips,
  including real Campfire/Closet validation after moving the content README.
  Exactly four catalog values changed; placeholders, colors, permissions,
  gameplay/API bytecode and API-JAR hash remain unchanged. See `verification.md`.
- **Atlas 0.1.0** (2026-10-01): 26 tests, 26 passed, 0 skipped, including the real-MariaDB
  repository test; recorded in `verification.md`. The in-game test is still pending.
- **Atlas 0.1.1** (2026-10-05): new and extended tests cover the trading flag key/default, per-world
  override and clearing, the `AtlasRules` answers before readiness and after reloads, rejected
  missing/wrong-type config, password redaction, flag completion/access and the reload supplier
  and result messages, plus trading row persistence in the MariaDB test. **38 tests passed,
  0 failures, 0 errors, 0 skipped**, including isolated real MariaDB. Runtime/API JAR inspection
  passed; `verification.md` records the command, artifact checks and SHA-256 hashes.
- **Atlas 0.1.2** (2026-10-05): 40 tests passed, 0 failures, 0 errors, 0 skipped,
  including real MariaDB and real Campfire/Closet validation. All 104 message keys checked;
  51 color-only changes preserve English text and layout. Gameplay/API bytecode and API-JAR
  hash remain identical to 0.1.1. No GUI/art assets were missing or added.
- **Atlas 0.1.3** (2026-10-05): 41 tests passed, zero failures/errors/skips. The
  existing action line starts with a reused transparent 16x16 glyph, registered through
  Closet with an owner font and external content. Real registry/pack-input, font-scope,
  bitmap and Campfire checks pass. No gameplay/API bytecode or permission changes.

## Open questions and known limits

The 0.1.2 presentation patch checks every message path and the complete legacy interface/
asset set; it changes colors only and preserves approved text, placeholders and layout.
No legacy GUI or artwork is missing. The later small-glyph instruction adds the
selector action icon without changing existing words or adding lore lines. See
[the presentation audit](presentation-audit.md). JAR, messages, manifest and external
content/pack need a separately authorized release before client acceptance.

- In-game testing on a Veyra server is done by the owner and has not happened yet.
- Installed `config.yml` files need `default-flags.trading` before Atlas 0.1.1 starts; a missing key
  stops startup by design (no fallback), and the distributed default is `true`.
- Cross-server portals still need a Velocity route and an in-game test.
- The owner removed Colosseum's arena-model discussion from Atlas's completion gates
  and deferred test-server Velocity routing. Game acceptance remains open.
- Handshake's remaining preconditions (Campfire ignore API, installed dependencies) are listed in
  its own handoff, not in this plugin.
