# API

`Atlas-0.1.0-api.jar` contains `nl.pixelretreat.atlas.api`. Use it as `compileOnly`, declare
`depend: [Atlas]` (or `softdepend` when the feature is optional) and obtain the service from
Bukkit's services manager after Atlas is ready:

```java
AtlasRules rules = getServer().getServicesManager().load(AtlasRules.class);
```

| Method | Meaning |
| --- | --- |
| `ready()` | Whether the rules are loaded. |
| `flagEnabled(World, AtlasFlag)` | Effective flag value; `false` while not ready. |
| `allowsSpawn(World, EntityType, SpawnOrigin)` | Whether a creature may appear. `GAME` follows `hostile-mobs`/`friendly-mobs`, `PLUGIN` follows `plugin-hostile-mobs`/`plugin-friendly-mobs`; creatures no mob flag governs are always allowed. `false` while not ready. |

All methods are thread-safe and read an immutable snapshot. Plugins that spawn creatures through
the Bukkit API (spawn reason `CUSTOM`) are governed by the `plugin-*` flags automatically; calling
`allowsSpawn(..., PLUGIN)` first lets them refuse cleanly before consuming anything (Jar).
Integration rows: `PLUGIN_INTEGRATIONS.md` (Atlas → Jar, Atlas → Nest/PixelMobs, Colosseum).
