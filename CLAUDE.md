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
./deploy.sh                        # -> every CurseForge instance on this line that has Cast; the EXACT
                                   #    versioned jar (a glob picked 0.1.0 over 1.0.0 next door); refuses a
                                   #    running instance (the guard now stops at " --": --gameDir is unquoted)
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
core/    NpcSpec (incl. scale, clamped 0.0625-16), NpcKind, NpcStore (SavedData: specs + cached skins)
npc/     HumanNpc, Phantoms (viewer tracking + packets), Bodies (mob bodies), Brains, Skins (account fetches +
         the cast:skin datapack registry: "<ns>:<name>" is shipped, a bare name is an account), Scale
         (minecraft:scale + refreshDimensions), Exposure, Gravity, LureGoal, Motion, Equipment, Npcs (the manager)
CastRegistries  the cast:skin datapack registry (data/<ns>/cast/skin/<name>.json = MineSkin value+signature)
api/     Cast (facade), Npc (handle), Role, NpcRemovedEvent   <- what other mods import
neoforge/ CastEvents, CastCommands, CastPermissions, Lang, Feedback, SelfTest
mixin/   ServerGamePacketListenerImplMixin, InteractPacketAccessor
```

## Versions

Branch per Minecraft version: `main` = 1.21.11 (Java 21), `mc26.1` = 26.1.2 on
NeoForge 26.1.2.95 and `mc26.2` = 26.2 on NeoForge 26.2.0.72 (both Java 25,
`/home/sable/.gradle/jdks/eclipse_adoptium-25-amd64-linux.2`). 26.1 carries the
same drift as 26.2 below EXCEPT the entity-type constants and `ChatFormatting`,
which it still has; its run dir is `run-mc26.1.2`.
Docs live on `main` only; features are cherry-picked forward. Each branch
differs in `gradle.properties` (four lines), `build.gradle` (plugin 2.0.144,
toolchain 25, `gameDirectory = run-mc26.2` so a 1.21.11 world is never
upgraded in place), `cast.mixins.json` (`JAVA_25`) and the 26.2 drift:

- `ServerboundInteractPacket` is a record carrying hand **and** location; the
  attack is its own `ServerboundAttackPacket`. No accessor mixin on 26.2.
- `SavedDataType` takes an `Identifier` (`cast:npcs`), and the file moves to a
  namespaced folder. No shipped world has crossed lines yet.
- `sendSystemMessage(text, overlay)` for `displayClientMessage`; `EntityTypes.*`
  for `EntityType.*`; `entityTags()` for `getTags()`; `ChatFormatting` is a bare
  enum, so `Feedback` owns the five formatting codes.
- `ItemParser.parse` returns an `ItemInput` record on 26.x (`ItemResult` on 1.21.11); same shape,
  different name (`Equipment.parse`).
- A 26.2 dev server with no player loads **no spawn chunks**; the self-test
  forces a 3x3 around the spawn chunk and keeps every actor inside the middle one.

The jars sit in `build/libs` side by side (`cast-<ver>+mc<mc>.jar`); Chronicler
picks by the `+mc` suffix.

**`mc26.3`** (2026-09-29) is 26.3 on **NeoForge 26.3.0.58-beta** (since 2026-10-08; .33 before),
MDG 2.0.147, Java 25, run dir `run-mc26.3`, range `[26.3.0.58-beta,26.4)` -- floored at the tested
build, and the bound must say `-beta` (Maven orders `26.3.0.37-beta` before `26.3.0.37`). FML 12.0.8
(NeoForge .37-beta+) renamed `ModConfig.Type` `COMMON` -> `LOCAL` (`SERVER` -> `SYNCED`); a jar
still saying `COMMON` dies there with `NoSuchFieldError` (1.2.1 capped below .37-beta for a week while
Sable waited). **`LOCAL`'s default file is `cast-local.toml`, so `CastMod` registers with
`"cast-common.toml"` explicitly** -- otherwise every existing server silently starts from defaults
(Standards/LegendQuest's trick; Chronicler does the same). On top of the 26.2 drift:
`DataPackRegistryEvent.NewRegistry`/`dataPackRegistry` -> `NewDatapackRegistryEvent`/`worldRegistry`
(`CastRegistries`); `setPermanentlyInvulnerable`/`isPermanentlyInvulnerable` for the old
`setInvulnerable` pair (26.3's `isInvulnerable()` is also true during hurt i-frames, so the old check
read a hit as "invulnerable"); `swing(hand, SwingAnimation.DEFAULT, bool)` and
`ClientboundSwingAnimationPacket` for the phantom swing; `ClientboundMoveEntityPacket.PosRot` takes
a `VecDelta.Linear`; authlib's `ProfileResult` moved to `com.mojang.authlib.services`. The first
NeoForge .58 setup takes ~19 min per repo on /mnt/d; run 26.x Gradle with `--no-daemon`.

## The build stamp

Family format (agreed 2026-09-10; LegendQuest holds the reference): `build.gradle` reads the
short commit (`-dirty` when uncommitted), branch and the COMMIT's UTC time (never the wall
clock, which would keep `jar` from ever being up to date), writes them to the manifest
(`Build-Commit` / `Build-Branch` / `Build-Time`) and to `/cast/build.properties` (read by
`BuildInfo`; a dev run has no jar). The startup line and `/cast status` print it. Unknown when
absent, never a failure. Same filename, different bytes, is the trap it answers.

## Releasing

`mod_version` in `gradle.properties` (the same on every branch), `CHANGELOG.md` with a dated
heading, the README status line, then commit main, cherry-pick the version commit to `mc26.1`,
`mc26.2` and `mc26.3` (docs conflicts: `--ours`), build a jar per branch with the right JDK
(`--no-daemon` on 26.x), read `Build-Commit` from each manifest (equal to the branch head, no
`-dirty`), tag `v<version>` on main, push every branch and the tag, and `gh release create` with
all four jars attached. Publishing the GitHub release fires `.github/workflows/curseforge.yml`,
which uploads every attached `cast-*.jar` with the Minecraft version read from the `+mc` filename
tag to CurseForge project 1690287 (live; a 200 is acceptance, moderation follows). Then `./deploy.sh`
on each branch; it skips the **LegendQuest ZARP** instance (`.sablecraft-no-deploy`, which stays) --
Sable allows hand-copied jars there until that pack rolls out, after a running check. When
Chronicler releases the same day, **Cast goes first**: Chronicler's 26.3 file needs a Cast 26.3 file
on CurseForge to resolve its required dependency. A 26.3-only fix is a 26.3-only point release
(version bumped on every branch, one jar attached). Modrinth is dropped (2026-09-29: it
refused every one of Sable's projects as AI content); CurseForge is the one store.
Never handle the tokens. `CURSEFORGE.md` is the store page; `docs/` holds the artwork
and screenshots. 1.0.0 shipped 2026-09-12; 1.2.2 (2026-10-08) is current on all four lines.

**A CurseForge project's file list is its identity to every other mod.** The app resolves a
required dependency to the dependency project's newest APPROVED file for that Minecraft version
and does not care what it is named, so a companion jar uploaded beside the mod is what dependents
install -- most likely right after a release, while the mod's own file is still in moderation.
LegendQuest shipped its example pack to StoryTeller users that way (2026-09-15). The workflow
uploads `cast-*.jar` only; attach anything else to the GitHub release alone.

## Known traps

- **Resolving a cherry-pick conflict with `git add -A` resurrects files the commit deleted.**
  The docs-only CLAUDE.md conflict on a version branch was resolved that way and `Proxies.java`,
  deleted on main, came back on both 26.x branches as an orphan nothing called. After
  `cherry-pick --continue`, `git grep` for the name of the thing the commit removed.

- **"Stuck until I jump" means a player is inside terrain, never that something holds them**
  (LegendQuest measured it: feet a tenth below a surface sink a whole block and move 0.2 in
  two seconds of W -- "a 1x1 pit on the surface"). Cast's answer is `Gravity.unbury` on every
  spawn: feet inside a block are raised before the position is stored. Feet only -- testing the
  whole height lifted the self-test camp into a tree canopy.
- **Armour stands block nothing in 1.21.11.** `canBeCollidedWith` is `Entity`'s default (false)
  and `ArmorStand` never overrides it; only Shulker, HappyGhast and boats are ever solid to another
  entity. The "solid proxy" Cast 1.0.0 stood in every phantom's space did nothing and is gone.

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
- **A human phantom has no ordinary entity tracker, so no vanilla broadcast
  method (`swing`, damage flash, particles tied to an entity event) ever
  reaches a client on its own** -- it is never added to a level. Every piece
  of a phantom's visible state is instead a packet Cast sends by hand to a
  tracked viewer list (`Phantoms.VIEWERS`): position, rotation, equipment,
  and now `swing` (`Phantoms.broadcastSwing`, `ClientboundAnimatePacket`).
  Reaching for a plain `LivingEntity` method on a phantom and expecting a
  client to see it is the trap; a mob body has no such problem (it is a real
  tracked entity, and `Cast.swing`'s mob-body branch is a bare vanilla call).
- **Anything a client must DRAW on a phantom has to be a synced, NON-DEFAULT entity-data value.**
  `Phantoms.show` sends `getNonDefaultValues()`; a server-side override of a getter
  (`isModelPartShown`) convinces nobody but the server. Phantoms drew with no hat, jacket or sleeve
  layer for a month (ZARP, with the shipped skins: "Sarge is shaved") until `HumanNpc`'s constructor
  set `Avatar.DATA_PLAYER_MODE_CUSTOMISATION` to every `PlayerModelPart` mask (same field on all
  four lines). Attributes likewise: a phantom has no tracker, so `show` sends
  `ClientboundUpdateAttributesPacket` (scale) by hand.
- **A phantom is never ticked the vanilla way, so a `minecraft:scale` change does not resize its
  hitbox or eye height** -- `Scale.apply` calls `refreshDimensions()` itself (the self-test caught a
  0.65 Kit standing in a 1.8 box).
- **Cast's working tree is normally left on `mc26.2`** (the ZARP instance's line). Check the branch
  before editing: 1.1.1's edits landed on mc26.2 first and were stashed across to `main`.
- `/cast skin`, `/cast spawn human`'s skin and `/cast say` are `greedyString()`: `word()` refused the
  colon in a shipped skin name (`zarp:okafor`).
- **A new API method is consumed by reflection next door until a floor moves.** Chronicler's
  `CastGivers` probes `setScale` so an older Cast logs once instead of refusing to start; its Cast
  floor is still `[1.1.0,)`. Raise a consumer's floor only when a seam needs the method unconditionally.

## Artwork

Artwork lives in `docs/`: `wordmark-850.png` (CurseForge caps description images at 850 wide; the store page and README use it), `wordmark.png` (full size), `icon.png` (1254 square), `icon-512.png` and `icon-256.png`.
