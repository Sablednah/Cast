# Changelog

## Unreleased (1.1.0)

- Fix: exposed NPCs were flatly ignored by monsters in play -- `setTarget` from outside the AI loses to the mob's own targeting goal re-evaluating and clearing it. Now a real goal in the mob's own target selector, arbitrated the same way as its other targeting goals.
- **Provoke**: a real hit on a monster currently after an exposed NPC turns it on the hitter for `motion.threatSeconds`, so a player can pull it off the NPC on purpose; it reverts to the NPC on its own once the window lapses with no further hit.
- Fix: a monster could never be pointed at a `FakePlayer`-backed target (every human-kind NPC, and anything standing in for a player) -- newer Minecraft's `Mob#setTarget` silently refuses an invulnerable target, and `FakePlayer`'s own constructor makes every one invulnerable from birth. `Exposure` now clears it on whatever it exposes, body or human.
- Fix: two followers of the same leader stood on top of each other. Each now holds a stable slot beside the leader, with a small per-NPC pace variance.

- **Swing**: `Cast.swing(server, npc, hand)` -- a one-shot gesture for a scripted scene (a blacksmith striking an anvil, a guard warning somebody off). Works on either kind: a mob body under the same vanilla call any mob would use, a human phantom through the same per-viewer packet push everything else about it already goes through, since it has no ordinary entity tracker to broadcast one on its own. Raised by the LegendQuest/StoryTeller session (`/st swing`), which could reach mob bodies already but had no way to reach a phantom.
- **Following**: `Cast.follow(server, npc, leader, leaseTicks)` walks the trail the leader walked, stops short, catches up, and lets go when the lease is not renewed. `/cast follow`, `/cast follow stop`. For Chronicler's escort quests.
- **Walking**: `Cast.walkTo` goes to a point on foot and stands there, anchored.
- **Lurking**: `/cast lurk door` and `Cast.setLurk` -- the caged zombie. Hides at the back, groans when someone is near, now and then rushes the door, hammers on it and slinks back. Saved with the NPC.
- **Hits**: `NpcHitEvent` for every blow on an NPC (still no damage), and `Cast.expose` -- leased -- sets nearby monsters on an NPC so an escort can be in danger. `motion.lureRadius`.
- Config `motion.walkSpeed`, `motion.rushSpeed`, `motion.followTeleport`, `motion.lurkGrowlChance`.

- Bodies land on a block's shape (a slab, a snow layer, a path), not on the block above it; a drop under 0.6 settles silently, only a real fall gets the gag.
- A spawn handed a Y inside the ground is raised out of it before it is stored, so nothing is ever buried and a possessor is never shoved sideways.
- The invisible armour-stand proxy is gone: armour stands block nothing in 1.21.11 (measured by the LegendQuest session), so it was an entity per phantom for no effect. Old proxies are reaped on sight.

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
