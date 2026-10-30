# PixelWorlds feature comparison

The full old-plugin inventory and owner decision record is preserved in [the original feature review](../../.research/atlas/feature-review.md). This table describes the current Atlas implementation and its accepted differences.

| PixelWorlds feature | Current Atlas behavior | Status |
| --- | --- | --- |
| Create/import/load/unload/delete worlds | Data-pack dimensions and queued start-time operations; no runtime create/unload. | Changed to Veyra-compatible restart lifecycle. |
| Clone/reset | Startup-time copy with sealed evidence, checksums and retained originals; uncertain work remains disabled. | Preserved and made recoverable. |
| Void generator | Flat no-layer dimension using the vanilla void biome. | Preserved with data-pack generation. |
| Teleport/spawn/set spawn | Safe async teleport; world must already be loaded. Player or explicit console target/coordinates. | Preserved and adapted to Folia. |
| World info/list | Loaded/enabled state, generator, settings, player count and flags. | Preserved; folder paths are not exposed. |
| Original 20 world rules | Stored as per-world MariaDB flags, with configured defaults and overrides. | Preserved without hard-coded world-name exceptions. |
| Plugin spawn rules | Separate hostile/friendly plugin-origin flags, while normal game spawns use the game flags. | Added to preserve the correct origin semantics. |
| Trading | `trading` flag (default on) consumed by Handshake. | Added by owner decision. |
| Difficulty, game mode, time, weather | Per-world settings; `atlas.gamemode.bypass`; time/weather cycles use their flags. | Preserved with old defects corrected. |
| Portals | Selected cuboid, same-backend spawn/location or EU/NA cross-region target, cooldown, sound, particle, optional permission and ordinary-block fill. | Extended. |
| Keep-loaded regions | Selected X/Z chunk rectangle with configured cap and plugin chunk tickets. | Preserved. |
| Help and tab completion | `/atlas` help and sender-aware completions for supported argument positions. | Preserved and adapted. |
| Messages | Authored Campfire catalog, stable message keys and selector name/lore. | Recipient-language messages and compound portal descriptions through Campfire; console English and selector definitions preserved. Native final refusals require the matching Veyra hook. |
| Selector | Closet item `atlas:selector`; public Closet delivery and exact overflow handling. | Preserved and provider-owned. |
| Public rules API | Typed `AtlasRules` and `AtlasFlag` service for world rules and spawn origin. | Replaces `PixelWorldsApi` and reflective `worldService()`. |
| GUI, custom blocks and recipes | None. There was no legacy Atlas GUI to restore; selector interaction is in-game corner selection. | No GUI or custom item-production feature. |

The old `pixelworlds.*` permissions and aliases are not retained. Current permissions and approved allocations are listed in [permissions](permissions.md). Colosseum arena/reset design is no longer an Atlas completion gate; Atlas does not provide runtime world lifecycle calls.

The current cross-region model supports the Postbox EU/NA pair only. It is not a general arbitrary-node implementation. The exact limitation is in [multiserver support](multiserver.md).

Current statistics are listed in [Logbook integration](logbook-integration.md); they use original Atlas source transitions and preserve unknown outcomes. These measurements do not add player history/location tracking.
