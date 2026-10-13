# Logbook integration for Atlas

Atlas owns Atlas statistics; Logbook archives, projects and reads those facts. The build does not add player-visible history or a second gameplay source.

## Capture and ingestion

Atlas records seven approved groups: observed world visits; portal attempts/refusals; the original portal route with acceptance, ticket consumption, terminal outcomes and duration; world-management diagnostics for create/import/enable/disable plus durable queued/terminal/duration facts for clone/reset/delete; current keep-loaded configuration/ticket snapshots; staff mutation diagnostics; and explicit coverage/capture gaps.

Durable portal and world-operation outcomes are joined to their original Atlas source rows and written to the Atlas outbox in the source transaction. Exact future event slots are reserved before the durable action. An exhausted outbox rejects that action under the existing Atlas contract. Unresolved outcomes retain their holds and stay UNKNOWN. Logbook's active collector uses the current Postbox EU/NA identity and drains the Atlas namespace only. Ingestion is transactionally replay-safe.

World visits use the observed event boundary as their time origin. The observation UUID and UTC Instant are captured before bounded SQL work is queued. Queue latency cannot shift the source timestamp or add time to the duration. Unobserved ends and restart gaps remain unknown.

## Read and staff output

The local Logbook build activates only api, client, core, and performance; the general command plugin and general website project remain paused. Atlas consumers use the in-process asynchronous LogbookApi. readDistributions returns exact BigInteger sum and sample count; do not calculate a mean with a fabricated denominator.

The staged registry helper is designed for the existing staff Stats page and its existing `stats` permission gate; it is not installed on the hosted site. For Atlas it displays aggregate Network rows, separate EU and NA rows, and a combined EU+NA row. Distribution rows preserve sum and sample count. Current keep-loaded snapshots show the latest separate backend values and a combined sum only when both backend snapshots are proved. The owner explicitly chose to retain internal UUID statistics while showing aggregates in the staff overview. Atlas registry reads use only aggregate `subject='*'` rollups and do not list individual player UUIDs, per-player routes, or coordinates. The separate hard-coded whitelist in `website/public/_inc/logbook_dashboard.php` contains no `atlas:*` definitions, so the staged generic player search/profile does not expose Atlas UUID facts. Trusted in-process API consumers may still query UUID-keyed facts.

Atlas portal_attempts, admin_actions and observation_gaps are daily UTC aggregate counters. A native bounded query over these facts is exact only at complete UTC-day boundaries. Partial-day or one-sided ranges return UNKNOWN with no number; all-time totals remain unchanged. diagnostic_gaps has flush-interval grain and is unknown for any bounded range. Exact original portal/world/visit duration facts retain their event timestamps.

## Existing page and host boundary

The output reuses the existing staff Stats page. Its staged implementation changes only:
- website/public/_inc/logbook_registry.php
- website/public/staffpanel/stats/logbook-registry.php
- website/tests/dashboard.php
- website/tests/staff-stats-auth-denied.php

The existing route view.php checks stats before including the dashboard. The existing stats wrapper delegates to the host-owned staff-panel front controller; its authentication/session code and navigation remain unchanged. The Atlas section is loaded through the existing overview-dashboard/reports include path. The Growth-tab Tebex helper and _inc/pixelstats.php are a separate, conditional host dependency, not part of the Atlas consumer changes. They are not needed to reach the Atlas output on Overview.

The PHP dashboard fixture seeds matching projected SQL rows directly; it verifies the PHP adapter contract but does not execute Java ingestion in the same test process. Separate MariaDB tests verify Atlas source-to-collector-to-public-API ingestion and read semantics. The denied-route fixture stubs the host permission function; the real page gate is verified from view.php source, not from a live host login/session. The PHP fixture and adapter source are not a website runtime installation. The host's filled website/public/_inc/logbook-db-config.php stays outside source control and is never included in the test database connection. The shared harness supplies LOGBOOK_TEST_DB_PORT=33319, LOGBOOK_TEST_DB_DATADIR=<private generated directory>, and LOGBOOK_TEST_DB_SCHEMA=logbook_website_dashboard_test; the fixture refuses any other port/schema or nonmatching MariaDB directory/bind address and validates the exact marker before reusing a schema.

No Logbook JAR, website, server, queue or production database is deployed or modified by this documentation.
