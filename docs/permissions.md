# Permissions

Every `/atlas` administration command, its tab completion, and selector issuance requires `atlas.admin`. OP is explicitly admitted in command and listener checks. `atlas.admin` has the declared child `atlas.gamemode.bypass: true`.

| Permission | Default | Purpose | Children | Approved rank allocation | Current read-only LuckPerms evidence |
| --- | --- | --- | --- | --- | --- |
| `atlas.admin` | OP | Every Atlas management command and staff selector delivery. | `atlas.gamemode.bypass: true` | `manager`, recorded in `Ranks.md`. | No explicit Atlas grant was found on groups or users in the captured database; runtime manager grant is therefore not verified. |
| `atlas.gamemode.bypass` | OP | Keep your game mode while in a world with a forced game mode. | None of its own. | `manager`, recorded in `Ranks.md`; also inherited from `atlas.admin`. | No explicit Atlas grant was found on groups or users in the captured database; runtime manager grant is therefore not verified. |
| `atlas.portal.use` | `true` | Use Atlas portals. | None. | `default`, recorded in `Ranks.md`. | No explicit grant was found; Bukkit's declared `true` default currently admits non-OP players. |
| `atlas.portal.<name>` | OP, registered at runtime for each restricted portal | Additional access to that portal. | None. | Deliberately unassigned by Ferry's 4 October decision. | No explicit Atlas grant was found on groups or users. |

There are no numeric, unlimited or exemption permission families. The dynamic portal permission remains deliberately unassigned. OP access is implemented separately from non-OP rank allocation.

The approved allocations are written in [Ranks.md](../../PixelRetreat%20-%20Documentation/Ranks.md). They are rank decisions, not proof that the same grants are installed in LuckPerms. The current read-only capture found no explicit Atlas nodes on any captured group or player. No runtime permission provider or grant was changed by this audit. Before relying on manager administration in game, verify the installed grant through the owner's normal permission-management process.

The staff-panel `Ranks 11/09` task (ID 94, updated 23 September 2026) has no Atlas-specific limits. Atlas has no rank caps.
