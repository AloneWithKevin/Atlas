# Public API

The API artifact exposes `nl.pixelretreat.atlas.api`. Compile against the Atlas API JAR and declare Atlas as a hard or soft dependency according to whether the integration can operate without Atlas. Resolve the registered Bukkit service after Atlas becomes ready:

```java
AtlasRules rules = getServer().getServicesManager().load(AtlasRules.class);
```

`AtlasRules` is a read-only, thread-safe view over an immutable rules snapshot.

| Method | Meaning |
| --- | --- |
| `ready()` | Whether Atlas has loaded its current rules. |
| `flagEnabled(World, AtlasFlag)` | Effective setting for a world. Before readiness, returns the restrictive value `false`. |
| `allowsSpawn(World, EntityType, SpawnOrigin)` | Whether this entity type may appear for the supplied origin. Before readiness, returns `false`. |

For `SpawnOrigin.GAME`, hostile and friendly mobs follow `hostile-mobs` and `friendly-mobs`. For `SpawnOrigin.PLUGIN`, they follow `plugin-hostile-mobs` and `plugin-friendly-mobs`. Other living entities are unaffected by these mob flags.

`AtlasFlag` provides stable lowercase keys and `fromKey`; underscores are accepted as hyphens. Current flags are `hostile-mobs`, `friendly-mobs`, `plugin-hostile-mobs`, `plugin-friendly-mobs`, `tnt-damage`, `crystal-damage`, `fire-damage`, `explosion-damage`, `pvp`, `fall-damage`, `hunger`, `drowning`, `block-break`, `block-place`, `leaf-decay`, `crop-trampling`, `item-drop`, `item-pickup`, `weather`, `time-cycle`, `time-skip`, `mob-griefing`, `keep-inventory`, `portals` and `trading`.

The `trading` flag is the per-world Handshake policy. `AtlasFlag.TRADING` and `AtlasFlag.fromKey("trading")` resolve it; its configured default is on.

Use `allowsSpawn(world, entityType, SpawnOrigin.PLUGIN)` before consuming a durable item or completing a plugin-owned spawn. Atlas's listener applies the same policy to actual `CUSTOM` spawns. Preserve the original operation result when a spawn is refused or cannot be established; do not turn an unavailable Atlas answer into permission to proceed.

Current integrations are tracked in the workspace [plugin integration register](../../PLUGIN_INTEGRATIONS.md). Exact server identities are independent of region; see [multiserver support](multiserver.md) for configured routes and the retained travel uncertainty boundary.

## Local world access

`AtlasWorldAccess` is published through Bukkit services after Atlas is ready. `closed(key)` reads the immutable source snapshot. `setClosed(key, true)` preflights the owning Toolbox spawn source, persists the restriction, publishes native admission and evacuates ordinary players. Its result distinguishes `DONE`, `UNKNOWN_WORLD`, `BUSY`, `SPAWN_UNAVAILABLE`, `EVICTION_INCOMPLETE` and `FAILED`. An incomplete evacuation leaves access closed; do not claim that all players moved. `setClosed(key, false)` only restores access. These are local operations; they neither stop the server nor delete a world.
