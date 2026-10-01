# Verification

## 2026-10-01, Atlas 0.1.0

`Build-Plugin.ps1 -Project Atlas -GradleArgs test,shadowJar,apiJar --offline` on Java 25:
26 tests, 26 passed, 0 skipped, with the MariaDB test enabled.

| Suite | Covers |
| --- | --- |
| `WorldNamesTest` | Vanilla aliases, Atlas keys, reserved and unsafe names. |
| `DatapackWriterTest` | Dimension JSON per generator, only enabled Atlas worlds declared, add/remove/no-change detection. |
| `WorldFoldersTest` | Copy skips `session.lock` and Paper's `metadata.dat`; vanilla dimensions never overwritten or deleted; reset swaps only after a full copy; missing source leaves no target. |
| `RulesSnapshotTest` | Overrides vs defaults, all 24 flags need defaults, key parsing, game vs plugin spawn flags. |
| `PortalIndexTest` | Chunk index across borders and negative coordinates, target validation, keep-loaded chunk maths (incl. the old Hub01 `hub` region = 504 chunks) and overflow. |
| `AtlasResourcesTest` | Shipped `messages.yml` against the real Campfire catalog with exact placeholders; `closet.yml` against real Campfire + Closet validation; shipped `config.yml` complete apart from credentials. |
| `MessageKeyUsageTest` | Every message key in the code is in the contract; every lifecycle refusal has a message. |
| `AtlasRepositoryMariaDbTest` | Real MariaDB 12.2 on a private loopback instance (shut down afterwards): worlds, flags, settings, spawn, portals, regions, ticket consumed once and only on its target region, queued clone/delete executed by `StartupOperations`, cancelled reset. |

Not yet verified: startup on a Veyra server, every command in game, cross-server portals
(needs a Velocity pair). The in-game test is done by the owner.
