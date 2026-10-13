# World operation and copy recovery

## When operations run

World registry data is read before plugins start. Atlas therefore writes the generated data pack and queues the operation in MariaDB when an administrator changes the declared world set, but performs directory changes during `onLoad`, before world loading. Create/import/enable/disable changes need the next server start. Clone and reset require a preparation start and a later activation start. A stale data-pack declaration may require another start before a folder can be touched. Atlas does not create or unload worlds at runtime.

## Durable evidence and phases

`atlas_world_copy` records operation phase, original enabled state and sealed entry counts. `atlas_world_copy_entry` records relative paths, directory/file type, sizes and SHA-256 for prepared and original trees. The evidence uses Atlas's current server identity in the shared database; no local journal substitutes for it.

Copy phases are `QUEUED → COPYING → PREPARED → MOVING_OLD → PUBLISHING → PUBLISHED → APPLIED`; clone skips `MOVING_OLD`. Intent is committed before same-parent atomic directory moves. Each resumed move must match the sealed evidence. Reset keeps its original tree in `.NAME.atlas-ID-original`; the prepared tree uses `.NAME.atlas-ID-staging`. Existing trees are never silently overwritten.

Only a completely sealed and verified prepared copy may finish automatically after an interruption. Unsealed copying, missing or changed evidence, unexpected files or conflicting paths become uncertain. The target stays disabled; Atlas does not copy again, adopt unknown content, or delete unknown trees. Legacy clone/reset receipts without evidence are not guessed into the new state machine. There is no staff command to force a resolution or automatic retention expiry for preserved originals.

The pending command lists operations still awaiting work, not a full history of terminal results. Cancellation is accepted only before preparation has claimed the copy; it restores a reset's original enabled state or removes a clone declaration as appropriate.

Queued delete checks that its target is not a built-in dimension and has no portal or keep-loaded references. The references are checked again immediately before directory removal; if one has appeared, Atlas retains the folder and records the refused operation.

## Threads and limits

Startup traversal streams entries and caps outstanding file tasks at `workers.threads`. Database writes use the bounded Atlas worker pool and Hikari pool. On interruption, outstanding copy writers are cancelled and retired before their parent operation returns. Files are flushed before evidence is sealed. Unsupported atomic directory moves preserve the operation for verified recovery. Tests cover interruption boundaries, altered evidence and SQL rollback; they do not prove hardware power-loss guarantees. This protocol is not a substitute for server backups.

## Startup failure

Before Campfire is available, Atlas has a bundled generic console failure message. Startup failure logs one short diagnostic and disables Atlas. It does not expose exceptions or database credentials to players.
