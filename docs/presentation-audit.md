# Message and interface audit

Reviewed on 2026-10-05 for Atlas 0.1.2 against the message and item palettes in the
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
| Menu backgrounds, custom selector model/texture, fonts/glyphs, sound assets | None referenced or bundled | None required |

The existing PixelWorlds distribution JAR was inspected as well: 33 owned class entries,
no inventory-menu/display/custom-model references and no asset entries. The selector
has no custom model or font; its appearance comes from the vanilla wooden axe.

The actual PixelItems source content/pack trees, preserved Closet legacy content/assets,
and installed VeyraTest01 Closet content were reconciled with those references. No
PixelWorlds/Atlas artwork or content definitions were found. The historical Survivaleu01
PixelItems directories are no longer present; their previously captured content remains
in the Closet baseline. No missing GUI needs a Compass conversion, and this change adds
no shared Compass/Closet files, custom art, glyphs or resource-pack output.
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

## Release and owner check

Atlas 0.1.2 is a source/build release. The previously staged Atlas 0.1.1 JAR and installed
message file have not been replaced by this audit. Applying the updated messages and JAR
requires a separately authorized deployment. No new permissions or schema changes exist.

After that release, the owner checks help/usage, world/portal/region information, selection
confirmations, selector tooltip, error/success messages and normal portal travel in game.
Technical rendering tests do not establish client or in-game acceptance.
