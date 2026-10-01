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
| 7 | `/pw clone` | `/atlas world clone`; copied at startup from an unloaded source (fixes the inconsistent live copy). Paper's world identity is not copied. | Kept, improved |
| 8 | `/pw reset … confirm` | `/atlas world reset … confirm`; copy is made beside the target and swapped in only after success. | Kept, improved |
| 9 | `/pw tp <world> [player]` | `/atlas tp`; one async teleport, no fallback chain, no console logging (D8). The world must already be loaded (no on-demand loading on Veyra). | Changed (D8) |
| 10 | `/pw spawn [world] [player]` | `/atlas spawn`. | Kept |
| 11 | `/pw setspawn [world]` | `/atlas setspawn`; must be run in the world itself (the old command stored another world's coordinates). Stored spawn keeps its own direction. | Kept, fixed |
| 12 | `/pw info` | `/atlas info` plus `/atlas world list`. Folder path no longer shown. | Kept |
| 13 | 20 flags | Same 20 flags plus `plugin-hostile-mobs`, `plugin-friendly-mobs` (D4), `portals`, `time-skip` (D2); `default` removes an override. | Extended |
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

- Every world lifecycle change needs a server restart, done by the owner.
- A world can only be cloned once its folder exists, so after it has been loaded once.
- Cross-server portals need a Velocity route; VeyraTest01 currently has none.
- Until Atlas has loaded its rules after a start, guarded gameplay and mob spawns are refused.
