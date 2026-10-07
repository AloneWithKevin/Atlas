# Verification

## 2026-10-07, selector delivery integration

Java 25 `assemble apiJar --offline` succeeds against the actual current Campfire,
Closet delivery and Veyra item-exchange API artifacts. The initial compile found
that the ordinary Closet API JAR did not contain the new delivery types; the pin
was corrected to the published complete `closet-api-delivery.jar`. No new tests
or repeated suites were run. Existing regression results below are historical
baseline evidence, not verification of the new caller journal.

Source review traces durable original-ID reservation, same-ID reconciliation,
receipt identity/accounting checks, conditional completion, pending/offline
retention and join recovery without new requests. Runtime is `Atlas.jar`.
API, flags, permission distribution, world-copy recovery and all existing approved
message values are unchanged. One new required key is `selector.overflow`.
Actual inventory-full delivery, interruption/rejoin, mailbox collection and
prepared-world activation remain bundled in-game acceptance checks.

## 2026-10-06, Atlas 0.1.8 region/worker lifecycle

Java 25 targeted test/runtime/API build passes **6 tests, zero failures/errors/skips**:
`test --tests *AtlasWorkersTest --tests *KeepLoadedServiceTest shadowJar apiJar --offline`.
Cases cover queue saturation, dropped queued work, active interruption without
premature completion, post-close rejection, delayed global callbacks after cleanup,
overlap deduplication/removal, stale database reads, scheduler/ticket/cleanup failures
and the maximum integer chunk coordinate. The first run identified an incorrect
test expectation for a composed cancellation future; the corrected rerun passes.

The 0.1.7 full-suite baseline remains the evidence for unchanged database, travel,
copy recovery and catalog contracts; it was not repeated for this targeted fix.
No new database schema, configuration, messages, content or public API changes.
Scheduler mocks prove ordering/result handling; actual world loading, restart and
game acceptance still require the owner's bundled in-game round.

## 2026-10-06, Atlas 0.1.7 catalog bindings

Full Java 25 clean test shadowJar apiJar offline build passes **69 tests, 0 failures,
0 errors, 0 skipped**, including **20 private MariaDB cases** and actual Campfire/
Closet rendering and content registration. All 112 catalog keys are validated.

New cases exercise all four target descriptions, negative fractional coordinates,
bounds, every new configurable template, literal markup input, authored console
warnings, unchanged failed-arrival delivery and a re-enabled deletion preserving
its files/state while reporting the keyed diagnostic. Arbitrary detail codes are
not treated as message keys. No exception details enter those console warnings.
Existing world-copy interruption/rollback/bounded-worker coverage also passes.
No installed database was written or server restarted for verification.

## 2026-10-06, Atlas 0.1.6 editorial package

The 97 remaining catalog entries were reviewed: 42 values polished and 55 retained;
the seven previously approved values remain unchanged. All 104 keys and placeholder
contracts are unchanged, as are palette/font/glyph, units, conditions and controls.
The diagnostic/formatter centralization proposals are outside this release.

Java 25 message-package tests and runtime/API build pass **10 tests, 0 failures,
0 errors, 0 skipped**, using actual Campfire and Closet rendering/registration.
The initial test run caught the previous exact lore assertion; the fixture was
updated to check both complete new action spans in green, corner values in white,
the same glyph/font and two separating spaces. The rerun passes.

All runtime class bytes and the public API remain identical to the verified 0.1.5
release. No new gameplay/database test run is claimed: the 0.1.5 full suite of 64
passing cases, including 19 isolated DB cases, covers that unchanged implementation.
Descriptor/config/content contracts remain unchanged apart from the version.
Packaged credentials are empty; no assets or provider runtime are embedded.

## 2026-10-06, Atlas 0.1.5 world preparation/recovery

Java 25 clean test shadowJar apiJar offline build passes **64 tests, 0 failures,
0 errors, 0 skipped**, using a private MariaDB 12.2 fixture, real Campfire and Closet.
No installed database was written or server restarted for verification.

Seven interruption boundaries cover clone/reset after sealing, moving the old
tree, publishing the new tree and persisting publication. Resumption uses the
prepared bytes even when source bytes have changed; applied worlds are never
replayed. Missing/changed/extra evidence, unsealed copies, contradictory paths and
legacy operations preserve content and disable the target. Tests also cover stale
startup declarations, two-start activation, SQL rollback of queue/cancel/final
activation, prior disabled reset state, and conflicting pending deletions.
Copy tests verify bounded traversal, all interrupted writers retired before return,
identity omission and failed reset preparation retaining the original world.

The public API JAR remains byte-identical to 0.1.4 (SHA-256
B4B923A1159786DF97631520385B71702F635BC021671B205AE8580AF05B0085).
All 104 message keys and placeholder contracts remain unchanged; only the three
approved lifecycle values changed. Descriptor permissions, config defaults and
the two external assets remain unchanged except the plugin version.
These tests do not establish hardware power-loss durability or in-game acceptance.

