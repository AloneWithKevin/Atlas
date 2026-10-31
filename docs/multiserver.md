# Atlas multi-server support

## Identity and configuration

Atlas uses PostboxNetwork 0.2, with exact unique server IDs independent of geographic region.
Hub01, Survival01 and Survival02 can therefore all be EU members. `server.id` must exactly match
Postbox's startup identity. The bootstrap guard runs before database/world startup work; the live
service is checked again, and startup waits for a fresh local lease. This is not proof of proxy routing.

`server.proxy-names` maps explicit server IDs to actual Velocity backend names, including the local
member. Remote portal commands and completion use that mapping; an absent or local target is
refused for ordinary players and OP. There is no inferred opposite region. An ID can contain ASCII
letters, digits, dots, underscores and hyphens, must begin with a letter or digit, and is at most64
characters. Values retain their original case; proxy names remain separately validated.

## Storage and historical proofs

All configured installations use the same existing `pixelretreat_veyra` schema. World, flag, portal,
keep-loaded and pending/copy-operation rows remain partitioned by exact original server ID.
Owned identity columns widen conditionally to64 ASCII/binary characters, retaining nullability and
all historical EU/NA bytes. Unexpected old values stop migration for review. Startup does not
rename EU/NA records to new installation names, import worlds or reset data.

New portal tickets keep their original UUID as the Controller transfer ID. Their fingerprint
binds the exact player, proxy session, source visit and registered source/target endpoints.
Required destination preparation claims that same ticket once, before native first-chunk
exposure. An actual successful teleport also needs its original player's SAVED checkpoint
before the ticket becomes ARRIVED. A known non-movement becomes NOT_MOVED; an interrupted
ARRIVING remains unknown and is never dispatched again.

## Proxy travel and uncertainty

Portals use Controller's public exact-plan transfer API. Source binding and ticket intent are
persisted before transfer admission; no uncorrelated Bungee Connect is sent. Controller owns
full inventory custody, routing and exposure. A changed binding or unknown provider result
cannot create a replacement ticket or transfer ID.

Legacy unbound tickets remain evidence and are not automatically consumed or dispatched by
join. They therefore cannot race a new prepared arrival. Only matching terminal transfer
proof makes a new ticket eligible for the existing age-based purge. Unfinished modern tickets
are retained, including after a restart or expiry during an uncertain movement.

Permission and entity-owner rechecks, original operation/custody evidence, bounded workers and
local native world ownership remain in force. Separate servers have independent worlds; this does
not distribute one active world across machines. Runtime proxy checks and owner game acceptance
remain required after the matching provider/consumer release.
