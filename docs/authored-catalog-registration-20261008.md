> **Historical implementation note (8 October 2026).** The authored Atlas catalog was registered with Campfire before message-dependent behavior started. The audit at that time observed English rendering after a viewer locale change. Current recipient-language delivery and the native refusal-renderer requirement are described in [architecture](architecture.md) and [presentation audit](presentation-audit.md). The implementation history below retains its original scope.

# Authored catalog registration

The actual CampfireDelivery resolves keys through CampfireLocalization, while
CampfireMessages alone registers the separate English catalog. Startup now
registers the real authored localization catalog before delivery-dependent
features are exposed. Atlas/Fence reload refreshes both catalogs. This fixes the
joint-runtime integration; no automatic translation or new message/rank policy.
Missing provider or invalid catalog fails closed. Matching Campfire remains
required; see workspace PLUGIN_INTEGRATIONS.md. Necessary shadowJar passes;
server restart and game acceptance belong to Ferry.
