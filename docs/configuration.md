# Configuration

## Message catalog

Atlas declares 113 required message keys: the original 104 plus eight
diagnostic/operation/destination templates. The travel-ticket warnings and delete
re-enabled reason now use the catalog, as do local/remote spawn/location and portal
bounds descriptions. Destination placeholders are plain input; enclosing messages
retain their existing colors. Coordinate display still truncates to integers.
Messages can be overridden and reloaded through the existing Campfire reload.
The additional `selector.overflow` key reports confirmed Market storage and
directs the recipient to `/overflow`. Existing approved messages are unchanged.
Do not remove required keys. The release installer adds missing bundled keys while
preserving existing overrides; database credentials remain in config.yml only.

`plugins/Atlas/config.yml` is read once at startup; every key is required and an invalid value
stops Atlas. Keep it identical on EU and NA apart from `server.region` and the credentials. The
distributed file has an empty username and password; fill them in on the server only.

For an existing installation, add `default-flags.trading: true` before this version starts. A
missing key stops Atlas; there is no migration, fallback or guessed default.

| Key | Meaning |
| --- | --- |
| `server.region` | `EU` or `NA`. Must equal Postbox's region; world rows are stored under it. Selector delivery intents follow the player across nodes. |
| `server.proxy-names.EU`, `.NA` | Velocity server names used by cross-server portals. |
| `database.host`, `port`, `name`, `username`, `password`, `ssl-mode` | Shared MariaDB connection (`pixelretreat_veyra`). `ssl-mode`: `disable`, `trust`, `verify-ca` or `verify-full`. |
| `datapack.pack-format` | Data pack format of the running Minecraft version (121 for 26.3). |
| `teleport.safe-search-radius` | Blocks (0–64) around a vanilla spawn searched for a safe landing spot. |
| `portals.default-cooldown-millis` | Cooldown of new portals (0–600000). |
| `portals.travel-ticket-minutes` | Validity of a cross-server travel ticket (1–60). |
| `portals.max-fill-blocks` | Largest portal volume that may be filled (1–65536). |
| `keep-loaded.max-chunks-per-region` | Largest keep-loaded region (1–65536 chunks). |
| `default-flags.<flag>` | Value of each flag in worlds that do not override it. |
| `workers.threads`, `workers.queue-size` | Bounded database/file workers (1–16 threads, queue 1–4096). |

## Messages and item text

`messages.yml` contains every chat/console and selector name/lore key. Chat uses Campfire's
ordinary-text default, `#F3E5AB` for subjects/labels and `#A8D5A2` for actions. Selector
name/values are white and click instructions use `#A8D5A2`. Atlas 0.1.2 changes colors only;
existing keys, English wording and line layout are preserved. Custom installed messages
need review during an authorized release; a new JAR does not overwrite them automatically.

Atlas 0.1.3 prefixes the existing selector action line with the registered
`atlas:lore/action` mini-glyph. `closet.yml` sets `assets-root: content`; the external
font/bitmap must be installed in the Atlas data folder independently of the JAR.
Registration supplies them to Closet's required pack input. Keep the matching glyph
prefix, manifest and external content together; a matching client pack release is
required for rendering. See [external content](content.md).

## Flags

| Flag | Default | When off |
| --- | --- | --- |
| `hostile-mobs` | on | Hostile creatures do not appear through any game mechanic (natural, spawner, egg, breeding, `/summon`, …). |
| `friendly-mobs` | on | The same for animals, ambient and water creatures, villagers and golems. |
| `plugin-hostile-mobs` | on | Hostile creatures spawned by plugins (spawn reason `CUSTOM`) are refused. |
| `plugin-friendly-mobs` | on | The same for friendly creatures spawned by plugins. |
| `tnt-damage` / `crystal-damage` | on | TNT (and TNT minecarts) / end crystals neither damage entities nor break blocks. |
| `explosion-damage` | on | No explosion damages entities or breaks blocks. |
| `fire-damage` | on | No fire, lava, campfire or magma damage, no burning, igniting or fire spread. |
| `pvp` | on | Players cannot hurt players directly or with projectiles. |
| `fall-damage`, `drowning` | on | No fall / drowning damage for any entity. |
| `hunger` | on | Players' food level does not drop. |
| `block-break`, `block-place` | on | Breaking / placing blocks is refused. |
| `leaf-decay`, `crop-trampling` | on | Leaves do not decay / farmland is not trampled. |
| `item-drop`, `item-pickup` | on | Players cannot drop / pick up items. |
| `weather` | on | The weather stays as set (clear if nothing is set). |
| `time-cycle` | on | The clock stops. |
| `time-skip` | on | Sleeping cannot skip the night. |
| `mob-griefing` | on | The `mobGriefing` game rule is off. |
| `keep-inventory` | off | (When on) the `keepInventory` game rule is on. |
| `portals` | on | Atlas portals in this world do nothing. |
| `trading` | on | Player-to-player trades through Handshake are refused in this world. |

## Files Atlas writes

- `<level>/datapacks/atlas/`: `pack.mcmeta` and one `data/atlas/dimension/<name>.json` per enabled
  Atlas world. Generated from MariaDB; do not edit by hand.
- `<level>/dimensions/atlas/<name>/`: Atlas world folders, written by Minecraft. Atlas copies or
  deletes them only during startup, for queued operations.
- Same-parent .NAME.atlas-ID-staging and .NAME.atlas-ID-original directories retain
  prepared and original trees. No automatic cleanup period is configured.
  See [world recovery](world-recovery.md). No new configuration keys are required.
