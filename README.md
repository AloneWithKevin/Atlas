# Atlas

Atlas manages the worlds of one Veyra server: extra worlds, the rules inside every world, portals,
safe world teleports and keep-loaded chunk regions. It replaces PixelWorlds.

- Worlds are declared as dimensions in an Atlas-owned data pack and load at the next server start.
- Rules (25 flags and per-world settings), portals and keep-loaded regions are stored per server
  region (EU or NA) in the shared MariaDB database `pixelretreat_veyra`.
- Requires Postbox, Campfire and Closet. Built for Veyra 26.3 (Folia).

Build from the workspace root:

```powershell
.\Build-Plugin.ps1 -Project Atlas -GradleArgs @('test','shadowJar','apiJar','--offline')
```

Documentation: [commands](docs/commands.md), [permissions](docs/permissions.md),
[configuration](docs/configuration.md), [architecture and threads](docs/architecture.md),
[API](docs/api.md), [comparison with PixelWorlds](docs/parity.md), [verification](docs/verification.md),
[final report](docs/final-report.md), [player guide](docs/players.md),
[build proposal](docs/build-proposal.md).
