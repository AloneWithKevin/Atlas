# Atlas

Atlas replaces PixelWorlds and manages per-world rules, datapack-declared worlds, portals, safe teleports and keep-loaded regions on Veyra.

The current implementation supports the EU and NA Postbox regions. Each backend uses its region identity in the shared `pixelretreat_veyra` MariaDB database. Cross-region portal travel uses Postbox's current EU/NA contract and Velocity routing. Atlas is not yet ready for arbitrary additional server identities: Postbox and Atlas configuration currently accept only EU and NA. See [multiserver support](docs/multiserver.md) for the verified boundary.

Worlds are dimensions declared in Atlas's data pack. Minecraft loads the declared world set during server startup; create/import/enable/disable therefore affect the next start. Clone and reset use verified preparation and activation across starts. Atlas does not create or unload worlds at runtime.

The public Atlas rules API serves typed world flags and spawn decisions to integrations. Atlas also records the owner-approved, source-backed measurements through Logbook's public contract. The Atlas consumer release depends on the matching Logbook provider and must remain local until that provider is approved for deployment.

Atlas requires Postbox, Campfire and Closet. It targets Veyra/Folia 26.3 and uses Java 25.

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
