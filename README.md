# Atlas

Atlas replaces PixelWorlds and manages per-world rules, datapack-declared worlds, portals, safe teleports and keep-loaded regions on Veyra.

Each backend uses its exact Postbox server ID in the shared `pixelretreat_veyra` MariaDB database. Multiple servers may share a geographic region. Cross-server portals use explicit server-to-Velocity mappings and one-use destination tickets. See [multiserver support](docs/multiserver.md) for configuration and the limits of proxy delivery confirmation.

Atlas declares worlds in its data pack for startup and uses Veyra's public world lifecycle for runtime enable/disable. Existing initialized worlds can be loaded and unloaded without restarting. Create and first terrain generation remain startup operations; clone/reset retain verified preparation and activation across starts. Close/open changes player access while keeping a world loaded. Complete native world-save folders can be copied intact under `<level>/dimensions/atlas/<name>` while that world is safely disabled.

The public Atlas rules API serves typed world flags and spawn decisions to integrations. Atlas also records the owner-approved, source-backed measurements through Logbook's public contract. The Atlas consumer release depends on the matching Logbook provider and must remain local until that provider is approved for deployment.

Atlas requires Postbox, Campfire, Closet and Compass. Selector issuance is direct on the original local player owner; unknown delivery requires a support ticket and is never replayed on join. It targets Veyra/Folia 26.3 and uses Java 25.

Build from the workspace root:

```powershell
.\Build-Plugin.ps1 -Project Atlas -GradleArgs @('test','shadowJar','apiJar','--offline')
```

## Documentation

- [Player guide](docs/players.md)
- [Commands and completion](docs/commands.md)
- [Permissions](docs/permissions.md)
- [Configuration](docs/configuration.md)
- [Architecture and thread ownership](docs/architecture.md)
- [World recovery](docs/world-recovery.md)
- [Public API](docs/api.md)
- [Logbook measurements](docs/logbook-integration.md)
- [Multiserver support](docs/multiserver.md)
- [PixelWorlds comparison](docs/parity.md)
- [Closet asset ownership](docs/closet-pack-ownership.md)
- [Selector item and glyph](docs/content.md)
- [Verification history](docs/verification.md)
- [Presentation audit](docs/presentation-audit.md)
- [Final audit report](../Ferry/Eindrapporten/Atlas.md)

The selector's item and lore definitions are in Atlas. All texture, font and other pack files are owned and served by Closet; Atlas does not bundle them in its JAR.

The workspace build exports `message-contract.json` from the compiled code-owned
contract declared by `message-contract-export.json`. Shared catalog verification
checks exact text and component placeholders independently of message defaults.
