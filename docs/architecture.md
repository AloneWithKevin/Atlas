# Architecture

## Selector issuance

`SelectorDeliveryService` persists the original operation ID in
`atlas_selector_delivery`, keyed by player across nodes. Only explicit staff
requests reserve new intents; startup/join recovery looks up pending intents.
Concurrent local calls share the current future. MariaDB reservation retains the
pending ID; only a verified COMPLETE Closet receipt marks that exact ID completed.
The next explicit command can then reserve a new intent. Provider failures, unknown
results and offline delivery retain the pointer; no replacement goods or IDs are
created to compensate. Closet's durable journal owns the frozen payload, inventory
partition, Market receipt and recovery. Atlas never materializes or drops the selector.
All caller journal access runs on AtlasWorkers. Delivery callbacks only use immutable
IDs/receipts; player messages use Campfire's public delivery service. A failed caller
completion write does not invalidate proven delivery; the retained pointer safely
reconciles the same COMPLETE provider receipt later.

## World lifecycle without createWorld

Veyra rejects Bukkit `createWorld`/`unloadWorld` (Folia), and the owner decided on 2026-10-01 not
to add a runtime lifecycle API to Veyra. Veyra does load every dimension in the `LEVEL_STEM`
registry at startup (`PaperWorldLoader.loadInitialWorlds`), including data pack dimensions, as
world key `<namespace>:<path>` stored in `<level>/dimensions/<namespace>/<path>`. Minecraft reads
data packs in `Main` before plugins load. Atlas therefore:

1. writes `<level>/datapacks/atlas` from MariaDB immediately when staff change the world set; the
   pack is picked up automatically at the next start;
2. queues folder work (delete, clone, reset) in `atlas_pending_operation`;
3. runs folder work only when the target is absent from startup declarations.
   Clones/resets prepare verified copies during one start and declare the completed
   world for the following start; uncertain content stays disabled;
4. re-synchronises the data pack in `onLoad` and warns once if it was out of date.

Void worlds are a flat generator without layers in `minecraft:the_void`; no plugin chunk generator
or `bukkit.yml` entry is involved. Custom dimensions use the vanilla dimension types; Paper's
`time.affects-all-worlds: false` (as on VeyraTest01) gives every world its own clock.

## Data (MariaDB, `pixelretreat_veyra`)

Every table has a `server` column (`EU`/`NA`); each server reads and writes only its own rows,
except travel tickets, which the origin writes for the destination.

| Table | Content |
| --- | --- |
| `atlas_world` | One row per Atlas world and per vanilla dimension with settings: generator (NULL for vanilla), enabled, reset source, spawn, difficulty, game mode, fixed time, weather. |
| `atlas_world_flag` | Flag overrides. |
| `atlas_portal` | Bounds, destination (spawn, location or other region), cooldown, sound, particle, restriction, fill block. |
| `atlas_keep_loaded_region` | Chunk rectangles. |
| `atlas_pending_operation` | Queued folder work and its outcome. |
| `atlas_world_copy` | Durable phase, activation state and sealed counts. |
| `atlas_world_copy_entry` | Prepared/original paths, sizes and checksum evidence. |
| `atlas_travel_ticket` | Cross-server trips; consumed once with a conditional update, expire after `portals.travel-ticket-minutes`, purged a day after expiry. |

No old data existed (no PixelWorlds installation or extra dimension on any instance), so nothing
is imported.

## Threads

See [world recovery](world-recovery.md) for atomic queue/cancellation, two starts,
bounded copy workers and retained original trees.

| Work | Where |
| --- | --- |
| Folder copy/delete for queued operations | `onLoad`, before worlds load; files copied in parallel on a bounded pool of `workers.threads`. |
| Database, data pack writes | `AtlasWorkers`: fixed pool of `workers.threads`, bounded queue; a full queue rejects the request (`common.failed`). Hikari pool ≤ 4 connections. |
| Rule checks in listeners | The event's own thread, reading an immutable `RulesSnapshot` from an `AtomicReference`; no locks. |
| Game rules, time, weather, difficulty, world spawn, chunk tickets, permission registration | Global region scheduler. |
| Game modes, teleports, messages to a player, Connect plugin message, portal effects | The player's entity scheduler (`teleportAsync` only). |
| Safe-spot search and portal fill blocks | The region scheduler of the chunk (after `getChunkAtAsync`); the search only inspects loaded chunks owned by that region. |
| Ticket purge | Async scheduler every 60 minutes, submitted to the workers. |

Results return to the owning thread through the schedulers; no game thread waits on a future.
`onDisable` releases all chunk tickets, stops the workers and closes the pool.

Region rectangle expansion runs on the bounded workers. Reload completes after the
global scheduler reconciles Atlas tickets, or reports a scheduling/ticket failure.
Revision checks discard older database reads. Ticket bookkeeping and terminal
cleanup share a lock; delayed callbacks cannot re-add tickets after shutdown.
Cleanup settles pending reconciliation results even when ticket removal throws.
Closed region services refuse new saves/deletions. Work accepted before shutdown
may already have committed; cancellation is not a database rollback.
Worker shutdown settles jobs removed from the queue and interrupts active jobs.
An active job's future completes when its call actually returns or throws.
Ticket registration does not guarantee the chunk is already physically loaded.

Until the rules are loaded, guarded gameplay (block changes, damage, drops, spawns) is refused,
and `/atlas` is not yet registered. If startup fails, Atlas logs one line and disables itself.

## Cross-server portals

The origin inserts a ticket (target region, world, optional point), then sends the player a
BungeeCord `Connect` to `server.proxy-names.<region>` on the entity thread. A failed send deletes
the unused ticket. On join the destination consumes the newest valid ticket once and teleports.
Testing needs a Velocity route between both servers.

## Vanilla portals inside Atlas portals

`EntityPortalEnterEvent`, `PlayerPortalEvent` and `EntityPortalEvent` are cancelled inside an
Atlas portal, and `BlockPhysicsEvent` is cancelled for an Atlas portal's own fill block, so a
nether portal fill without obsidian frame does not break.
