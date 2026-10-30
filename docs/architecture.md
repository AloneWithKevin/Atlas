# Architecture

## World lifecycle

Atlas does not call Bukkit `createWorld` or `unloadWorld`. Veyra loads dimensions from the world registry before plugins are enabled. Atlas writes its generated data pack from database state and handles queued folder operations in `onLoad`, before those worlds load. New terrain is generated at startup. Initialized worlds may also be enabled/disabled through the public Veyra world lifecycle while the server runs. Native construction and IO run on bounded workers; global publication is separate from exact-instance region/task/move admission and verified shutdown. Atlas persists only proven runtime outcomes and reports uncertainty on a persistence failure. Clone/reset operations prepare and verify folder copies at startup and activate the published world on a later startup. Uncertain files remain preserved and the affected target remains disabled. See [world recovery](world-recovery.md).

Closed access is stored separately from enabled state. Native original-player admission denies ordinary entries; existing players and already accepted arrivals are evacuated on their entity owners to Toolbox's published actual local spawn. A native barrier waits for the original accepted moves before a second evacuation sweep. Staff bypass is checked again on the owning player, and reopening does not move players or reload files.

## Server identity and storage

Postbox publishes `PostboxBootstrapIdentity` during `onLoad` from the exact validated `server` configuration value `EU` or `NA`. This is a bootstrap configuration fact, not evidence that Postbox's database, presence or routing service is ready; those start in `onEnable`. Atlas has `load: POSTWORLD` and depends on Postbox. Its `onLoad` compares the bootstrap identity with Atlas's configured region before resolving world folders, opening Hikari, initializing schema, running queued world operations or synchronizing the datapack. Missing or mismatched identity fails Atlas closed. Atlas retains its later live `VeyraCluster` region check as a separate runtime readiness check.

MariaDB is the shared `pixelretreat_veyra` database; Atlas rows are partitioned by the validated exact local server ID. Each backend reads and mutates its own world, portal and keep-loaded records. Cross-server portal travel writes a one-use ticket for the exact target server; the destination consumes it conditionally. Multiple configured servers may share a geographic region. Routing uses explicit server-to-proxy mappings. See [multiserver support](multiserver.md).

## Stored data

Atlas stores world settings, flag overrides, portals, keep-loaded regions, pending lifecycle work, sealed copy evidence, travel tickets, historical selector-delivery pointers, and Logbook source journals in MariaDB. World files and generated datapack files remain under the server's normal world folder. Database credentials stay in the installed local `plugins/Atlas/config.yml`; repository defaults are blank and diagnostics redact credentials.

## Work ownership

| Work | Execution owner |
| --- | --- |
| Database access, generated data-pack writes, snapshots and bounded non-world computation | Atlas's bounded worker pool; rejected when its queue is full. |
| Clone/reset/delete folder work | Bounded startup workers in `onLoad`, before worlds load. Copy recovery verifies durable sealed path/hash evidence. |
| Rule checks | The event's owning thread, reading an immutable `RulesSnapshot`. |
| World rules, game rules, time, weather, difficulty, world spawn and chunk-ticket reconciliation | Folia global region scheduler. |
| Player game mode, message delivery, portal connection messages, teleport and portal effects | That player's entity scheduler. Teleports use `teleportAsync`. |
| Safe-landing search and portal fill edits | The owning region scheduler after the target chunk is available. |
| Logbook measurement writes | Source transaction or bounded Atlas worker, as described in [the measurement contract](logbook-integration.md). |

No game thread waits on a database, file or future. Asynchronous results return to the entity or region that owns the next operation. `onDisable` stops capture, releases plugin-owned chunk tickets, settles and stops worker jobs, and closes the database pool. Accepted work may already have committed; shutdown cancellation is not a database rollback.

## Rules and portal indexing

Each world has an immutable rules/settings record. Database changes are serialized through the rules service before new snapshots are published, so an older read cannot overwrite a later accepted change. Portal lookup uses compact per-world cuboid entries and checks the actual block against portal bounds; it does not allocate an entry per chunk covered by an administrator-defined portal.

Portal edits serialize their database and index updates. Portal create is insert-only, so a concurrent duplicate cannot replace the existing record. Portal fill work is clipped to the current world height and uses the owning region. Destructive world deletion rechecks portal and keep-loaded references immediately before removing the folder.

At startup, guarded natural weather and thunder changes are refused until an immutable rules snapshot is available. Atlas-caused weather changes remain allowed. A `NIGHT_SKIP` is also refused until rules are ready; other time-skip reasons retain their existing behavior. `WorldRulesListenerTest` covers these startup paths.

## Cross-region portal flow

The origin validates the player and route, reserves a ticket in the original portal transaction, and sends Velocity's `Connect` message on the player's entity scheduler. Failed send removes the unused ticket. On arrival, the target EU/NA backend claims the ticket once and schedules the teleport for the player. Atlas keeps `UNKNOWN` distinct from proven arrival or refusal. A supported Velocity route and a running target are required for actual game acceptance.

## Campfire and Closet

Atlas registers its authored catalog with Campfire before delivering messages, and uses Campfire's public delivery API. Portal destination and bounds descriptions remain keyed references until compound delivery, sharing the enclosing message's recipient language and catalog snapshot. Console replies use the English catalog. World names, coordinates, command tokens and setting identifiers remain literal values.

Atlas publishes a message-only refusal renderer through `WorldLifecycle.publishRefusalRenderer`. Native final join/respawn refusals render `world.closed-entry` for the original recipient UUID and refused world without requiring an online Bukkit player. Veyra bounds requests and retains the static refusal on failure, timeout or stale registration. Reload withdraws the registration and fences earlier callbacks before publishing the current renderer; disable withdraws it. Successful admission and teleport decisions do not wait for message rendering. This requires the matching native API/runtime release.

Atlas registers `atlas:selector` and the selector lore glyph through Closet's public asset-registration contract. The definitions stay with Atlas. Closet owns and supplies every pack asset; Atlas does not own a texture, font, model or sound file.

## Logbook

Atlas exposes only the seven owner-approved metric groups through the Logbook provider contract. Durable facts use their original Atlas operation and transaction. The source enforces bounded collection and admission. The matching provider is build/test-only under this task; the dependent Atlas artifact is not eligible for deployment until matching provider deployment is authorized. Exact metric semantics and coverage are in [Logbook integration](logbook-integration.md).

## Direct selector issuance

`/atlas selector` captures the original requester and local target. Closet prepares the unchanged definition asynchronously, then the original requester permissions and actual target connection/Compass custody are checked again. Materialization and direct live inventory delivery run on that original player's Folia owner, inline when already owned. Only actual Market overflow business stock is stored; exact correlation, one applied unit and an empty remainder are required for success. No full inventory capture, saved proof, selector SQL intent, generic per-player busy map or reconnect/startup replay remains. Interrupted/unknown delivery gives ticket feedback and is never automatically reissued. Historical selector rows remain evidence only. Console can still issue to an explicit target; the target needs no admin permission.

Compass is a current service dependency so hidden menu inventories cannot receive the selector. No menu is forcibly closed or restored for this staff command. World rules continue to read immutable live snapshots; portal travel tickets, chunk loading and world-copy recovery keep their separate functional storage and maintenance protections.
