# Cast

NPCs a vanilla client can see, for other mods to give roles to. Two bodies:

- **Human** — a player-model NPC with a real account's skin. It is a
  *phantom*: rendered purely by packets to the players who can see it, never
  added to the world. So it is not a player: it does not count towards
  sleeping, does not anchor mob spawning, does not keep chunks loaded and is
  not in the tab list.
- **Mob** — any vanilla creature, owned by Cast from spawn. Villagers and the
  other brain-driven mobs have their behaviours removed every second; every
  body gets look-at-player goals, invulnerability, a name, and never opens its
  own screen (a villager NPC does not trade).

Depends on nothing. [Chronicler](https://github.com/Sablednah/Chronicler)
uses it for quest givers; [LegendQuest StoryTeller](https://github.com/Sablednah/LegendQuest-StoryTeller)
uses it for its cast. Server-side only; vanilla clients see everything.

**Status: 0.1.0 (unreleased).** Built 2026-09-07 and play-tested the same
day: a human with a real skin renders on a vanilla-protocol client and turns
to follow you; villager and cow bodies stand and look. StoryTeller possesses
both kinds.

## Commands (`cast.admin` or op level 2)

| Command | What |
|---|---|
| `/cast spawn human "<name>" [skinAccount]` | a human where you are looking |
| `/cast spawn mob <entity> "<name>"` | a creature where you are looking |
| `/cast remove` / `name <text>` / `skin <account>` / `look <bool>` / `here` / `say <text>` | act on the NPC you look at, or the nearest |
| `/cast role add\|remove <id>` | give the NPC a role another mod registered |
| `/cast list` / `status` | what exists |

Names may have spaces (quote them). A human's name tag shows the first 16
characters. Skins are fetched from Mojang once and cached; a fetch that fails
leaves the default skin and the NPC still stands.

## For other mods

Import `com.sablednah.cast.api` from one guarded class behind a
`ModList.isLoaded("cast")` check. `Cast.registerRole(id, handler)` makes a
right-click on an NPC carrying that role call you. `Cast.spawnHuman`,
`spawnMob`, `remove` (idempotent, works unloaded), `byId`, `npcAt(viewer, reach)`,
`say`, `lookAt`, `drive` (a teleport), `setRolesEnabled(player, false)` to
work on an NPC without talking to it, and `NpcRemovedEvent` on the game bus.
Every mob body carries `cast:npc` in its persistent data, the public marker.

## Building

```bash
export JAVA_HOME=/path/to/jdk21
./gradlew build     # -> build/libs/cast-<version>+mc1.21.11.jar
```

## Licence

MIT.


## Gravity

NPCs obey gravity: with nothing under their feet they drop to the ground once a second and their anchor follows them down, phantom or body alike. `/cast defygravity true` (or `Cast.setDefyGravity`) keeps one exactly where it was put with nothing underneath. Named for the laugh, kept for the use.

## Dressing

`/cast equip <slot> <item>` dresses the NPC you are looking at (mainhand, offhand, head, chest, legs, feet; the item written as `/give` takes it, blank to clear). Vanilla clients see it on phantoms and bodies alike, and it survives restarts. From code: `Cast.equip(server, id, slot, item)` and `Cast.equipment(server, id)`.
