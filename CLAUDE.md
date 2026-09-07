# CLAUDE.md

Guidance for Claude Code working in this repository.

## What this is

**Cast** — NPCs a vanilla client can see, for other mods to give roles to.
Built for Chronicler (quest givers) and LegendQuest StoryTeller (a GM's cast);
depends on nothing. Mod id `cast`, package `com.sablednah.cast`, MIT. Eighth
in Sable's NeoForge series; `../Chronicler/CLAUDE.md` and
`../SableCraft-Standards/CLAUDE.md` carry the family conventions this repo
follows without restating them (vanilla first, don't make me think, sensible
defaults, Lang + messages.yml, `§` never in a literal, `word()` never for
punctuation, a self-test that parses AND executes, boot checks that print
ERROR lines not counts).

| | |
|---|---|
| Minecraft / NeoForge / Java | 1.21.11 / 21.11.42 / 21 (`main`) |
| Dev server | port **25574**, RCON **25584** (`run/server.properties`, gitignored) |
| JAVA_HOME | `/home/sable/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2` (no system Java) |

```bash
./gradlew compileJava              # ~20s; the FIRST build after touching accesstransformer.cfg is 10+ min
./gradlew runServer -Pselftest     # neoforge/SelfTest on ServerStartedEvent; read "Cast SelfTest:" and every FAILED:
./deploy.sh                        # -> the CurseForge instance for this line
```

Run self-test boots in the FOREGROUND of one command (the harness's memory
monitor kills background boots when the sibling dev servers are up), kill only
the JVM whose command line has `Cast/build/classes`, and never `pkill` on
`java` or `fml.modFolders`.

## The design everything follows from

**A human NPC is a phantom, and that is a constraint, not a shortcut.** A real
`ServerPlayer` added to a level joins that level's `players()` on tracking
start: it counts towards sleeping, anchors mob spawning, holds chunk tickets
and shows in `/list`. Ten NPCs in a village and nobody can skip the night,
silently. So `HumanNpc` (a FakePlayer subclass) is never added to any level.
It is a source of packets: `Phantoms` decides who can see it and sends
player-info (`listed=false`), spawn, entity data and head rotation, and the
mirror on the way out. Sable confirmed this constraint on 2026-09-07.

**Exactly one mixin, and it is the phantom's right-click.** Vanilla's
`handleInteract` looks the entity id up in the level, finds nothing, and drops
the packet. `ServerGamePacketListenerImplMixin` injects at HEAD, acts only on
the server thread (the first pass runs on netty and vanilla's own
`ensureRunningOnSameThread` reschedules it), and cancels for our ids.
`defaultRequire: 1` makes it fail the launch loudly if it stops applying.

**A mob body is owned from spawn, so its rebuild may be one-way.** Goals are
cleared outright and the idle set added. That is ownership, not the reversible
parking StoryTeller does on wild mobs; the two must never be confused, and Cast
never parks a body it did not create.

**Brain-driven mobs cannot be parked by goals.** A Brain ticks in
`customServerAiStep` and never consults goal flags, so a priority-0 holder goal
is a silent no-op on a villager (the LegendQuest session verified this; their
villager citizens wandered). `Brains` uses vanilla's `Brain.isBrainDead()` —
false means born with a brain; it stays false after neutralising — and
`removeAllBehaviors()`, re-asserted every second because a brain regrows on
refresh. The 20-class list in `SelfTest` is a fixture asserting both
directions, never something the runtime reads.

**Identity is the npcId in `NpcStore`**, never an entity. Bodies are rebuilt
on chunk load; a human's entity UUID is a v3 UUID from the npcId; a mob body
carries `cast:npc` in persistent data (the public marker). `remove` is
idempotent and works unloaded.

**Skins never fail a spawn.** A player-model skin must be signed; it is fetched
off-thread from the session service for whichever real account the skin names
and cached in the store. Missing, unsigned or slow means default skin, standing.

**Possession lives in StoryTeller.** Cast gives it `Npc.entity()` (a real
object for a camera, though `level.getEntity` will never find a phantom),
`canPossess`, `drive` (a teleport, documented as one) and `NpcRemovedEvent`
on every path. `Cast.isBrainDriven` is a static both can call.

## Layout

```
core/    NpcSpec, NpcKind, NpcStore (SavedData: specs + cached skins)
npc/     HumanNpc, Phantoms (viewer tracking + packets), Bodies (mob bodies), Brains, Skins, Npcs (the manager)
api/     Cast (facade), Npc (handle), Role, NpcRemovedEvent   <- what other mods import
neoforge/ CastEvents, CastCommands, CastPermissions, Lang, Feedback, SelfTest
mixin/   ServerGamePacketListenerImplMixin, InteractPacketAccessor
```

## Known traps

- **Every mixin, accessors included, must be LISTED in `cast.mixins.json`.**
  An accessor that merely sits in the package throws
  `IllegalClassLoadError` the first time anything references it -- for
  `InteractPacketAccessor` that was the first interact packet of any kind,
  i.e. Sable hitting a zombie, and it crashed the client's server. The
  self-test now casts a real packet to the accessor so an unlisted one fails
  at boot, not in play.

- `net.minecraft.util.Util`, not `net.minecraft.Util`, on 1.21.11.
- The decompiled 1.21.11 sources are the July `decompile_b70d…` jar in
  `~/.gradle/caches/neoformruntime`; the two August jars are 26.1 and 26.2.
- 26.x: `SavedDataType` id becomes an `Identifier` and the file moves to a
  namespaced folder; `EntityType.X` → `EntityTypes.X` on 26.2 only.
- ZombieMod re-genuses natural spawns and clears goals unrecoverably; Cast
  spawns with `EntitySpawnReason.COMMAND` so its roll never touches a body.
