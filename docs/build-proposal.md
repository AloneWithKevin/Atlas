# Atlas implementation scope

Atlas is the approved PixelWorlds replacement. The full file-by-file legacy source inventory and owner decisions D1–D13 remain in [the preserved feature review](../../.research/atlas/feature-review.md). This document describes the current implementation after the 13 October 2026 final audit; it is not a request to expand Veyra or server scope.

## World and rules behavior

Atlas stores each backend's worlds and settings in the shared MariaDB database and declares enabled Atlas worlds in a generated data pack. Veyra reads dimensions before plugin startup; Atlas therefore applies world creation/import/enabling at a server start and performs file operations during `onLoad`. Runtime `createWorld`/`unloadWorld` support was declined by Ferry. Clones and resets use durable, checksum-verified preparation and later activation. Uncertain data is retained and the target remains disabled.

All 25 `AtlasFlag` values are per-world: `hostile-mobs`, `friendly-mobs`, `plugin-hostile-mobs`, `plugin-friendly-mobs`, `tnt-damage`, `crystal-damage`, `fire-damage`, `explosion-damage`, `pvp`, `fall-damage`, `hunger`, `drowning`, `block-break`, `block-place`, `leaf-decay`, `crop-trampling`, `item-drop`, `item-pickup`, `weather`, `time-cycle`, `time-skip`, `mob-griefing`, `keep-inventory`, `portals` and `trading`. Per-world overrides win over configured defaults. Forced game mode has the `atlas.gamemode.bypass` exception. There are no hard-coded spawn/arena world names.

## Portals, teleports and keep-loaded regions

The Atlas Selector defines a two-corner cuboid. A portal may target a local world spawn, an exact location, or the corresponding EU/NA region. Portals support a cooldown, optional registered sound and supported particle, a per-name access restriction and optional ordinary-block fill. Region lookup is compact per world and range checked. Cross-region travel uses a conditional single-use ticket and Postbox/Velocity route. Permission is checked at command execution and at the player-owned movement callback; unknown arrival remains unknown.

Teleports use the player's entity scheduler and `teleportAsync`, after a safe-location search on the owning region. A target world must already be loaded. Keep-loaded regions are bounded X/Z chunk rectangles reconciled through Folia chunk tickets.

## Items and interfaces

Atlas defines one Closet item, `atlas:selector`, for staff selection. Closet is the sole owner of every pack asset. Atlas creates no custom blocks, recipes, player rewards or inventory GUI. Player travel is direct portal use; all `/atlas` management commands require `atlas.admin`.

## Public contracts

- `AtlasRules` and `AtlasFlag` provide a thread-safe immutable world-rule snapshot and spawn-origin decisions for integrations.
- Handshake checks the per-world `trading` flag, default on.
- Jar plugin-origin spawns use the `plugin-*` flags; Nest vanilla spawner-origin mobs use the normal game flags.
- Atlas publishes seven approved measurement groups through the public Logbook contract. Its matching Logbook provider is built/tested locally; the dependent release remains undeployed until provider deployment is authorized.
- Campfire registers the authored Atlas catalog and delivers its messages. The tested route renders authored strings in English regardless of viewer locale; no translated Atlas prose is claimed.

## Supported server boundary

Atlas is verified for the current EU/NA pair, using the exact EU/NA Postbox identity and a configured Velocity server name for each. Atlas and current Postbox reject arbitrary additional server IDs. No third-node isolated integration test exists. Therefore the current build is not fully ready for future arbitrary-server expansion; see [multiserver support](multiserver.md) and the external final report. Adding new core identities or routing is out of this proposal's approved scope.

## Permission and data decisions

`atlas.admin` (with child `atlas.gamemode.bypass`) is allocated to manager in `Ranks.md`; `atlas.portal.use` is allocated to default; runtime `atlas.portal.<name>` remains deliberately unassigned by Ferry's decision. No Atlas numeric rank limits exist. This audit did not modify LuckPerms. Repository database credentials remain blank and server-local credentials remain outside the repository.

## Verification and completion

Current tests, source review, GitHub state, worktree cleanup, release dependency and Ferry's outstanding game checks are recorded in [the external final audit](../../Ferry/Eindrapporten/Atlas.md). Historical verification records remain in [verification.md](verification.md); do not treat older version counts or deployment state as evidence for this release.
