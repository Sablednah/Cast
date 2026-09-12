package com.sablednah.cast.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.ParseResults;
import com.sablednah.cast.CastMod;
import com.sablednah.cast.api.Cast;
import com.sablednah.cast.api.Npc;
import com.sablednah.cast.core.NpcStore;
import com.sablednah.cast.npc.Brains;
import com.sablednah.cast.npc.HumanNpc;
import com.sablednah.cast.npc.Npcs;
import com.sablednah.cast.npc.Phantoms;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Headless checks on {@code ./gradlew runServer -Pselftest}. The brain
 * fixture is the load-bearing one: it asserts that the 20 brain-driven
 * classes of 21.11 are detected AND that goal mobs are not, so a version
 * that converts another mob to a Brain fails here rather than in a village.
 */
public final class SelfTest {

    private static final List<String> FAILURES = new ArrayList<>();
    private static int passed;

    /** Brain-driven in 21.11, from a grep of makeBrain overrides. Drift here is the point. */
    private static final List<String> BRAIN_MOBS = List.of(
            "allay", "armadillo", "axolotl", "breeze", "camel", "copper_golem", "creaking", "frog", "goat",
            "happy_ghast", "hoglin", "nautilus", "piglin", "piglin_brute", "sniffer", "tadpole", "villager",
            "warden", "zoglin", "zombie_nautilus");
    private static final List<String> GOAL_MOBS = List.of("zombie", "pig", "cow", "skeleton", "iron_golem", "wandering_trader");

