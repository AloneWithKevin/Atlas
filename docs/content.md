# Atlas item and glyph definitions

Atlas defines one Closet item: `atlas:selector`.

| Stable ID | Definition | Function | Acquisition |
| --- | --- | --- | --- |
| `atlas:selector` | One unbreakable `WOODEN_AXE`, maximum stack size 1, with Atlas name and lore keys. | Staff mark the first corner with a left-click and the second with a right-click. The same selection supports portal bounds and keep-loaded regions. | Staff only, through `/atlas selector` or `/atlas selector <online-local-player>`. There is no recipe, public grant, reward, or player shop route. Closet owns delivery and any overflow. |

The lore references the Closet glyph `atlas:lore/action`: font `atlas:lore`, codepoint U+E000, display width/height 8 and ascent 7. The glyph is an action cue, not a new command or feature. Selector text retains the approved action wording and corner order.

Atlas contains only the item and glyph definitions. Closet is the sole owner of the texture, font and all other resource-pack files. Their maintained source and installed pack live under Closet. Atlas does not bundle asset files in its JAR, extract them to its data folder, or register a duplicate asset source. See [Closet ownership](closet-pack-ownership.md).

Atlas creates no custom block, machine, recipe, reward, GUI icon or preview item. A portal can be filled with an ordinary placeable block and the configured portal region restores the affected fill when removed; the fill is not a Closet item identity.

The selector is not a survival-player tool. In-game confirmation should check its name/lore/glyph and corner-selection behavior after the matching Closet pack has been loaded.

Selector issuance checks the original requester/target after asynchronous preparation and refuses while Compass owns the target inventory. It performs no selector intent SQL or saved-inventory proof. Unknown delivery needs investigation; joining never resumes a selector grant.
