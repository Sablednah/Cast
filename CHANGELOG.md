# Changelog

## 1.0.0 — 2026-09-12

The first release. Everything below is in it.

- Phantom names no longer appear in command suggestions: the player-info entry is withdrawn two seconds after the phantom appears (`npcs.tabEntrySeconds`).
- Build stamp: commit, branch and time in the manifest, `/cast/build.properties`, the startup line and `/cast status`.
- The coyote gag (`npcs.coyote`): an NPC losing its footing looks down, looks back up, then falls.
- Gravity: NPCs drop to the ground when the block under them goes and the anchor follows; `/cast defygravity` keeps one hanging.
- Dressing: `/cast equip <slot> <item>` and `Cast.equip`; items kept as /give strings in the spec, built at use time, sent to viewers of a phantom and set on a body.

- Human phantoms: player-model NPCs rendered by packets, never in a level;
  signed skins from Mojang, cached; one mixin to catch right-clicks.
- Mob bodies owned from spawn; brain-driven mobs (villagers) neutralised
  with behaviours removed each second; look goals; invulnerable; no own screen.
- Solid phantoms (an invisible armor-stand proxy), anchored mob bodies, an
  anchor a possessor can suspend (`setAnchored`), bodies re-owned on every
  level join so a restart never hands them their vanilla goals back.
- NpcStore (SavedData), roles registry, per-player role suppression,
  `NpcRemovedEvent`, `/cast` commands, `api.Cast` facade, self-test.