    public static void run(MinecraftServer server) {
        FAILURES.clear();
        passed = 0;
        CastMod.LOGGER.info("=== Cast SelfTest ===");
        ServerLevel level = server.overworld();

        // --- the brain fixture, both directions ---
        for (String name : BRAIN_MOBS) {
            var type = BuiltInRegistries.ENTITY_TYPE.get(Identifier.parse("minecraft:" + name));
            if (type.isEmpty()) { CastMod.LOGGER.info("SelfTest: no entity type {} on this version (fixture entry skipped)", name); continue; }
            var e = type.get().value().create(level, EntitySpawnReason.COMMAND);
            check("brain-driven: " + name, e instanceof Mob m && Brains.isBrainDriven(m));
            if (e != null) e.discard();
        }
        for (String name : GOAL_MOBS) {
            var type = BuiltInRegistries.ENTITY_TYPE.get(Identifier.parse("minecraft:" + name));
            if (type.isEmpty()) continue;
            var e = type.get().value().create(level, EntitySpawnReason.COMMAND);
            check("goal-driven: " + name, e instanceof Mob m && !Brains.isBrainDriven(m));
            if (e != null) e.discard();
        }

        // --- the store and both bodies, through the API ---
        // The middle of the spawn chunk, so every actor placed within a few blocks shares one chunk:
        // on 26.2 a dev server with no player has no spawn chunks loaded, and a body two blocks
        // over a chunk edge is unloaded, silently never spawns, and the first orElseThrow on it
        // takes the server down. Force the neighbours too, for the walk-away checks.
        var spawnPos = level.getRespawnData().globalPos().pos();
        // On the actual surface: NPCs obey gravity now, and a spawn point a few blocks up would drop them mid-test.
        int hx = ((spawnPos.getX() >> 4) << 4) + 8, hz = ((spawnPos.getZ() >> 4) << 4) + 8;
        level.getChunk(hx >> 4, hz >> 4);
        int hy = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new net.minecraft.core.BlockPos(hx, 0, hz)).getY();
        Vec3 here = new Vec3(hx + 0.5, hy, hz + 0.5);
        int cx = ((int) Math.floor(here.x)) >> 4, cz = ((int) Math.floor(here.z)) >> 4;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setChunkForced(cx + dx, cz + dz, true);
        NpcStore store = NpcStore.get(server);
        int before = store.size();
        UUID human = Cast.spawnHuman(level, here, 0F, "Dr Okafor of the Long Name", Optional.empty(), List.of(Identifier.parse("cast:selftest")));
        UUID villager = Cast.spawnMob(level, here.add(2, 0, 0), 90F, Identifier.parse("minecraft:villager"), "&aCamp Trader", List.of());
        UUID zombie = Cast.spawnMob(level, here.add(-2, 0, 0), 90F, Identifier.parse("minecraft:zombie"), "Shambles", List.of());
        try {
            check("store holds three more", store.size() == before + 3);
            Optional<Npc> h = Cast.byId(server, human);
            check("human handle: loaded, is human", h.map(n -> n.loaded() && n.isHuman()).orElse(false));
            check("human handle: entity is a phantom player, not in a level's entity list",
                    h.flatMap(Npc::entity).map(e -> e instanceof HumanNpc && level.getEntity(e.getUUID()) == null).orElse(false));
            check("human profile name trimmed to 16", h.flatMap(Npc::entity).map(e -> ((HumanNpc) e).getGameProfile().name().length() <= 16).orElse(false));
            // A cached skin must reach the profile through the constructor (properties() is immutable).
            store.putSkin("selftestskin", new NpcStore.Skin(UUID.randomUUID(), "dGVzdA==", "c2ln", 0L));
            Npcs.setSkin(server, human, Optional.of("selftestskin"));
            check("cached skin lands on the phantom profile", Cast.byId(server, human).flatMap(Npc::entity)
                    .map(e -> ((HumanNpc) e).getGameProfile().properties().containsKey("textures")).orElse(false));
            Npcs.tick(server);
            check("human is not listed", h.flatMap(Npc::entity).map(e -> !((HumanNpc) e).allowsListing()).orElse(false));

            Optional<Npc> v = Cast.byId(server, villager);
            check("villager body exists", v.flatMap(Npc::entity).isPresent());
            check("villager body carries the public marker", v.flatMap(Npc::entity).map(e -> Cast.isNpc(e) && Cast.npcIdOf(e).map(villager::equals).orElse(false)).orElse(false));
            check("villager brain has no behaviours", v.flatMap(Npc::entity).map(e -> ((Mob) e).getBrain().getRunningBehaviors().isEmpty()).orElse(false));
            check("villager is invulnerable", v.flatMap(Npc::entity).map(e -> e.isInvulnerable()).orElse(false));
            Optional<Npc> z = Cast.byId(server, zombie);
            check("zombie body exists and is ours", z.flatMap(Npc::entity).map(Cast::isNpc).orElse(false));
            // Anchoring: a shoved body goes home; a possessed one is left where its wearer walks it.
            Mob zb = (Mob) z.flatMap(Npc::entity).orElse(null);
            if (zb == null) {
                check("anchored body snaps home after a shove (no zombie body to test)", false);
                check("unanchored body stays where it was walked (no zombie body to test)", false);
            } else {
            Vec3 home = zb.position();
            zb.snapTo(home.x + 3, home.y, home.z, 0F, 0F);
            Npcs.tick(server);
            check("anchored body snaps home after a shove (home " + home + ", now " + zb.position() + ", spec " + Cast.byId(server, zombie).map(Npc::pos).orElse(null) + ", here " + here + ")", zb.distanceToSqr(home) < 0.01);
            Cast.setAnchored(server, zombie, false);
            zb.snapTo(home.x + 3, home.y, home.z, 0F, 0F);
            Npcs.tick(server);
            check("unanchored body stays where it was walked", zb.distanceToSqr(home) > 8.0);
            Cast.setAnchored(server, zombie, true);
            Npcs.tick(server);
            check("re-anchoring adopts the new spot", zb.distanceToSqr(home) > 8.0
                    && Cast.byId(server, zombie).map(n -> n.pos().distanceToSqr(zb.position()) < 0.01).orElse(false));
            check("zombie has only our goals", z.flatMap(Npc::entity).map(e -> ((Mob) e).goalSelector.getAvailableGoals().size() == 2).orElse(false));
            // A reload gives a body its vanilla goals back; reown must take them away again.
            Mob reloaded = zb;
            reloaded.goalSelector.addGoal(5, new net.minecraft.world.entity.ai.goal.FloatGoal(reloaded)); // a vanilla goal, as a reload would give it
            Npcs.reown(level, reloaded);
            check("reown strips a rebuilt body back to our goals", reloaded.goalSelector.getAvailableGoals().size() == 2);
            Cast.setAnchored(server, zombie, false);
            Npcs.reown(level, reloaded);
            check("reown re-anchors a body whose mover is gone", Npcs.isAnchored(zombie));
            // Re-anchored in mid-air (a rooftop release): it keeps falling and the landing becomes home, no gag.
            {
                Vec3 ground = Cast.byId(server, zombie).map(Npc::pos).orElse(home);
                Cast.setAnchored(server, zombie, false); // possessed: walked off a roof...
                zb.snapTo(ground.x, ground.y + 4, ground.z, 0F, 0F);
                zb.setOnGround(false);
                Cast.setAnchored(server, zombie, true); // ...and released mid-air: anchors where it is, four blocks up
                Npcs.tick(server);
                check("airborne re-anchor: not held up, left to fall", !zb.isNoGravity() && Math.abs(zb.getY() - (ground.y + 4)) < 0.01);
                zb.snapTo(ground.x, ground.y, ground.z, 0F, 0F); // vanilla physics lands it
                zb.setOnGround(true);
                Npcs.tick(server);
                check("airborne re-anchor: the landing became home and it is held there", zb.isNoGravity()
                        && Cast.byId(server, zombie).map(n -> Math.abs(n.pos().y - ground.y) < 0.01).orElse(false));
            }
            }

            // --- packets to a viewer: must not throw ---
            FakePlayer viewer = new FakePlayer(level, new GameProfile(UUID.nameUUIDFromBytes("cast:viewer".getBytes()), "CastViewer"));
            viewer.snapTo(here.x, here.y, here.z - 3, 0F, 0F); // three blocks back, facing +z at the phantom
            HumanNpc phantom = (HumanNpc) h.flatMap(Npc::entity).orElseThrow();
            boolean ok = true;
            try { Phantoms.show(viewer, phantom); Phantoms.hide(viewer, phantom); } catch (RuntimeException e) { ok = false; CastMod.LOGGER.error("packets", e); }
            check("phantom show/hide packets build", ok);
            // The player-info entry is what puts the phantom's name in /tp suggestions; it is withdrawn after it appears.
            // The viewer is not in the player list, so the withdrawal is exercised through the schedule, not the tick.
            Phantoms.show(viewer, phantom);
            check("unlist: a shown phantom's tab entry is scheduled to go", Phantoms.pendingUnlist() >= 1);
            Phantoms.tickUnlist(server); // the fake viewer is not online: entry dropped, nothing sent, nothing thrown
            check("unlist: a viewer who is gone is forgotten", Phantoms.pendingUnlist() == 0);
            Phantoms.hide(viewer, phantom);

            // --- roles: dispatch, suppression, unknown role ---
            boolean[] fired = {false};
            Cast.registerRole(Identifier.parse("cast:selftest"), (p, npc, hand) -> { fired[0] = true; return true; });
            check("role dispatch fires", Npcs.interact(viewer, human, InteractionHand.MAIN_HAND) && fired[0]);
            fired[0] = false;
            Cast.setRolesEnabled(viewer, false);
            check("roles suppressed for a player", !Npcs.interact(viewer, human, InteractionHand.MAIN_HAND) && !fired[0]);
            Cast.setRolesEnabled(viewer, true);
            check("a mob with no roles handles nothing", !Npcs.interact(viewer, villager, InteractionHand.MAIN_HAND));

            // --- lookups and drive ---
            check("npcAt finds the phantom in front of the viewer (human at " + Cast.byId(server, human).map(Npc::pos).orElse(null) + ", viewer " + viewer.position() + ", block under here " + level.getBlockState(net.minecraft.core.BlockPos.containing(here).below()) + ")", Cast.npcAt(viewer, 6).map(n -> n.id().equals(human)).orElse(false));
            check("npcHitAt reports a distance under 4", Cast.npcHitAt(viewer, 6).map(hit -> hit.distance() > 1 && hit.distance() < 4).orElse(false));
            check("human handle can be possessed while loaded", h.map(Npc::canPossess).orElse(false));
            check("phantom.level() is the real level", h.flatMap(Npc::entity).map(e -> e.level() == level).orElse(false));

            // Pinning: a far viewer keeps the phantom; the plain range logic would have hidden it.
            viewer.snapTo(here.x + 500, here.y, here.z, 0F, 0F);
            Cast.pinViewer(viewer, human);
            check("pinned viewer is shown out of range", Phantoms.isViewing(viewer, human));
            Npcs.tick(server);
            check("pinned viewer survives the tick", Phantoms.isViewing(viewer, human));
            Cast.unpinViewer(viewer, human);
            Npcs.tick(server);
            check("unpinned viewer is hidden out of range", !Phantoms.isViewing(viewer, human));
            viewer.snapTo(here.x, here.y, here.z - 3, 0F, 0F);

            int[] removedEvents = {0};
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((com.sablednah.cast.api.NpcRemovedEvent ev) -> {
                if (ev.npcId().equals(human)) removedEvents[0]++;
            });
            Cast.rename(server, human, "Renamed");
            check("rebody fires NpcRemovedEvent(REBODY)", removedEvents[0] >= 1);
            Cast.setAnchored(server, human, false);
            Cast.rename(server, human, "Renamed Again");
            check("a rebuilt body starts anchored", Npcs.isAnchored(human));
            Cast.drive(server, human, here.add(0, 0, 3), 45F, 0F);
            check("drive moves the spec", Cast.byId(server, human).map(n -> n.pos().z > here.z + 2).orElse(false));
            check("say with nobody near returns 0", Cast.say(server, human, "hello?", 8.0) == 0);
            check("rename lands", Cast.byId(server, human).map(n -> n.name().equals("Renamed Again")).orElse(false));
            // Placement: a spawn handed the ground block's own Y is raised out of it; a slab is sat on, not hovered over.
            {
                var solid = net.minecraft.core.BlockPos.containing(here.x + 4, here.y - 1, here.z);
                level.setBlockAndUpdate(solid, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                UUID buried = Cast.spawnMob(level, new Vec3(here.x + 4.5, here.y - 1, here.z + 0.5), 0F, Identifier.parse("minecraft:pig"), "Buried", List.of());
                check("placement: a body handed a Y inside the ground is raised onto it",
                        Cast.byId(server, buried).map(n -> n.pos().y >= here.y - 0.01).orElse(false));
                Cast.remove(server, buried);
                var slabAt = net.minecraft.core.BlockPos.containing(here.x - 4, here.y, here.z);
                level.setBlockAndUpdate(slabAt.below(), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(slabAt, net.minecraft.world.level.block.Blocks.STONE_SLAB.defaultBlockState());
                Vec3 onSlab = com.sablednah.cast.npc.Gravity.landing(level, new Vec3(slabAt.getX() + 0.5, slabAt.getY() + 1, slabAt.getZ() + 0.5));
                check("placement: feet land on a slab's top, not the block above it (" + onSlab.y + ")", Math.abs(onSlab.y - (slabAt.getY() + 0.5)) < 0.01);
                level.setBlockAndUpdate(slabAt, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
            // Gravity: a phantom over air drops to the ground and its anchor follows; defying it, it hangs.
            {
                Vec3 gBefore = Cast.byId(server, human).map(Npc::pos).orElse(here);
                var under = net.minecraft.core.BlockPos.containing(gBefore.x, gBefore.y - 1, gBefore.z);
                var was = level.getBlockState(under);
                level.setBlockAndUpdate(under, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(under.below(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                Npcs.tick(server);
                check("coyote: first it looks down, and does not fall", Cast.byId(server, human).map(n -> n.pos().y == gBefore.y).orElse(false)
                        && Cast.byId(server, human).flatMap(Npc::entity).map(e -> e.getXRot() > 45F).orElse(false));
                Npcs.tick(server);
                check("coyote: then it looks back up, and still does not fall", Cast.byId(server, human).map(n -> n.pos().y == gBefore.y).orElse(false)
                        && Cast.byId(server, human).flatMap(Npc::entity).map(e -> e.getXRot() < 0F).orElse(false));
                Npcs.tick(server);
                Vec3 after = Cast.byId(server, human).map(Npc::pos).orElse(gBefore);
                check("gravity: THEN it drops (" + gBefore.y + " -> " + after.y + ")", after.y < gBefore.y - 0.5);
                check("gravity: the anchor followed it down", Cast.byId(server, human).flatMap(Npc::entity).map(e -> Math.abs(e.getY() - after.y) < 0.01).orElse(false));
                Cast.setDefyGravity(server, human, true);
                var under2 = net.minecraft.core.BlockPos.containing(after.x, after.y - 1, after.z);
                var was2 = level.getBlockState(under2);
                level.setBlockAndUpdate(under2, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                Npcs.tick(server); Npcs.tick(server); Npcs.tick(server);
                check("gravity: defied, it hangs there", Cast.byId(server, human).map(n -> Math.abs(n.pos().y - after.y) < 0.01).orElse(false));
                level.setBlockAndUpdate(under2, was2);
                Cast.setDefyGravity(server, human, false);
                level.setBlockAndUpdate(under, was);
                level.setBlockAndUpdate(under.below(), was);
            }
            // Dressing: a /give string in a slot, on a phantom and on a body; a bad slot or item is refused.
            check("equip: a potion in the phantom's hand", Cast.equip(server, human, "mainhand", "minecraft:potion[potion_contents={potion:'minecraft:healing'}]")
                    && Cast.byId(server, human).flatMap(Npc::entity).map(e -> ((net.minecraft.world.entity.LivingEntity) e).getMainHandItem().is(net.minecraft.world.item.Items.POTION)).orElse(false)); // fresh handle: rename rebodied the phantom
            check("equip: a helmet on the villager", Cast.equip(server, villager, "head", "minecraft:iron_helmet")
                    && Cast.byId(server, villager).flatMap(Npc::entity).map(e -> ((net.minecraft.world.entity.LivingEntity) e).getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(net.minecraft.world.item.Items.IRON_HELMET)).orElse(false));
            check("equip: remembered in the store", Cast.equipment(server, human).getOrDefault("mainhand", "").startsWith("minecraft:potion"));
            check("equip: a wrong slot is refused", !Cast.equip(server, human, "hat", "minecraft:iron_helmet"));
            check("equip: a wrong item is refused", !Cast.equip(server, human, "offhand", "minecraft:no_such_thing"));
            check("equip: blank clears", Cast.equip(server, human, "mainhand", "") && Cast.byId(server, human).flatMap(Npc::entity).map(e -> ((net.minecraft.world.entity.LivingEntity) e).getMainHandItem().isEmpty()).orElse(false));
            viewer.discard();
        } finally {
            check("remove is idempotent (first)", Cast.remove(server, human));
            check("remove is idempotent (second)", !Cast.remove(server, human));
            Cast.remove(server, villager);
            Cast.remove(server, zombie);
            check("store back to where it was", store.size() == before);
            Npcs.tick(server);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setChunkForced(cx + dx, cz + dz, false);
        }

        // 26.2: the interact packet is a record, so no accessor mixin is needed; the 1.21.11 line
        // paid for an unlisted one with a crash on every hit. Build one the way the client does and
        // read it back, so a future shape change is caught here rather than by a player.
        try {
            var probe = new FakePlayer(level, new GameProfile(UUID.nameUUIDFromBytes("cast:probe".getBytes()), "CastProbe"));
            var packet = new net.minecraft.network.protocol.game.ServerboundInteractPacket(probe.getId(),
                    net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.phys.Vec3.ZERO, false);
            check("interact packet carries the entity id", packet.entityId() == probe.getId());
            probe.discard();
        } catch (Throwable t) {
            check("interact packet carries the entity id (" + t + ")", false);
        }

        check("lang catalogue", Lang.catalogueSize() > 15 && !Feedback.colored("&6x").getString().contains("§"));
        check("build stamp: no resource at all reads unknown, and does not throw",
                com.sablednah.cast.BuildInfo.parse(null).commit().equals("unknown") && com.sablednah.cast.BuildInfo.describe(com.sablednah.cast.BuildInfo.parse(null)).contains("unknown"));
        check("build stamp: a malformed resource reads unknown, and does not throw",
                com.sablednah.cast.BuildInfo.parse(new java.io.ByteArrayInputStream("\u0000garbage=\\u00zz\n=\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))).version().equals("unknown"));
        // Mixed corruption in both orderings. The VALID-FIRST case is the one with teeth: those lines are
        // already in the map when load throws, so a reader that swallows the throw and reads what landed
        // reports a real-looking commit (LegendQuest ran that broken reader to prove which case catches it).
        // Bad-first throws on line one with nothing populated, so it passes either way; kept for the record.
        check("build stamp: a valid line then a bad escape degrades to unknown, all of it",
                com.sablednah.cast.BuildInfo.parse(new java.io.ByteArrayInputStream("commit=deadbeef\nbranch=main\ntime=\\u00zz\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))).commit().equals("unknown"));
        check("build stamp: a bad escape then a valid line degrades to unknown, all of it",
                com.sablednah.cast.BuildInfo.parse(new java.io.ByteArrayInputStream("time=\\u00zz\ncommit=deadbeef\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))).commit().equals("unknown"));
        check("build stamp: a stream that throws mid-read degrades to unknown", com.sablednah.cast.BuildInfo.parse(new java.io.InputStream() {
            int n = 0;
            @Override public int read() throws java.io.IOException { if (n++ > 12) throw new java.io.IOException("cut"); return "commit=abcd1234\n".charAt(n - 1); }
        }).commit().equals("unknown"));
        check("build stamp: a good stamp reads whole", com.sablednah.cast.BuildInfo.describe(com.sablednah.cast.BuildInfo.parse(new java.io.ByteArrayInputStream("commit=abcd1234\nbranch=b\ntime=t\nversion=v\n".getBytes()))).equals("v (build abcd1234 on b, t)"));
        check("build stamp: the version carries the Minecraft line, like the filename", com.sablednah.cast.BuildInfo.version().contains("+mc"));
        check("build stamp: a dev run reads its commit (" + com.sablednah.cast.BuildInfo.describe() + ")",
                !"unknown".equals(com.sablednah.cast.BuildInfo.commit()) && !"unknown".equals(com.sablednah.cast.BuildInfo.version()));
        CommandSourceStack source = server.createCommandSourceStack();
        command(server, source, "cast list", true);
        command(server, source, "cast status", true);
        command(server, source, "cast spawn human Bob", false); // console has no hands
        command(server, source, "cast sideways", false);

        CastMod.LOGGER.info("=== Cast SelfTest: {} PASSED, {} FAILED ===", passed, FAILURES.size());
        for (String f : FAILURES) CastMod.LOGGER.error("  FAILED: {}", f);
    }

    private static void command(MinecraftServer server, CommandSourceStack source, String cmd, boolean expectOk) {
        var dispatcher = server.getCommands().getDispatcher();
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(cmd, source);
        boolean parses = parsed.getExceptions().isEmpty() && !parsed.getReader().canRead()
                && parsed.getContext().getLastChild().getCommand() != null;
        if (!expectOk) {
            boolean refused = !parses;
            if (parses) { try { dispatcher.execute(parsed); } catch (Exception e) { refused = true; } }
            check("'" + cmd + "' is refused", refused);
            return;
        }
        if (!parses) { check("'" + cmd + "' parses", false); return; }
        try { dispatcher.execute(parsed); check("'" + cmd + "' executes", true); }
        catch (Exception e) { check("'" + cmd + "' executes (" + e.getMessage() + ")", false); }
    }

    private static void check(String what, boolean ok) {
        if (ok) passed++; else FAILURES.add(what);
    }

    private SelfTest() {}
}