## 2026-10-06, Atlas 0.1.4 portal wording

The four ordinary player portal messages now use friendlier English. No keys,
placeholders, colors, permissions or gameplay behavior changed. The content README
was moved to `docs/content.md`: documentation under `content/` failed Closet's
pack-path validation. The two external assets are unchanged.

Java 25 `test --tests nl.pixelretreat.atlas.message.AtlasResourcesTest shadowJar apiJar
--offline` passed: **6 tests, 0 failures, 0 errors, 0 skipped**, using real Campfire
catalog/rendering and Closet registration/pack validation. This targeted rerun
does not replace the earlier full gameplay/database verification or owner game checks.
The initial registration failure was reproduced and resolved by moving documentation
outside the asset root.

Artifact comparison confirms exactly four changed catalog values, all 54 Atlas
gameplay/API classes byte-identical to 0.1.3, unchanged API JAR, and unchanged
descriptor/config apart from version. Packaged credentials are empty; no assets
or provider runtime are embedded. No deployment, pack publication or server action.

| Artifact | SHA-256 |
| --- | --- |
| `Atlas-0.1.4.jar` | `8A73B3B4308115A3179964D00FA73298F2075F9721164E5CDB60A69CB51D8151` |
| `Atlas-0.1.4-api.jar` | `B4B923A1159786DF97631520385B71702F635BC021671B205AE8580AF05B0085` |

## 2026-10-05, Atlas 0.1.3 selector mini-glyph

Full Java 25 `clean test shadowJar apiJar --offline --continue` build passed:
**41 tests, 0 failures, 0 errors, 0 skipped**, including isolated MariaDB 12.2;
the private instance was shut down afterwards.

Real Campfire rendering confines `atlas:lore` to the single action character, keeps
its authored color untinted, preserves white corner values and green click actions,
and preserves literal placeholders across all 104 keys. Real Closet registration
publishes the selector and `atlas:lore/action` to the catalog and both owner assets
to required pack inputs. Bitmap dimensions/transparency, font reference, U+E000,
display height 8 and ascent 7 are checked. The 16x16 bitmap matches the preserved
generic action source byte-for-byte; no legacy art is modified. The only catalog
change from 0.1.2 is the glyph and following space before the existing action line.

Artifact checks confirm 0.1.3/Folia support, unchanged permissions/dependencies,
blank database credentials, no assets/provider runtime in the JAR, API-only scope,
and all 54 Atlas runtime classes byte-identical to verified 0.1.1. No PNG/font file
is copied into code build output. The API JAR hash is unchanged.

| Artifact | SHA-256 |
| --- | --- |
| `Atlas-0.1.3.jar` | `EC0D6AB2218B5C1E1D98C3B88A0479DE698F38E006351436200D3EBE4D63937F` |
| `Atlas-0.1.3-api.jar` | `B4B923A1159786DF97631520385B71702F635BC021671B205AE8580AF05B0085` |

No new deployment, live pack publication, server lifecycle action or game acceptance.
Client tooltip appearance is still an owner check after a separately authorized
matching content/pack release. See [the presentation audit](presentation-audit.md).

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

## 2026-10-05, Atlas 0.1.2

`Build-Plugin.ps1 -Project Atlas -GradleArgs clean,test,shadowJar,apiJar,--offline,--continue`
on Java 25 completed successfully: **40 tests passed, 0 failures, 0 errors, 0 skipped**,
including the real MariaDB 12.2 repository test in a private loopback instance, shut down
afterwards. The existing test port was occupied, so a separate instance and data directory
were used; the other process was not touched.

The real Campfire catalog renders all 104 keys, accepts only the appropriate chat/item
palette and keeps injected input tags literal without click/hover events. Explicit color
checks cover usage labels/actions, world-info labels/default values and selector name/lore
values/actions, including color bleeding. Real Closet 0.1.4 validates the selector manifest.
Before/after comparison finds 51 color-only edits; all English text, keys, placeholders,
line order, whitespace and empty lines remain identical.

Artifact inspection confirms version/Folia support, unchanged permission/dependency
declarations, blank credentials, no embedded assets/provider/Bungee runtime, and API-only
scope. All 54 Atlas gameplay/API class entries are byte-identical to the verified 0.1.1
runtime JAR. The API JAR's hash is unchanged.

| Artifact | SHA-256 |
| --- | --- |
| `Atlas-0.1.2.jar` | `0D2C2F3E59DC3DA1D0E4CB9D49B0222CC4BB68A22B923C1EA70C700C98AD7FA2` |
| `Atlas-0.1.2-api.jar` | `B4B923A1159786DF97631520385B71702F635BC021671B205AE8580AF05B0085` |

The complete legacy presentation/asset comparison is in
[the presentation audit](presentation-audit.md). No missing GUI, artwork or public contract
blocks this patch. Release of the new JAR/messages and owner in-game checks are still pending;
this task performed no deployment, pack publication or server lifecycle action.
