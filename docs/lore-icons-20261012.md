> **Historical release note (12 October 2026).** This file records the earlier icon-resolution release. Closet is the sole owner of the current selector action glyph texture/font source and installed copy. Historical Atlas content paths do not establish current asset ownership. See [content](content.md) and [Closet pack ownership](closet-pack-ownership.md).

# Lore icons — 12 October 2026

Ferry selected inventory numbers 1, 2, 6, 12, 13, 21 and 28–36 for redesign,
with explicit freedom to replace the whole picture and choose a clearer semantic
symbol. Other 16×16 icons are enlarged without redrawing. His original minimum
64×64 requirement also covers six older non-square images; integer enlargement
keeps their pixels, alpha and aspect ratio intact. Existing approved 64×64 hand
and storage artwork is retained. No shader/filtering, game behavior, text, limits,
permissions, rank allocation, inventory data or server lifecycle changes follow.

The complete release covers 59 originally undersized images, their shared plugin
copies and two new owned action textures. All existing font providers retain
their identities and height/ascent; source resolution is separate from the small
game display size. Amount/uses gets a clear number sign; selected status,
information, gift, background, block, globe, area, date, limit, pin, ruler,
deny and request artwork uses the existing approved pixel-art style.

Fence previously used the trade bitmap for action identity B. Its registered
action now uses a dedicated L cell and owned click texture; five physical-item
action templates are updated, while token trading retains B. Pier's three fishing
control actions use a new E006 action cell; original E002 trade remains intact.
These are proven semantic corrections rather than a global trade substitution.
Other genuine trading lines keep their trading symbol.

Deployment target is VeyraTestNA01 only. External PNGs/font manifests require the
existing stopped-target installation contract; a queued JAR does not replace
already extracted assets. Prepare matching catalogs/manifests and verified builds,
preserve other values/credentials/newer queues, then install these exact files
while Ferry has stopped NA. Ferry starts it and accepts the rebuilt pack. Existing
issued item metadata keeps its original lore; verify newly issued Fence items and
the current menu/fishing controls. No inventory migration is performed.

No new permission nodes or rank decisions. Existing defaults, children and approved
allocations stay in the plugin permission documentation and central Ranks.md.

## This plugin's assets

| Inventory number | Source path | Pixel size | Treatment |
| --- | --- | --- | --- |
| 67 | `content/assets/pixelretreat/textures/gui/atlas_lore/action.png` | 64×64 | pixels preserved; enlarged only |
