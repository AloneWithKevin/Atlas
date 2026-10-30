# Configuration

Atlas reads `plugins/Atlas/config.yml` during startup. Required configuration is validated strictly; a missing or invalid field prevents Atlas from starting. The repository template contains empty database username/password values. Fill actual credentials only in the server-local config. Diagnostics redact all database details.

Set `server.region` to the exact current Postbox region on that backend (`EU` or `NA`). These are the only supported node identities. Do not configure a third region or copy a proxy alias as a new backend identity. Keep the shared database settings consistent with the existing `pixelretreat_veyra` connection. `server.proxy-names.EU` and `.NA` must match the Velocity names for the current regional destinations.

The Atlas configuration has these sections:

| Key | Meaning and validated range |
| --- | --- |
| `server.region` | Actual Postbox identity: `EU` or `NA`. |
| `server.proxy-names.EU`, `server.proxy-names.NA` | Velocity server names for those two regions. |
| `database.host`, `port`, `name`, `username`, `password`, `ssl-mode` | Shared MariaDB settings. SSL mode is `disable`, `trust`, `verify-ca` or `verify-full`. Repository credentials are blank. |
| `datapack.pack-format` | Data-pack format for the running game version (121 for Veyra 26.3). |
| `teleport.safe-search-radius` | Search radius around a vanilla spawn: 0–64 blocks. |
| `portals.default-cooldown-millis` | Default for new portals: 0–600000 ms. |
| `portals.travel-ticket-minutes` | Cross-region ticket validity: 1–60 minutes. |
| `portals.max-fill-blocks` | Largest fill volume: 1–65536 blocks. |
| `keep-loaded.max-chunks-per-region` | Largest saved region: 1–65536 chunks. |
| `default-flags.<flag>` | Boolean default for each of the 25 world flags. |
| `workers.threads`, `workers.queue-size` | Bounded workers: 1–16 threads and queue size 1–4096. |
| `logbook.maximum-pending` | Owner-approved bound for pending facts and terminal holds, default 100000. |
| `logbook.maximum-fact-bytes` | Owner-approved per-fact payload bound, default 32768 bytes. |

The `logbook` limits are part of the approved producer contract. Durable operations reserve measurement capacity before the Atlas operation begins; when the bounded outbox has no capacity, Atlas refuses the action instead of performing an unmeasured durable change. Keep these limits positive and consistent with the matching Logbook provider. See [Logbook integration](logbook-integration.md).

## World flags

Every flag is boolean. The per-world override wins; otherwise Atlas uses `default-flags`. The shipped defaults are on except `keep-inventory`, which is off.

| Flag | Effect when off |
| --- | --- |
| `hostile-mobs` | Blocks game-origin hostile-mob spawns. |
| `friendly-mobs` | Blocks game-origin friendly-mob spawns. |
| `plugin-hostile-mobs` | Blocks plugin-origin hostile-mob spawns. |
| `plugin-friendly-mobs` | Blocks plugin-origin friendly-mob spawns. |
| `tnt-damage`, `crystal-damage` | Disables damage and block damage from the matching source. |
| `explosion-damage` | Disables explosion damage and block damage. |
| `fire-damage` | Disables fire, lava, campfire and magma damage, combustion, ignition and spread. |
| `pvp` | Prevents direct player and player-projectile damage. |
| `fall-damage`, `drowning` | Prevents the matching damage cause. |
| `hunger` | Prevents player food level decrease. |
| `block-break`, `block-place` | Blocks player block breaking or placing. |
| `leaf-decay`, `crop-trampling` | Prevents leaf decay or farmland trampling. |
| `item-drop`, `item-pickup` | Prevents player item drops or pickups. |
| `weather` | Locks weather to the explicitly configured value, or clear when none is set. |
| `time-cycle` | Stops the daylight cycle unless the world has no fixed time. |
| `time-skip` | Prevents sleeping from skipping night. |
| `mob-griefing` | Sets the `mobGriefing` game rule. |
| `keep-inventory` | Sets the `keepInventory` game rule. |
| `portals` | Blocks Atlas portal use in the world. |
| `trading` | Blocks player-to-player trades handled by Handshake in the world. |

Change a flag with `/atlas flag <world> <flag> on|off|default`; `default` removes that world's override. Runtime changes to `config.yml` require a restart.

## Messages and presentation

`messages.yml` is validated against Atlas's complete 125-key contract. Atlas registers its authored English catalog with Campfire before message-dependent features start, and delivers text in the recipient's Campfire language through the public service. Nested portal destination/bounds descriptions share that message's language and catalog snapshot. Console output stays English; command/settings identifiers and world names remain literal. `/atlas reload` reloads this catalog and the current database snapshots. Native final closed-world refusals require the matching Veyra refusal-renderer API/runtime; unavailable or stale translation retains the original static refusal. Keep all required keys and placeholders. Existing installed overrides are a separate server-side state and must be preserved when preparing a release. Selector names and lore retain their existing definitions.

Subjects and labels use the approved soft yellow `#F3E5AB`; actions use `#A8D5A2`; selector name and corner values are white. The selector's lore begins with the `atlas:lore/action` glyph. Its item/lore definitions are owned by Atlas, while Closet owns the actual font and texture files. See [content](content.md) and [asset ownership](closet-pack-ownership.md).

## Files Atlas writes

- `<level>/datapacks/atlas/`: generated `pack.mcmeta` and dimensions for enabled Atlas worlds. Do not edit by hand.
- `<level>/dimensions/atlas/<name>/`: Minecraft world data. Atlas mutates folders only for queued startup operations.
- `.NAME.atlas-ID-staging` and `.NAME.atlas-ID-original`: prepared or retained source trees during copy recovery. No automatic retention expiry is configured.
- MariaDB tables for plugin state and Logbook source journals. Do not edit these tables manually.

See [world recovery](world-recovery.md) for the files Atlas retains on uncertain copies.
