> **Historical implementation note (8 October 2026).** The authored Atlas catalog is registered with Campfire before message-dependent behavior starts. The current audit verifies the real delivery route: authored Atlas strings render in English after a viewer locale change. See [presentation audit](presentation-audit.md) and the external [Atlas final report](../../Ferry/Eindrapporten/Atlas.md) for current source and test status.

# Authored catalog registration

The actual CampfireDelivery resolves keys through CampfireLocalization, while
CampfireMessages alone registers the separate English catalog. Startup now
registers the real authored localization catalog before delivery-dependent
features are exposed. Atlas/Fence reload refreshes both catalogs. This fixes the
joint-runtime integration; no automatic translation or new message/rank policy.
Missing provider or invalid catalog fails closed. Matching Campfire remains
required; see workspace PLUGIN_INTEGRATIONS.md. Necessary shadowJar passes;
server restart and game acceptance belong to Ferry.
