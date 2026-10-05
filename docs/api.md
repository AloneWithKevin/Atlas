# API

`Atlas-0.1.1-api.jar` contains `nl.pixelretreat.atlas.api`. Use it as `compileOnly`, declare
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

The flags include `trading` (`AtlasFlag.TRADING`, default on): the per-world answer to "may players
trade here?". Handshake resolves it with `AtlasFlag.fromKey("trading")`, refuses to start without
it and asks `flagEnabled(world, TRADING)` for the players in a trade. Staff override it per world
with `/atlas flag <world> trading <on|off|default>`, like every other flag. Integration rows:
`../../PLUGIN_INTEGRATIONS.md` (Atlas → Handshake, Atlas → Jar, Atlas → Nest/PixelMobs, Colosseum).
