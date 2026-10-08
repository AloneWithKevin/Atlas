# Authored catalog registration

The actual CampfireDelivery resolves keys through CampfireLocalization, while
CampfireMessages alone registers the separate English catalog. Startup now
registers the real authored localization catalog before delivery-dependent
features are exposed. Atlas/Fence reload refreshes both catalogs. This fixes the
joint-runtime integration; no automatic translation or new message/rank policy.
Missing provider or invalid catalog fails closed. Matching Campfire remains
required; see workspace PLUGIN_INTEGRATIONS.md. Necessary shadowJar passes;
server restart and game acceptance belong to Ferry.
