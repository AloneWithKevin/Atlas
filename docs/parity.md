# Comparison with PixelWorlds

Source inventory: `.research/atlas/feature-review.md` (PixelWorlds `4fd52b9`). Owner decisions of
2026-10-01 are numbered D1–D13 there. "Changed" lines were approved by those decisions.

| # | PixelWorlds feature | Atlas | Status |
| --- | --- | --- | --- |
| 1 | `/pw create` | `/atlas world create <name> <generator>`; active after the next start (D1). Per-world seeds are not available for data pack dimensions; new worlds use the server seed. | Changed (D1) |
| 2 | Void generator, `/pw void` | `void` generator (flat, no layers, void biome). Switching an existing world's generator is replaced by declaring it with the wanted generator; existing chunks never change. | Changed (D1, D2) |
| 3 | `/pw import` | `/atlas world import <name> <generator>` for folders in `dimensions/atlas/`. | Kept, restart-applied |
| 4 | `/pw load`, auto-load | `/atlas world enable`; every enabled world loads at startup. | Changed (D1) |
| 5 | `/pw unload [save\|nosave]` | `/atlas world disable`; unloads at the next start (Minecraft saves normally). Keep-loaded regions still block it; portals now block it too. | Changed (D1) |
| 6 | `/pw delete … confirm` | `/atlas world delete … confirm`; folder deleted at the next start. Same guards. | Changed (D1) |
| 7 | `/pw clone` | `/atlas world clone`; copied at startup from an unloaded source (fixes the inconsistent live copy). Paper's world identity is not copied. Preparation and activation use two starts with durable evidence. | Kept, improved |
| 8 | `/pw reset … confirm` | `/atlas world reset … confirm`; copy is prepared beside the undeclared target, checksum-verified and published with atomic moves; original retained. Activation follows on the next start. See [recovery](world-recovery.md). | Kept, improved |
| 9 | `/pw tp <world> [player]` | `/atlas tp`; one async teleport, no fallback chain, no console logging (D8). The world must already be loaded (no on-demand loading on Veyra). | Changed (D8) |
| 10 | `/pw spawn [world] [player]` | `/atlas spawn`. | Kept |
| 11 | `/pw setspawn [world]` | `/atlas setspawn`; must be run in the world itself (the old command stored another world's coordinates). Stored spawn keeps its own direction. | Kept, fixed |
| 12 | `/pw info` | `/atlas info` plus `/atlas world list`. Folder path no longer shown. | Kept |
| 13 | 20 flags | Same 20 flags plus `plugin-hostile-mobs`, `plugin-friendly-mobs` (D4), `portals`, `time-skip` (D2) and `trading` (Handshake integration, 0.1.1); `default` removes an override. | Extended |
| 14 | `/pw difficulty` | `/atlas set <world> difficulty`, plus `default`. | Kept |
| 15 | `/pw gamemode` (no bypass) | `/atlas set <world> gamemode`, plus `none`; `atlas.gamemode.bypass` (D5, D10). | Changed (D5) |
| 16 | `/pw time … [lock]`, `lock`/`unlock` | `/atlas set <world> time` = fixed time; `time-cycle` flag for the clock. The broken `lock` argument is gone; the clock no longer jumps back when players enter. | Kept, fixed |
| 17 | `/pw weather … [lock]`, `lock`/`unlock` | `/atlas set <world> weather` plus the `weather` flag. Lock bug fixed. | Kept, fixed |
| 18 | Portals (pos at feet, spawn target, 2.5 s cooldown) | Selector-based; spawn, exact location or other-server target; per-portal cooldown, sound, particle, restriction and fill blocks (D6, D12); chunk index lookup. | Extended |
| 19 | Keep-loaded regions and wand | Same behaviour and limit; regions in MariaDB; the wand is the Closet item `atlas:selector` (D9). | Kept |
| 20 | `/pw reload` | `/atlas reload` now also reloads messages. Config changes need a restart. | Kept, improved |
| 21 | Help and tab completion by permission | Kept; one admin node. | Kept |
| 22 | PixelChat messages | Campfire `messages.yml`, validated at startup and by tests. | Kept |
| 23 | `PixelWorldsApi` and reflective `worldService()` | Typed `AtlasRules` service. The reflective world service (PixelArena) has no successor: runtime world creation is not possible on Veyra. | Changed (D1, D13) |
| 24 | Hard-coded `spawn` world | Ordinary flags and settings staff set per world (D2). | Changed (D2) |
| 25 | Hard-coded arena rules | Ordinary flags (`block-break`, `block-place`, `portals`) and the `void` generator (D2). | Changed (D2) |
| 26 | Void worlds pin time/weather | Ordinary settings: `set time noon`, `weather` flag off, `time-skip` off (D2). | Changed (D2) |

## Permissions

The 27 PixelWorlds nodes (`pixelworlds.*`, all `default: false`) are replaced by `atlas.admin`,
`atlas.portal.use`, `atlas.gamemode.bypass` and runtime `atlas.portal.<name>` (D3, D10, D12).

## Storage

`config.yml` world, portal and region sections → MariaDB tables per region (D7). No live data
existed to import.

## Known limits

The 2026-10-05 full source/configuration and content/asset presentation comparison found no
legacy GUI to restore. The command-only interfaces and vanilla selection tool are accounted
for in [the presentation audit](presentation-audit.md); approved text and behavior remain intact.

- Every world lifecycle change needs a server restart, done by the owner.
- A world can only be cloned once its folder exists, so after it has been loaded once.
- Cross-server portals need a Velocity route; VeyraTest01 currently has none.
- Until Atlas has loaded its rules after a start, guarded gameplay and mob spawns are refused.

## Selector delivery and overflow integration

The 2026-10-07 audit covers all Atlas physical issuance paths. The only path is
the staff command `/atlas selector`: `AtlasPlugin.giveSelector` submits one
`atlas:selector` through `ClosetGrants`, with action `atlas.selector` and one
operation UUID per invocation. Atlas asynchronously registers its manifest with
Closet before enabling the command. Closet owns the item factory, preparation,
serialization and grant; Atlas owns access checks and corner-selection behavior.
There are no Atlas item rewards, refunds, returns or public physical-grant adapters.
World drop restrictions guard normal gameplay and are not item issuance.

The delivered Atlas 0.1.8 and Closet 0.1.5 currently use an all-or-nothing grant.
Closet plans storage-slot stacking/empty-slot placement, but refuses the entire
request with `NOT_APPLIED` / `CAPACITY_EXCEEDED` when any amount does not fit.
For this one unstackable selector, the exact undelivered amount is one. It is not
stored in Market overflow. Atlas reports `selector.pending` for every non-APPLIED
outcome and does not retain the UUID or query `ClosetGrants.outcome`. The current
invocation does not automatically retry; an unresolved invocation must not be
replaced by a fresh grant ID as a recovery mechanism.

The required provider/consumer release is tracked in the workspace
`PLUGIN_INTEGRATIONS.md` and the central item-issuance audit. Closet leads the
shared fresh-item and exact-existing-stack grant/overflow contract; Market owns
overflow storage, and Controller retains its approved transfer/payment/reward
coordination. Atlas gameplay does not move into Closet.

Atlas's consumer update is pending a verified public contract that preserves the
original operation ID, freezes exact item bytes/components, proves the inventory
placement/rest partition, reports the durable overflow receipt and reconciles
UNKNOWN/lost acknowledgements without new goods or IDs. Atlas must retain the
original operation identity and handle delivery/pending outcomes distinctly through
that contract. Exact existing or enriched stacks must use exact-byte redelivery,
never reconstruction from a later item template. Atlas currently has no such
existing-stack return path.

Current Market source exposes `deliverStored`, but the audited installed
Market 0.1.0 public artifact exposes only `deliver(ItemStack list)` and `receipt`.
Matching Market and any required Veyra provider releases are not yet verified as
delivered for this integration. New dependencies and consumer changes must be
verified together against the released public artifacts before Atlas release.
Until then, preserve existing pending outcomes and any retained operation IDs;
do not add drops, fire-and-forget deposits, guessed compensation or private APIs.
This documentation records pending work, not implemented overflow behavior.
