# Atlas multi-server support

Atlas supports the configured EU/NA pair on separate, independent worlds. Its current contracts do not support an arbitrary number of nodes, a shared world, or a future third node. The source-level pair support is distinct from proof that the inspected Velocity configuration can route both installed backends; that route still needs a matching proxy mapping.

## Identity and startup

Postbox owns Atlas's backend identity for this independent-world topology. `PostboxBootstrapIdentity` accepts the exact configured value `EU` or `NA`; Postbox publishes it during `onLoad`, and Atlas compares it with `server.region` before resolving the level folder, opening the database, initializing tables, running startup world operations, or synchronizing the datapack. Atlas keeps a later comparison against the live Postbox `VeyraCluster` service. The early value proves configuration agreement only; it does not prove that MariaDB, the remote heartbeat, player presence, or Velocity routing is available.

The independent-world contract is explicit in Postbox's integration guide: Postbox's `VeyraCluster.localNode()` reflects its configured EU/NA node, while Veyra's world-ownership answers are local. Veyra's own cluster runtime can have no native node in this mode. Atlas therefore treats the Postbox configuration/bootstrap/live service as the region authority; an absent native Veyra node is not by itself a failed Atlas identity check.

## Shared storage, presence, and leases

The owner-configured EU and NA backends must both use the same MariaDB schema, `pixelretreat_veyra`. `AtlasPlugin` uses the configured database name as-is, without a region suffix. `AtlasStatistics` gives `ProducerWriter` the same datasource and owner `atlas`; the writer locks `logbook_producers` by `owner` only, and Atlas ticket issue/consume transactions use that lock. Thus origin and destination serialize on the same `atlas` producer row only when both runtime configurations actually point to the owner-required shared schema and the statistics service is attached and ready. Per-schema isolated test fixtures do not prove that runtime topology. Atlas keeps region-owned world, portal, flag, keep-loaded, and world-operation rows keyed by `server`; travel tickets are shared rows with an origin and target region. The destination accepts a ticket only for its local target region and conditionally consumes its UUID once.

Postbox uses the same shared schema for its own service tables. `postbox_server` stores a two-character node and an instance UUID with a database-clock heartbeat. Player rows and operator snapshots are tagged with that node and instance. A peer is considered present only while the opposite node's heartbeat is younger than its configured timeout. A takeover changes the instance identity; peers publish leave/join observations for the old/new instance. Database loss or identity conflict removes peer presence and operator authority until recovery. These leases describe Postbox presence; they are not a proxy connection acknowledgement or Atlas travel acknowledgement.

Postbox peer messages are a separate signal channel. Atlas's portal path uses Velocity's `BungeeCord` `Connect` message directly, so Postbox's peer-message queue, presence events, and operator snapshot are not a travel delivery receipt or an Atlas authorization check.

## Portal routing and uncertainty

The origin checks `atlas.portal.use` and, for restricted portals, the portal-specific permission; OP is explicitly accepted. Atlas checks the route against the configured opposite region and requires a configured proxy name before creating a ticket. It creates the ticket and the original route root before the send, then sends Velocity `Connect` on the player's entity scheduler. The message contains the proxy verb and configured destination name, but no Atlas ticket UUID or session correlation token. Atlas does not receive a native acknowledgement that proves whether the proxy completed the switch.

The destination selects the newest unexpired ticket for that player and local target region using `created_at DESC`, then conditionally marks that UUID consumed in the same database transaction. Equal millisecond timestamps have no explicit secondary ordering, so the winner of a tie remains unspecified. The current source preserves that selection rule and, only when the selected ticket is actually consumed, expires every other still-unconsumed same-player/same-target ticket whose timestamp is older than or equal to the selected timestamp. This prevents a superseded route from becoming eligible on a later unrelated join. The transaction changes the old ticket's expiry only: it does not mark the old route `not_performed`, terminalize its original root, remove its gap, release its terminal/duration holds, or issue a replacement ticket. Equal-millisecond ties have no secondary ordering; the selected winner remains unspecified, while a non-selected tie falls under the same expiry boundary.

When a `sendPluginMessage` call throws after invocation, Atlas cannot prove that the proxy did not receive the `Connect`. The current source keeps that same original unconsumed ticket and records the existing `proxy_send_unconfirmed` gap; it does not retry or issue another ticket. A proven pre-send cancellation still removes the unused ticket and records the known `not_performed` outcome. Retaining the route preserves the original intent, but without a proxy acknowledgement or arrival correlation token a later same-player join to the target could still consume it. The source does not claim causal correlation with a particular connection attempt.

The source portal's sound/particle effect is scheduled before `sendPluginMessage`; it can therefore be visible even if the send later fails or remains uncertain. Atlas does not treat that source effect as an arrival terminal. Local and destination teleports preserve `UNKNOWN` when their actual result cannot be proven. There is no automatic fresh retry for an unknown result.

## Proxy mapping evidence

Atlas's installed EU and NA plugin configurations refer to proxy names `VeyraTest01` and `VeyraTestNA01`. The inspected `Velocity01/Minecraft/velocity.toml` contains `VeyraTestNA01`, `survival`, and `arena`, but not `VeyraTest01`. The inspected configuration does not establish that this is the only proxy used by both backends; therefore the source contract can be described as EU/NA-capable, but live cross-region routing is not verified until the actual common proxy and its matching server map are confirmed.

## Future nodes

A third node is deliberately unsupported. Atlas restricts `server.region` and the exact `proxy-names` keys to `EU` and `NA`; `otherRegion()` selects only the opposite member. Postbox validates only those same identities and assumes one opposite peer. Its relevant tables store `server` as `VARCHAR(2)`, and the network operator query explicitly selects `EU` and `NA`. Veyra's current `ClusterNode` enum and cluster service peer selection also model only EU/NA; Postbox documents that the two game worlds are independent.

Current negative tests prove rejection, not expansion: `AtlasConfigTest` rejects `APAC` as either a local region or proxy key, and `PortalServiceTest.persistedThirdNodeTargetIsRefusedBeforeTicketOrProxySendForRegularAndOpPlayers` proves a persisted third-node portal fails before scheduler, repository use, or proxy send for OP and non-OP callers. There is no successful third-node test, and this build must not be described as ready for arbitrary future servers.

Adding another node requires a reviewed topology contract for identity ownership, discovery, peer presence and leases, proxy destination mapping, shared-storage partitioning, reconnect and outage behavior, permissions, network-wide one-time actions, and tests that run a successful isolated third-node route. No Veyra/Postbox expansion is included here.

## Verification boundary

The current source contains MariaDB regressions for ticket supersession, preservation of an older UNKNOWN root and ambiguous-send ticket retention. The final bounded private harness (run 38036) passed: 44 XML suites, 215 Java tests (Atlas 99, Logbook 74, Postbox 42), and 0 skipped tests, failures or errors. All three AtlasStatisticsMariaDbTest cases passed. No live cross-region game route or third-node route was tested in this review.
