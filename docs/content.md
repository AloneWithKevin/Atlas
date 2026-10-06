# External selector lore content

Atlas 0.1.3 contributes one mini-glyph through its shipped `closet.yml`:

| ID | Font and codepoint | Display metrics | Texture |
| --- | --- | --- | --- |
| `atlas:lore/action` | `atlas:lore`, U+E000 | width 8, height 8, ascent 7 | `pixelretreat:gui/atlas_lore/action.png`, transparent 16x16 |

The generic green action marker is a byte-exact reuse of the preserved
`pixelretreat:gui/scrapbook_lore/action.png` bitmap. Its SHA-256 is
`01458A139E1E62FF3C27A2402E9A557B0A692EC65E8EEC16FFFA7B5F9FC41C81`.
The original is retained. Atlas owns the new font and texture paths, so registration
does not collide with other owners' paths or modify a shared font/codepoint.

The selector's existing `items.selector.lore` begins with this font-qualified icon.
The text after it, including the two-space separator, remains unchanged. Closet's
validated asynchronous owner registration automatically contributes both assets to
the pack inputs and lists the glyph in its catalog.

For a separately authorized release, install the repository's `content/assets/` tree beneath
the Atlas data folder's `content/`, alongside the matching manifest and message catalog.
Assets are not extracted from the JAR and are never copied into code build output.
Missing content fails registration. The matching Closet pack must be built and
published through its approved release process before client rendering can be accepted.
That installation/publication was not performed by this source task.

Keep documentation outside `content/`: Closet validates every file under that
asset root as a pack input.
