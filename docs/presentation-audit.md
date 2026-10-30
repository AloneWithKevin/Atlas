# Message and interface audit

This current section supersedes the dated 0.1.2/0.1.3 audits below. Those records remain useful as historical evidence for the prior release and must not be read as current deployment state.

## Current authored messages

The current Atlas message contract contains 114 keys in `AtlasMessages.CONTRACT` and `src/main/resources/messages.yml`. It includes the current diagnostics and operation/destination templates, including `diagnostic.statistics`. Resource tests validate the required keys/placeholders and exercise actual Campfire rendering. Atlas registers its authored catalog before message-dependent behavior becomes ready and calls Campfire's public delivery service.

Campfire delivers authored messages in the recipient's selected language. Portal destination and bounds descriptions remain keyed references until compound delivery, so nested prose shares one language and catalog snapshot with the outer message. Dynamic player input remains literal text; message placeholders do not become executable click/hover events. Console output stays English. The shared Campfire route supplies the established prefix. Native final closed-world refusals use the original recipient UUID and denied world through the matching Veyra refusal-renderer hook, with the original static refusal on unavailable translation. There are no Atlas-generated titles, actionbars, bossbars, holograms, custom GUI, button actions or player-facing hover surfaces. Selector item names and lore retain their existing definitions.

## Presentation rules

- Subject labels and world/portal values use soft yellow `#F3E5AB`.
- Action text uses green `#A8D5A2`.
- Selector name and coordinate values remain white.
- The selector lore begins with the small `atlas:lore/action` glyph followed by the existing action text and two-space separator.
- Console diagnostics are short keyed messages; raw exceptions and database credentials are not included.

The separate Ferry message inventory remains the line-by-line source for every authored key and its current presentation. Its Atlas section must be refreshed to 114 keys and include `diagnostic.statistics`; its archived 104-key count and old installed-key line reference are historical.

## Item and pack boundary

There is one Atlas item definition: `atlas:selector`. Its lore glyph is a Closet glyph identity; the texture/font files are solely Closet-owned. Atlas has no bundled pack assets. Do not describe the icon as Atlas-installed or imply that Atlas publishes the resource pack.

## In-game acceptance

The resource/catalog tests establish contract shape and server-side rendering, not the client's actual item tooltip or pack display. Ferry must verify selector name, action glyph, colors and corner instructions after a matching Closet pack is loaded. The full acceptance checklist is in the external [Atlas final report](../../Ferry/Eindrapporten/Atlas.md).
