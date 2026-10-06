# World copy preparation and recovery

## Two starts

Clone and reset commands atomically queue work and disable the target declaration.
The next server start prepares the copy while that target is absent from the startup
registry. Successful publication enables its declaration and writes the pack for the
following start, which loads the completed world. An already disabled reset remains
disabled after publication.

Minecraft reads registries before plugin onLoad. Atlas refuses folder mutation for
any target still declared in its own startup pack; removing a stale declaration can
require an additional start. No private hooks or runtime world creation are used.

## Durable evidence

atlas_world_copy records phase, original activation state and sealed entry counts.
atlas_world_copy_entry records every relative path, directory, file size and SHA-256
for prepared and original trees. Both private tables use the existing server identity.
CREATE TABLE IF NOT EXISTS adds them without deleting or migrating existing rows.
There is no persistent local journal.

Phases: QUEUED → COPYING → PREPARED → MOVING_OLD → PUBLISHING → PUBLISHED → APPLIED.
Clones skip MOVING_OLD. Intentions commit before same-parent atomic directory moves.
Every resumed move verifies paths, sizes, hashes and counts against sealed evidence.
Existing trees are never overwritten. Original reset data remains in
.NAME.atlas-ID-original; preparation uses .NAME.atlas-ID-staging. Neither is declared
as a dimension.

Only a fully verified prepared publication resumes automatically. Unsealed COPYING,
missing evidence, unexpected paths or changed content result in UNCERTAIN, a failed
receipt and a disabled target. No source is copied again and no unknown tree is
deleted, adopted or rolled back. Legacy clone/reset rows without evidence are refused.
Staff must bring such cases to the owner; there is no new resolution command or
automatic backup-retention policy.

The pending command lists pending operations, not failed receipts. Startup reports
failures through the existing keyed console template. Evidence stays in private
tables and folders. Normal enable/reset/delete commands block unresolved copy
targets. Cancellation is allowed only before preparation claims a queued copy;
it atomically restores the reset's prior enabled state or removes a clone declaration.

Since 0.1.7, the refused deletion of a re-enabled world stores the stable
operation.delete-reenabled meaning and resolves its catalog text when reporting.
Existing receipts are not migrated; operation state and refusal behavior are unchanged.

## Threads and durability limits

Traversal streams entries. At most workers.threads file futures are outstanding;
per-entry evidence uses the bounded Hikari pool. Failure/interruption cancels pending
work and waits for all copy writers to retire before returning. Folder work happens
before world loading; no game thread waits for a copy. Copied files are flushed before
evidence is sealed. Unsupported atomic moves preserve the operation for verified retry.
Tests cover interrupted execution, SQL rollback and altered evidence, not hardware
power-loss durability. This protocol does not replace backups.

## Bootstrap failure

Before Campfire is available, Atlas preloads generic startup.failed console text
from its own bundled messages.yml. Failure uses that text and disables Atlas.
There is no player fallback or visible exception/credential diagnostic.
