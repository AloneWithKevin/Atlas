# Atlas release handoff — 2026-10-07

## Complete source package

Branch: `feature/selector-overflow-integration`, based on main
`7c4e7a845ce8affa8d15d9630246d996edd8f8e1`.
Runtime: `build/libs/Atlas.jar`; internal descriptor version `0.1.9`.
Runtime SHA-256:
`04CB6E4516D5D878E204E09E377A476D40BC667332436E0CF85E6D74552171E6`.
Public Atlas API hash remains
`B4B923A1159786DF97631520385B71702F635BC021671B205AE8580AF05B0085`.
Final publication SHA/tree and runtime hash are reported with the release verification.

Selector uses public ClosetDeliveries.issue/reconcile with inventory-first routing.
New shared caller table `atlas_selector_delivery` retains original player/operation
identity and completion. Pending/offline/unknown results keep the same pointer;
repeated commands and join/startup recovery resume it. Only the explicit command
can reserve a new intent, and only after the previous request completed. Completion
validates recipient, operation, expected units and COMPLETE accounting. Pointer
completion is conditional on the original ID. No direct stack construction,
inventory mutation, template recreation, drops or compensating delivery IDs.
Closet owns exact frozen payload/partition/Market receipts; Atlas owns selector
usage and access. Earlier untracked legacy grant IDs/journals are not guessed,
reassigned or cleared by this release.

## Matching provider artifacts

| Provider | Compile artifact | SHA-256 |
| --- | --- | --- |
| Veyra | `.build-deps/veyra-api-item-exchange.jar` | `CC8D44D9F746BE69F705F2191410F501B25796F6E1A22B2A50828E3E8D6C7C6F` |
| Closet | `.build-deps/closet-api-delivery.jar` | `99EEFEC1D55D5A789BCB5C068858DF6D947C7F308E2512869C8534318843939C` |
| Campfire | `Campfire/build/libs/Campfire-0.6.0-api.jar` | `39EE7EE5E80F8C2FE50ED5EC36730F079D3EE43915B4F19F43C8CC50FBED0398` |

Closet delivery provider requires its matching Market exact-storage and Veyra
inventory providers. Atlas acquires ClosetDeliveries from the services manager;
missing capability stops readiness. Existing dependencies remain Postbox, Campfire,
Closet; Atlas does not duplicate a direct Market delivery coordinator. Public
artifact signatures were inspected. This is not evidence of server installation.

## Verification and preserved contracts

Java 25 `assemble apiJar --offline` passes. No new test suites, database cases or
repeated broad regressions were run. First compile exposed the ordinary Closet API
artifact missing delivery classes; the complete published API pin resolved it.
Source review covers original IDs, receipt proofs, concurrency and delayed pointer
acknowledgements. Historical suites remain in verification.md without new claims.
Artifact comparison confirms all world-lifecycle classes are byte-identical,
the Atlas public API hash is unchanged, packaged passwords remain empty and
provider runtime classes are not embedded.

Catalog has 113 keys. Only new `selector.overflow` is added; prior approved values,
three world-operation messages, glyphs and palette are retained. Caller reports
inventory delivery, confirmed overflow or pending distinctly. Runtime filename is
Atlas.jar. Player documentation contains no staff procedures or internal contracts.
World preparation/activation, uncertain reset preservation and verified-copy
completion remain unchanged. No new commands, configuration values or permissions.

Permissions remain resolved: atlas.admin (op, child atlas.gamemode.bypass=true)
and atlas.gamemode.bypass (op) to manager; atlas.portal.use (true) to default;
runtime atlas.portal.<name> (op) deliberately unassigned. No limit/unlimited nodes.
No rank grant actions occurred.

## Rollout and acceptance

Deployment must bundle matching providers, add the required selector.overflow
catalog key while retaining installed overrides, and use Atlas.jar. This package
does not install artifacts, start/stop servers, mutate installed databases, create
backups, publish packs or change routes. NA rollout is coordinated separately;
EU remains deferred. Owner performs restarts and in-game acceptance.

Acceptance: inventory-space/full-inventory selector delivery, exact mailbox item,
same-ID repeated pending request, disconnect/rejoin/startup recovery and no duplicate
goods after an interrupted reply. Retain the bundled world two-start clone/reset,
safe uncertainty, region overlap, portals and Jar/Nest/PixelMobs/Handshake checks.
Velocity routing deferral and removed Colosseum decision remain unchanged. No new
owner feature or permission question blocks this source package.
