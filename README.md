![Cast](docs/wordmark-850.png)

# Cast

NPCs a vanilla client can see, for other mods to give roles to. Cast has no
quests, no dialogue and no scenes of its own; it makes people and creatures
that stand where you put them, look at you, wear what you dress them in, and
do whatever the mod that placed them says when right-clicked.

Depends on nothing. [Chronicler](https://github.com/Sablednah/Chronicler)
uses it for quest givers; [LegendQuest StoryTeller](https://github.com/Sablednah/LegendQuest-ReForged)
possesses them for live scenes. Server-side only; vanilla clients see
everything. The store page is [CURSEFORGE.md](CURSEFORGE.md).

**Status: 1.0.0.** Play-tested on a vanilla-protocol client: humans with real
skins, villager and cow bodies, possession, dressing, gravity, the lot; driven
end to end on a second machine by keystroke. Self-tested headlessly (94 checks)
on all three Minecraft lines.

## Two kinds of body

- **Human** -- a player-model NPC with a real account's skin. A *phantom*:
  rendered purely by packets to the players who can see it, never added to the
  world. Not a player: does not count towards sleeping, does not anchor mob
  spawning, does not keep chunks loaded, not in the tab list, not in `/tp`
  suggestions (the player-info entry that carries the skin is withdrawn
  `npcs.tabEntrySeconds` after it appears). Nothing stands in its
  space: an armour-stand proxy used to, until it was measured to block nothing.
- **Mob** -- any vanilla creature, owned by Cast from spawn. Goals cleared and
  replaced with look-at-player; brain-driven mobs (villagers and friends) have
  their behaviours removed every second, which is the only way to park them.
  Invulnerable, named, never opens its own screen.

## Anchoring and gravity

Both kinds stand where placed: a shove is undone within a second. Possession
(StoryTeller) suspends the anchor and re-anchors wherever the body is let go;
a body released mid-air falls first and its landing becomes home. Mine the
block from under one and it looks down, looks back up at you, then falls
(`npcs.coyote`, two seconds of dawning realisation). `/cast defygravity true`
keeps one exactly where it was put with nothing underneath.

## Dressing

`/cast equip <slot> <item>` -- mainhand, offhand, head, chest, legs, feet; the
item written as `/give` takes it (`minecraft:leather_chestplate[dyed_color=16777215]`);
blank clears. Sent to phantom viewers, set on bodies, remembered across
restarts. A wrong slot or item is refused with the NPC named and the reason.

## Moving

**Following** walks the trail the leader actually walked -- through the door they
used, down the stairs they took -- so a phantom with no navigation never tries a
wall. It stops a couple of blocks short, speeds up when it has fallen well behind,
and past `motion.followTeleport` blocks appears a few steps back along the trail.
A follow is **leased**: whoever asked renews it, and when renewals stop the NPC
stops and is anchored where it stands. `/cast follow` (five minutes) and
`/cast follow stop` try it by hand. **Walking** (`Cast.walkTo`) goes to a point and
stands there: a phantom in a straight line that climbs a block and passes through
anything taller, a mob body on its own legs; either is put there after a minute.

**Lurking** is the caged zombie in the basement. Put the NPC at the back of the
cell, stand at the door and run `/cast lurk door`: it hides where it stands, groans
now and then while someone is within `radius` (8), and roughly every `every`
seconds (20, randomised) while someone is, rushes the door, hammers on it three
times and slinks back into the dark. `/cast lurk radius <blocks>`, `every <seconds>`,
`scare` (now), `off`. Saved with the NPC.

## Commands (`cast.admin` or op level 2)

| Command | What |
|---|---|
| `/cast spawn human "<name>" [skinAccount]` | a human where you are looking |
| `/cast spawn mob <entity> "<name>"` | a creature where you are looking |
| `/cast remove` / `name <text>` / `skin <account>` / `look <bool>` / `here` / `say <text>` / `equip <slot> [item]` / `defygravity <bool>` | act on the NPC you look at, or the nearest |
| `/cast role add\|remove <id>` | give the NPC a role another mod registered |
| `/cast follow` / `follow stop` | follow you for five minutes, or stop |
| `/cast lurk door [pos]` / `radius <blocks>` / `every <seconds>` / `scare` / `off` | the lurker: hides where it stands, rushes the door (where you stand) |
| `/cast list` / `status` | what exists, and which build this is |

Names may have spaces (quote them). A human's name tag shows the first 16
characters. Skins are fetched from Mojang once and cached; a fetch that fails
leaves the default skin and the NPC still stands.

## For other mods

Import `com.sablednah.cast.api` from one guarded class behind a
`ModList.isLoaded("cast")` check. `Cast.registerRole(id, handler)` makes a
right-click on an NPC carrying that role call you. `Cast.spawnHuman`,
`spawnMob`, `remove` (idempotent, works unloaded), `byId`, `all`, `npcAt(viewer, reach)`,
`npcHitAt`, `say`, `lookAt`, `drive` (a teleport), `equip` / `equipment`,
`setAnchored`, `setDefyGravity`, `rename`, `setRoles`, `pinViewer`,
`follow(server, npc, leader, leaseTicks)` / `stopFollowing` / `leaderOf`, `walkTo`,
`isMoving`, `setLurk` / `clearLurk` / `scare`,
`setRolesEnabled(player, false)` to work on an NPC without talking to it, and
`NpcRemovedEvent` on the game bus. Every mob body carries `cast:npc` in its
persistent data, the public marker. Gravity and anchoring are Cast's alone; a
mod that wants a floating NPC asks for it rather than setting it.

## Config (`cast-common.toml`)

`npcs.viewRange`, `npcs.lookRange`, `npcs.coyote`, `npcs.tabEntrySeconds`,
`skins.fetchSkins`, `motion.walkSpeed`, `motion.rushSpeed`, `motion.followTeleport`,
`motion.lurkGrowlChance`.

## Requirements and building

| Minecraft | NeoForge | Java | branch |
|---|---|---|---|
| 1.21.11 | 21.11.42+ | 21 | `main` |
| 26.1.2 | 26.1.2.95+ | 25 | `mc26.1` |
| 26.2 | 26.2.0.72+ | 25 | `mc26.2` |

```bash
export JAVA_HOME=/path/to/jdk21     # jdk25 on the 26.x branches
./gradlew build                     # -> build/libs/cast-<version>+mc<mc>.jar
./gradlew runServer -Pselftest      # the headless self-test
```

Every jar carries a build stamp (`/cast status`, the startup log line, the
manifest's `Build-Commit`), so two jars with the same name can be told apart.

## Licence

MIT.
