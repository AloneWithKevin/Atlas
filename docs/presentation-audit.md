# Message and interface audit

2026-10-06, 0.1.7: eight additional fixed templates are catalog-bound: two
travel-ticket console warnings, the delete-re-enabled diagnostic and five destination
descriptions (local/remote spawn/location and bounds). All 112 catalog keys are
validated. Destination strings remain plain nested values with existing outer color
scope; negative decimal coordinates retain Java integer truncation. Runtime phase,
receipt and rule identifiers remain technical data; Bukkit metadata is separate.
The generic bootstrap policy and earlier exact/editorial text approvals are retained.

2026-10-06, 0.1.6 editorial review: 97 remaining entries reviewed under the owner's
delegated editorial approval. 42 values polished; 55 retained. The seven existing
exact approvals are unchanged. Chat, selector actions and lore keep their meaning,
conditions, placeholders, palette, white glyph/font and two separating spaces.
The empty portal-list wording stays precise instead of repeating the same fact.
No new GUI, name, asset, metadata, permission or runtime behavior is introduced.

2026-10-06, 0.1.5: owner approved exactly world.clone-queued, world.reset-queued
and world.confirm-reset to explain separate preparation/activation starts.
The 104-key catalog and placeholder/color contracts are unchanged. Bootstrap
console errors now use the existing bundled startup.failed template; technical
exception details are not appended. Other outstanding wording remains under review.

Reviewed on 2026-10-05 for Atlas 0.1.2/0.1.3 against the message and item palettes in the
current Veyra plugin standards, sections 11 and 12.

## Legacy interfaces and assets

The complete PixelWorlds production source and configuration (20 Java files and three
resources) were inspected again:
bootstrap, command execution/completion, messages, permissions, API, world settings and
file operations, portal services/listeners, keep-loaded regions/selection, generators,
records and teleport helpers. Its tests and message fixture describe the same command
interfaces. PixelWorlds stores its world/portal/region state in configuration, with no
database-backed presentation path.

| Interface | PixelWorlds | Atlas |
| --- | --- | --- |
| Commands, help and argument errors | Chat messages through PixelChat | Campfire catalog and delivery, with approved `/atlas` syntax |
| World/flag/setting information | Command output | Command output |
| Portal and keep-loaded management | Commands and chat confirmations | Commands and chat confirmations |
| Delete/reset confirmation | Explicit command argument | Explicit command argument; startup operations retained |
| Selection tool | Vanilla wooden axe, PDC identity, hardcoded name/lore | `atlas:selector` Closet definition with Campfire name/lore keys |
| Menus/submenus, pages, buttons, inventory input/confirmation screens | None | None required |
| Actionbars, bossbars, titles, hover text | None | None |
| Menu backgrounds, custom selector model/texture, fonts/glyphs, sound assets | None referenced or bundled | No legacy art missing; 0.1.3 adds one action-lore glyph |

The existing PixelWorlds distribution JAR was inspected as well: 33 owned class entries,
no inventory-menu/display/custom-model references and no asset entries. The selector
has no custom model or font; its appearance comes from the vanilla wooden axe.

The actual PixelItems source content/pack trees, preserved Closet legacy content/assets,
and installed VeyraTest01 Closet content were reconciled with those references. No
PixelWorlds/Atlas artwork or content definitions were found. The historical Survivaleu01
PixelItems directories are no longer present; their previously captured content remains
in the Closet baseline. No missing GUI needs a Compass conversion, and this change adds
no shared Compass/Closet files, custom art, glyphs or resource-pack output in 0.1.2.
The compile/test dependency now points to the available Closet 0.1.4 API/runtime
artifacts. The older 0.1.2 files are no longer present in the workspace. Atlas's existing
registration, identity and selector-grant contracts are preserved and validated against
the current public API; no provider-source changes are required.

## Message paths and corrections

All catalog keys and their call sites were checked, including console startup results,
permission/input/error messages, help/usage, queued operations, world information,
teleport/spawn, flags/settings, selection, portals, keep-loaded regions and reload.
Closet's selector name/lore references were checked against the same catalog.

- Ordinary chat retains Campfire's default color. Subjects/labels use `#F3E5AB`;
  commands, option hints and actions use `#A8D5A2`.
- Previously plain usage and information labels, portal/region information, operation
  identifiers and action hints now follow those roles.
- The selector name and corner values remain white; left/right-click instructions use
  the action color. Existing one-line lore, two-space separator and text are preserved.
- No prices, GUI titles/buttons, hover text or player/rank/tag color overrides exist
  in Atlas. No extra lore was added.
- Every visible string remains a stable `messages.yml` key rendered through Campfire;
  input values remain unparsed. No command or gameplay behavior changed.

All 104 before/after keys were compared after removing color tags: 51 changed colors only.
English wording,
placeholder names, punctuation, line order, whitespace and empty lines are identical.
Tests render through the real Campfire catalog, inspect effective colors, detect color
bleeding and verify literal input cannot create click/hover events. The selector still
passes real Closet contribution validation. See [verification](verification.md).

## Small lore glyph follow-up, 0.1.3

The owner's 2026-10-05 clarification requires the plugin to implement the small icons
in its existing lore itself. Atlas has exactly one such surface: the selector's existing
action line. It now begins with `atlas:lore/action`, U+E000 in the private `atlas:lore`
font. No new lore line, screen, label or gameplay feature is introduced. The earlier
text/layout preservation requirement is reconciled by adding only this glyph and its
following space; every existing word and the two-space action separator remain exact.

The 16x16 transparent green action marker reuses the preserved Scrapbook action bitmap
byte-for-byte, under an Atlas-owned asset path. It has the same generic action meaning;
there is no new drawing or modification of the original asset. Display height 8 and
ascent 7 match the existing mini-glyph style. The character is explicitly white to
preserve its authored green pixels; click text remains green and corner values white.
Only the character uses the custom font.

`closet.yml` registers the item, glyph and external `content/` atomically through the
existing asynchronous Closet startup stage. Both font JSON and texture automatically
enter Closet's required pack inputs and catalog; no central provider file or font slot
is changed. Missing content fails registration before dependent Atlas actions enable.
The authoring assets remain outside compilation output and the JAR. See
[external content](content.md) for the separate installation contract.

All 41 tests pass, including actual Closet registry/catalog/pack-input publication,
real Campfire rendering, bitmap dimensions/transparency/font reference checks and
font/color confinement. The 104-key catalog is unchanged except for that action glyph
prefix relative to 0.1.2. All 54 runtime classes and the API JAR remain identical to
verified 0.1.1. No extra permission or database schema exists.

## Release and owner check

Atlas 0.1.3 is a source/build release. Installed Atlas 0.1.1 and its messages have not
been replaced by these audits. Applying the updated messages, manifest, external
content and JAR requires separately authorized deployment and a matching Closet pack
release. No deployment, live pack publication or server restart occurred here.

After that release, the owner checks help/usage, world/portal/region information, selection
confirmations, selector tooltip including its mini-glyph, error/success messages and
normal portal travel in game.
Technical rendering tests do not establish client or in-game acceptance.
