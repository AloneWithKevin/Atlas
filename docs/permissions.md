# Permissions

| Node | Default | Allows | Children |
| --- | --- | --- | --- |
| `atlas.admin` | op | Every `/atlas` command, tab completion and using the Atlas Selector. | `atlas.gamemode.bypass` |
| `atlas.portal.use` | true | Travelling through Atlas portals. | — |
| `atlas.gamemode.bypass` | op | Keeping your own game mode in worlds with a forced game mode. | — |
| `atlas.portal.<name>` | op | Using the portal `<name>` while it is restricted. Registered at runtime for each restricted portal. | — |

There is one admin node for all staff commands (owner decision 2026-09-29: no per-subcommand
nodes). No node uses `default: false`; operators hold every node. Rank allocation is recorded in
`PixelRetreat - Documentation/Ranks.md`.
