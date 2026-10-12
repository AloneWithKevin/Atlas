# Closet-owned resource pack

Ferry's12 October2026 decision makes Closet the sole owner of every pack file.
This plugin bundles and installs definitions/configuration only. Its item/glyph/lore
IDs, text, colours and display metrics are unchanged. Definitions register through
Closet's `registerWithClosetAssets` API7, with no local assetsRoot. Closet validates
references and pins the active source hashes; missing, changed or revoked assets
refuse dependent work. Duplicate asset ownership remains an error.

The pack lives in `plugins/Closet/contents/pack/assets/`; matching sources live in
Closet's `content/legacy/pack/assets/`. File I/O stays on bounded provider workers.
Deploy the matching Closet and consumer JARs together; update contains only JARs.
Only byte-identical verified former local pack files may be removed after builds.
The owner restarts NA and checks startup and unchanged icon/lore presentation.
Permissions and their existing rank allocations are unchanged.
