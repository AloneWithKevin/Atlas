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

## 2026-10-05, Atlas 0.1.1

New and changed behavior under verification: the `trading` flag (`AtlasFlag.TRADING`, shipped
default on, per-world override and clearing through the generic flag path), the Handshake lookup
through `AtlasFlag.fromKey("trading")` and config diagnostics redaction of `database.password`.
New tests (`AtlasConfigTest`, `RulesServiceTest`, `AtlasCommandTest`, extended `RulesSnapshotTest`
and `AtlasRepositoryMariaDbTest`) also cover the `/atlas reload` supplier path and its result
messages.

`Build-Plugin.ps1 -Project Atlas -GradleArgs clean,test,shadowJar,apiJar,--offline,--continue`
on Java 25 completed successfully: **38 tests passed, 0 failures, 0 errors, 0 skipped**
across 11 suites. The real MariaDB 12.2 repository test ran against an isolated loopback
instance through `ATLAS_TEST_JDBC_URL`; the instance was shut down afterwards.

Artifact inspection confirmed version 0.1.1, Folia support, unchanged dependencies and
permissions, blank packaged database credentials and `default-flags.trading: true`.
The runtime JAR contains no assets or dependency-provider/Bungee runtime classes.
The API JAR contains only the public Atlas API and includes `AtlasFlag.TRADING`.

| Artifact | SHA-256 |
| --- | --- |
| `Atlas-0.1.1.jar` | `21F0573579C17B314062EDB6E0E4EE33228F52629FD19DABC08295DE543F3DBB` |
| `Atlas-0.1.1-api.jar` | `B4B923A1159786DF97631520385B71702F635BC021671B205AE8580AF05B0085` |

Server startup, owner in-game acceptance and joint Handshake/spawn-consumer checks remain
pending. Passing these tests does not establish cross-server portal or gameplay acceptance.
