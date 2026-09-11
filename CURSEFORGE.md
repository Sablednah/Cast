![Cast](https://raw.githubusercontent.com/Sablednah/Cast/main/docs/wordmark-850.png)

# Cast — people in the world that a vanilla client can see

**NPCs for other mods to give roles to.** A person with a name, a real skin, a coat and a lantern,
standing where you put them, turning to look at you, and doing whatever the mod that placed them
says when you right-click. Or a villager, a cow, a zombie, owned from the moment it spawns so it
never wanders, trades, or bites. Cast makes them; the mods you install decide what they are for.

**Your players do not need to install anything.** A human NPC is drawn by packets on every client
that can see it, skin and all, and behaves like a player standing very still. A creature NPC is the
creature it looks like, minus its own mind. Nothing to download, no resource pack.

---

![Sarge: a phantom with a helmet, chainmail and a crossbow, drawn on a vanilla client](https://raw.githubusercontent.com/Sablednah/Cast/main/docs/screenshots/sarge.png)

## Two kinds of body

![A human phantom and a villager body side by side, the camp behind them](https://raw.githubusercontent.com/Sablednah/Cast/main/docs/screenshots/two-bodies.png)


- **Human** — a player-model NPC with any real account's skin. It is a *phantom*: sent to the
  players who can see it, never added to the world. So it is not a player: it does not count
  towards sleeping, does not anchor mob spawning, does not keep chunks loaded, is not in the tab
  list, and does not turn up in `/tp` suggestions. An invisible stand-in gives it a body, so mobs
  walk round it rather than through it.
- **Mob** — any vanilla creature. Its goals are cleared and replaced with "look at whoever is near";
  the brain-driven ones (villagers, piglins, the rest) have their behaviours removed every second,
  which is the only way to park them. It is invulnerable, named, and never opens its own screen.

Both stand where they were placed. Shove one and it steps back. Mine the block from under one and it
looks down, looks back up at you, and *then* falls, landing wherever the ground is now — unless you
told it to defy gravity, which is the option's actual name.

## Dressing

![A villager body in a golden helmet and an iron chestplate, holding a lantern: three /cast equip commands](https://raw.githubusercontent.com/Sablednah/Cast/main/docs/screenshots/trader-dressed.png)


`/cast equip head minecraft:iron_helmet`, `/cast equip mainhand "minecraft:potion[potion_contents={potion:'minecraft:healing'}]"`
— any item as `/give` takes it, in any slot, on either kind of body, remembered across restarts.
Blank clears the slot.

![Dr Okafor in her coat with a potion, placed by Chronicler](https://raw.githubusercontent.com/Sablednah/Cast/main/docs/screenshots/okafor-dressed.png)

## For the mods that use it

Cast has no quests, no dialogue and no scenes of its own. It has an API:

- **[Chronicler](https://www.curseforge.com/minecraft/mc-mods/chronicler)** places quest givers
  with it — a camp of five appears near spawn on any seed, each carrying a storyline.
- **LegendQuest StoryTeller** possesses them: a game master walks an NPC into a scene, speaks as
  them, and lets go; Cast re-anchors the body where it was left.
- Your mod: one guarded class that imports `com.sablednah.cast.api`, `Cast.registerRole(id, handler)`
  to be called when a right-click lands on an NPC carrying your role, and `spawnHuman` / `spawnMob`
  / `say` / `equip` / `remove` / `drive` for everything else. An NPC can carry several roles from
  several mods at once.

## Commands (`cast.admin`, or op)

`/cast spawn human "Dr Okafor" Sablednah` puts a human with that account's skin where you are
looking; `/cast spawn mob minecraft:villager "Camp Trader"` a creature. Look at one and
`/cast name`, `skin`, `equip`, `look`, `here`, `say`, `defygravity`, `role add|remove`, `remove`.
`/cast list` and `/cast status` say what exists and which build this is.

## Requirements

| Minecraft | NeoForge | Java |
|---|---|---|
| 1.21.11 | 21.11.42+ | 21 |
| 26.1.2 | 26.1.2.95+ | 25 |
| 26.2 | 26.2.0.72+ | 25 |

There is **a jar per Minecraft version**, named for the one it was built against — take the one
that matches your server.

**Install on the server. That is all.** Cast depends on nothing, and nothing depends on your players.

## Credits and licence

Cast is licensed under **MIT**, by **Sablednah**. Source and API docs:
**[github.com/Sablednah/Cast](https://github.com/Sablednah/Cast)**.
