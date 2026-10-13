# Closet-owned resource pack

Closet is the sole owner of every resource-pack file: textures, fonts, models, sounds and related assets. Atlas owns only its item/glyph definitions, authored text and references to Closet-owned stable IDs.

Atlas registers `atlas:selector` and `atlas:lore/action` through Closet's public `registerWithClosetAssets` contract. The current manifest declares the selector as an unbreakable single-stack wooden axe and the action glyph under `atlas:lore` at U+E000. It does not contain an `assets-root` or copy of a pack file.

Closet validates the definition and resolves each asset from its maintained source tree. Missing, changed or ambiguous assets cause registration to fail before dependent Atlas behavior is enabled. Duplicate ownership remains an error. Keep all item/lore identifiers in Atlas while keeping the physical assets in Closet.

Build the matching Atlas and Closet API artifacts from the verified workspace sources. Deploy a JAR only through the plugin update path; pack files belong directly in Closet's maintained/installed pack tree under the standing asset procedure. Never stage textures, fonts, manifests or other pack files in `plugins/update/` or copy them into Atlas's plugin data folder.

The matching server/client pack and exact source/install hashes are recorded in the Atlas final audit report. Client reception and visual acceptance require the owner's in-game check.
