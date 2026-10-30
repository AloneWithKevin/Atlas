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

One-use travel tickets retain their original UUID, player, source/target server, timestamps and world.
The destination accepts only its exact target and conditionally consumes the ticket once. Consuming
a newer ticket expires older/equal same-player/same-target tickets without inventing an outcome for
their original operations. Tickets for other target servers remain independent.

## Proxy travel and uncertainty

Portal travel keeps the existing Velocity BungeeCord Connect mechanism. The configured destination
receives the request after ticket creation; the proxy message has no Atlas ticket/session correlation
or completion acknowledgement. Queueing/sending is therefore not proof of arrival. An exception
after invocation retains the original ticket and unknown-send gap, without a fresh automatic retry.
Only proven pre-send cancellation withdraws an unused ticket. A later unrelated arrival may still
consume an original unexpired ticket; causal proxy correlation is not claimed.

Permission and entity-owner rechecks, original operation/custody evidence, bounded workers and
local native world ownership remain in force. Separate servers have independent worlds; this does
not distribute one active world across machines. Runtime proxy checks and owner game acceptance
remain required after the matching provider/consumer release.
