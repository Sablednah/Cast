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
            // The outer skin layer (beards, glasses, hoods) is drawn by the CLIENT from a synced byte; it must be
            // non-default, or show()'s entity-data packet leaves it out and every viewer sees all layers off.
            check("human: every skin layer is on, in the data a viewer is sent", h.flatMap(Npc::entity).map(e -> {
                var values = ((HumanNpc) e).getEntityData().getNonDefaultValues();
                return values != null && values.stream().anyMatch(dv -> dv.id() == HumanNpc.skinLayersData().id()
                        && Byte.valueOf(HumanNpc.allLayers()).equals(dv.value()));
            }).orElse(false));
            check("human: ...and the model agrees, hat and all", h.flatMap(Npc::entity).map(e ->
                    java.util.Arrays.stream(net.minecraft.world.entity.player.PlayerModelPart.values()).allMatch(((HumanNpc) e)::isModelPartShown)).orElse(false));
            check("human profile name trimmed to 16", h.flatMap(Npc::entity).map(e -> ((HumanNpc) e).getGameProfile().name().length() <= 16).orElse(false));
            // A cached skin must reach the profile through the constructor (properties() is immutable).
            store.putSkin("selftestskin", new NpcStore.Skin(UUID.randomUUID(), "dGVzdA==", "c2ln", 0L));
            Npcs.setSkin(server, human, Optional.of("selftestskin"));
            check("cached skin lands on the phantom profile", Cast.byId(server, human).flatMap(Npc::entity)
                    .map(e -> ((HumanNpc) e).getGameProfile().properties().containsKey("textures")).orElse(false));
            // Shipped skins: "<ns>:<name>" from data/<ns>/cast/skin/<name>.json or Cast.registerSkin -- no account, no fetch.
            var skinJson = com.google.gson.JsonParser.parseString("{\"value\": \"dGVzdDI=\", \"signature\": \"c2lnMg==\"}");
            check("shipped skin: the file format parses", com.sablednah.cast.npc.Skins.DataSkin.CODEC
                    .parse(com.mojang.serialization.JsonOps.INSTANCE, skinJson).result().map(d -> d.value().equals("dGVzdDI=")).orElse(false));
            check("shipped skin: a file with no signature is refused", com.sablednah.cast.npc.Skins.DataSkin.CODEC
                    .parse(com.mojang.serialization.JsonOps.INSTANCE, com.google.gson.JsonParser.parseString("{\"value\": \"eA==\"}")).error().isPresent());
            Cast.registerSkin(Identifier.parse("selftest:doctor"), "dGVzdDI=", "c2lnMg==");
            Npcs.setSkin(server, human, Optional.of("selftest:doctor"));
            check("shipped skin: a namespaced skin lands on the phantom profile", Cast.byId(server, human).flatMap(Npc::entity)
                    .map(e -> ((HumanNpc) e).getGameProfile().properties().get("textures").stream()
                            .anyMatch(pr -> pr.value().equals("dGVzdDI=") && "c2lnMg==".equals(pr.signature()))).orElse(false));
            Npcs.setSkin(server, human, Optional.of("selftest:nobody"));
            check("shipped skin: one nobody shipped leaves the default skin", Cast.byId(server, human).flatMap(Npc::entity)
                    .map(e -> !((HumanNpc) e).getGameProfile().properties().containsKey("textures")).orElse(false));
            check("shipped skin: ...and is never fetched or cached as an account", store.skin("selftest:nobody").isEmpty());
            // The real datapack path, when the dev world carries the test pack (run/world/datapacks/cast-skin-selftest).
            if (server.getPackRepository().getSelectedIds().contains("file/cast-skin-selftest")) {
                check("shipped skin: a datapack's data/selftest/cast/skin/packdoctor.json is found by id",
                        com.sablednah.cast.npc.Skins.resolve(server, store, "selftest:packdoctor").map(sk -> sk.value().equals("cGFja3NraW4=")).orElse(false));
            } else {
                CastMod.LOGGER.info("Cast SelfTest: no cast-skin-selftest datapack in this world -- the datapack skin path is not exercised");
            }
            check("shipped skin: a bare name is still an account", !com.sablednah.cast.npc.Skins.isShipped("selftestskin")
                    && com.sablednah.cast.npc.Skins.resolve(server, store, "selftestskin").isPresent());
            // Scale: vanilla's minecraft:scale on the body, kept on the spec and re-applied to every new body.
            {
                var SCALE = net.minecraft.world.entity.ai.attributes.Attributes.SCALE;
                double plainHeight = Cast.byId(server, human).flatMap(Npc::entity).map(e -> e.getBbHeight()).orElse(0F);
                check("scale: a human is resized", Cast.setScale(server, human, 1.2D) && Cast.byId(server, human).flatMap(Npc::entity)
                        .map(e -> Math.abs(((HumanNpc) e).getAttribute(SCALE).getBaseValue() - 1.2D) < 1e-9).orElse(false));
                check("scale: ...and its hitbox with it (what a click reaches, where it looks from)", Cast.byId(server, human).flatMap(Npc::entity)
                        .map(e -> e.getBbHeight() > plainHeight * 1.15F).orElse(false));
                check("scale: kept on the spec", store.get(human).map(sp -> sp.scale() == 1.2D).orElse(false));
                check("scale: a mob body is resized in place", Cast.setScale(server, zombie, 0.85D) && Cast.byId(server, zombie).flatMap(Npc::entity)
                        .map(e -> Math.abs(((Mob) e).getAttribute(SCALE).getBaseValue() - 0.85D) < 1e-9).orElse(false));
                check("scale: clamped to vanilla's range", store.get(human).map(sp -> sp.withScale(100D).scale() == 16D && sp.withScale(0D).scale() == 0.0625D).orElse(false));
                check("scale: an NPC that does not exist is refused", !Cast.setScale(server, UUID.randomUUID(), 1.1D));
                check("scale: a spec saved before scale existed reads as 1", store.get(human).map(sp -> {
                    var json = com.sablednah.cast.core.NpcSpec.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, sp).getOrThrow().getAsJsonObject();
                    json.remove("scale");
                    return com.sablednah.cast.core.NpcSpec.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, json).result().map(o -> o.scale() == 1D).orElse(false);
                }).orElse(false));
                var dispatcher = server.getCommands().getDispatcher();
                var ok = dispatcher.parse("cast scale 1.2", server.createCommandSourceStack());
                check("scale: '/cast scale 1.2' parses", ok.getExceptions().isEmpty() && !ok.getReader().canRead() && ok.getContext().getLastChild().getCommand() != null);
                var big = dispatcher.parse("cast scale 40", server.createCommandSourceStack());
                check("scale: '/cast scale 40' is refused", !big.getExceptions().isEmpty() || big.getContext().getLastChild().getCommand() == null || big.getReader().canRead());
                Cast.setScale(server, human, 1D);
                Cast.setScale(server, zombie, 1D);
            }
            var skinParse = server.getCommands().getDispatcher().parse("cast skin zarp:okafor", server.createCommandSourceStack());
            check("shipped skin: '/cast skin zarp:okafor' parses (not word())", skinParse.getExceptions().isEmpty() && !skinParse.getReader().canRead()
                    && skinParse.getContext().getLastChild().getCommand() != null);
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
            // Swing: a scripted gesture -- a mob body under vanilla's own call, a human phantom through
            // Phantoms' packet push, since it has no ordinary entity tracker to broadcast one on its own.
            check("swing: a mob body swings", Cast.swing(server, villager, net.minecraft.world.InteractionHand.MAIN_HAND));
            check("swing: a human phantom swings (no viewer connected to see it, but it must not throw)",
                    Cast.swing(server, human, net.minecraft.world.InteractionHand.OFF_HAND));
            check("swing: an unknown NPC is refused", !Cast.swing(server, UUID.randomUUID(), net.minecraft.world.InteractionHand.MAIN_HAND));
            // Moving: a phantom follows a leader along the trail they walked, stops close, and lets go when the lease lapses.
            {
                var motion = com.sablednah.cast.npc.Motion.class;
                Vec3 start = Cast.byId(server, human).flatMap(Npc::entity).map(e -> e.position()).orElse(here);
                viewer.snapTo(start.x, start.y, start.z + 3, 0F, 0F);
                viewer.setOnGround(true);
                check("follow: a known NPC accepts a leader", Cast.follow(server, human, viewer, 40));
                check("follow: an unknown NPC does not", !Cast.follow(server, UUID.randomUUID(), viewer, 40));
                check("follow: it reports its leader", Cast.leaderOf(human).map(viewer.getUUID()::equals).orElse(false));
                check("follow: a following body is unanchored", !Npcs.isAnchored(human));
                // The leader walks eight blocks along x, a step per tick; the follower ticks along.
                for (int t = 0; t < 32; t++) {
                    viewer.snapTo(start.x + t * 0.25, start.y, start.z + 3, 0F, 0F);
                    Cast.follow(server, human, viewer, 40);
                    com.sablednah.cast.npc.Motion.tick(server);
                }
                for (int t = 0; t < 80; t++) { Cast.follow(server, human, viewer, 40); com.sablednah.cast.npc.Motion.tick(server); }
                Vec3 followed = Cast.byId(server, human).flatMap(Npc::entity).map(e -> e.position()).orElse(start);
                double gap = followed.distanceTo(viewer.position());
                check("follow: it walked after the leader (moved " + String.format("%.1f", followed.distanceTo(start)) + ", gap " + String.format("%.1f", gap) + ")",
                        followed.distanceTo(start) > 4.0 && gap < 4.0);
                // A per-follower pace (added for the two-follower case below) makes the exact stopping
                // distance vary a little from run to run; the property worth proving is "stopped
                // somewhere near, not on top of" -- KEEP itself is 2.5, so anything comfortably above
                // zero clears that bar.
                check("follow: it stopped short of the leader, not on top of them (" + gap + ")", gap > 0.5);
                check("follow: the spec moved with the phantom", Cast.byId(server, human).map(n -> n.pos().distanceTo(followed) < 0.01).orElse(false));
                // Nobody renews: the lease lapses and it is anchored where it stands.
                for (int t = 0; t < 60; t++) com.sablednah.cast.npc.Motion.tick(server);
                check("follow: an unrenewed lease lets go", !Cast.isMoving(human) && Cast.leaderOf(human).isEmpty());
                check("follow: and it is anchored again", Npcs.isAnchored(human));
                // walkTo: three blocks along z, and stands there.
                Vec3 goal = followed.add(0, 0, 3);
                check("walkTo: an NPC accepts a destination", Cast.walkTo(server, human, goal));
                for (int t = 0; t < 60 && Cast.isMoving(human); t++) com.sablednah.cast.npc.Motion.tick(server);
                Vec3 walked = Cast.byId(server, human).map(Npc::pos).orElse(followed);
                check("walkTo: it got there and stopped (" + walked + " for " + goal + ", moving " + Cast.isMoving(human) + ", blocks " + level.getBlockState(net.minecraft.core.BlockPos.containing(followed.add(0, 0, 1))) + "/" + level.getBlockState(net.minecraft.core.BlockPos.containing(followed.add(0, 1, 1))) + ")",
                        !Cast.isMoving(human) && Math.hypot(walked.x - goal.x, walked.z - goal.z) < 0.5 && Npcs.isAnchored(human));
                if (motion == null) check("motion class", false);
            }
            // Two followers of the same leader: reported in play as standing exactly on top of one
            // another, faces flickering between them. Each gets its own slot beside the leader.
            {
                UUID second = Cast.spawnHuman(level, here.add(1, 0, 0), 0F, "Second Follower", java.util.Optional.empty(), List.of());
                try {
                    viewer.snapTo(here.x, here.y, here.z, 0F, 0F);
                    viewer.setOnGround(true);
                    Cast.follow(server, human, viewer, 40);
                    Cast.follow(server, second, viewer, 40);
                    for (int t = 0; t < 60; t++) {
                        Cast.follow(server, human, viewer, 40);
                        Cast.follow(server, second, viewer, 40);
                        com.sablednah.cast.npc.Motion.tick(server);
                    }
                    Vec3 p1 = Cast.byId(server, human).map(Npc::pos).orElse(Vec3.ZERO);
                    Vec3 p2 = Cast.byId(server, second).map(Npc::pos).orElse(Vec3.ZERO);
                    check("follow: two followers of the same leader do not stand on each other (" + p1.distanceTo(p2) + " apart)",
                            p1.distanceTo(p2) > 1.0D);
                } finally {
                    Cast.remove(server, second);
                }
            }
            // Lurking: the caged zombie. Scared on demand, it rushes the door, hammers, and slinks home.
            // A body walks on its own legs only while the level ticks, which a self-test does not, so the
            // rush and the slink both run out of time and are put there -- the phases are what is checked.
            {
                Vec3 home = Cast.byId(server, zombie).map(Npc::pos).orElse(here);
                Vec3 door = home.add(3, 0, 0);
                check("lurk: nothing to scare before it lurks", !Cast.scare(server, zombie));
                Cast.setLurk(server, zombie, home, door, 8.0, 20);
                check("lurk: saved with the NPC", NpcStore.get(server).get(zombie).flatMap(com.sablednah.cast.core.NpcSpec::lurk).map(l -> l.door().equals(door)).orElse(false));
                check("lurk: a scare starts a rush", Cast.scare(server, zombie) && Cast.isMoving(zombie)
                        && com.sablednah.cast.npc.Motion.lurkPhase(zombie).equals("rushing"));
                for (int t = 0; t < 105; t++) com.sablednah.cast.npc.Motion.tick(server);
                check("lurk: at the door, hammering (" + com.sablednah.cast.npc.Motion.lurkPhase(zombie) + ")",
                        com.sablednah.cast.npc.Motion.lurkPhase(zombie).equals("at_door")
                        && Cast.byId(server, zombie).map(n -> n.pos().distanceTo(door) < 1.5).orElse(false));
                for (int t = 0; t < 45; t++) com.sablednah.cast.npc.Motion.tick(server);
                check("lurk: then it slinks back", com.sablednah.cast.npc.Motion.lurkPhase(zombie).equals("slinking"));
                for (int t = 0; t < 305; t++) com.sablednah.cast.npc.Motion.tick(server);
                check("lurk: home in the dark, hiding, anchored (" + com.sablednah.cast.npc.Motion.lurkPhase(zombie) + ")",
                        com.sablednah.cast.npc.Motion.lurkPhase(zombie).equals("hiding") && Npcs.isAnchored(zombie)
                        && Cast.byId(server, zombie).map(n -> Math.hypot(n.pos().x - home.x, n.pos().z - home.z) < 1.5).orElse(false));
                Cast.clearLurk(server, zombie);
                check("lurk: cleared", NpcStore.get(server).get(zombie).flatMap(com.sablednah.cast.core.NpcSpec::lurk).isEmpty() && !Cast.scare(server, zombie));
            }
            // Hits: nothing hunts an NPC until it is exposed; then monsters go for it, and each blow is an event, never damage.
            {
                java.util.Map<UUID, Integer> hits = new java.util.HashMap<>();
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((com.sablednah.cast.api.NpcHitEvent ev) -> hits.merge(ev.npcId(), 1, Integer::sum));
                Mob zb2 = (Mob) Cast.byId(server, zombie).flatMap(Npc::entity).orElse(null);
                var hostile = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
                if (zb2 != null && hostile != null) {
                    hostile.snapTo(zb2.getX() + 2, zb2.getY(), zb2.getZ(), 0F, 0F);
                    check("expose: a known NPC can be exposed", Cast.expose(server, zombie, 40) && Cast.isExposed(zombie));
                    check("expose: an unknown one cannot", !Cast.expose(server, UUID.randomUUID(), 40));
                    com.sablednah.cast.npc.Exposure.tick(server);
                    check("expose: an exposed body can be struck (no longer invulnerable to the event)", !zb2.isInvulnerable());
                    // A goal in the target selector, not a bare setTarget: it only takes effect once the
                    // selector itself runs it (real play, on the mob's own next AI tick), same as any
                    // other TargetGoal -- proving the fix actually rides the framework rather than
                    // fighting it, which is exactly what a bare setTarget looked fine and was not.
                    check("expose: lureOne attaches a goal, not an instant target", com.sablednah.cast.npc.Exposure.lureOne(hostile, zb2)
                            && hostile.getTarget() != zb2 && com.sablednah.cast.npc.Exposure.attachedCount() == 1);
                    hostile.targetSelector.tick();
                    check("expose: ...and the selector picks it up on its own next tick", hostile.getTarget() == zb2);
                    // A rival TargetGoal (same flag, lower priority) trying to steal the target every tick is
                    // exactly the failure mode found in play: our goal must keep winning, tick after tick.
                    var rival = new net.minecraft.world.entity.ai.goal.Goal() {
                        { setFlags(java.util.EnumSet.of(Flag.TARGET)); }
                        @Override public boolean canUse() { return true; }
                        @Override public void start() { hostile.setTarget(null); }
                    };
                    hostile.targetSelector.addGoal(1, rival);
                    for (int t = 0; t < 5; t++) hostile.targetSelector.tick();
                    check("expose: a rival target goal at lower priority does not win", hostile.getTarget() == zb2);
                    hostile.targetSelector.removeGoal(rival); // a fixture for this one check, not left to fight every check after it
                    float hp = zb2.getHealth();
                    zb2.hurtServer(level, level.damageSources().mobAttack(hostile), 3F);
                    check("hit: a blow on the body is one hit, and no damage (" + hits.get(zombie) + ")", hits.getOrDefault(zombie, 0) == 1 && zb2.getHealth() == hp);
                    zb2.hurtServer(level, level.damageSources().mobAttack(hostile), 3F);
                    check("hit: a second blow in the same instant is folded into the first", hits.getOrDefault(zombie, 0) == 1);
                    var ph = Cast.byId(server, human).flatMap(Npc::entity).orElse(null);
                    if (ph instanceof HumanNpc phantom2) {
                        phantom2.hurtServer(level, level.damageSources().mobAttack(hostile), 3F);
                        check("hit: a blow on a phantom is a hit too", hits.getOrDefault(human, 0) == 1);
                    } else {
                        check("hit: a phantom to strike", false);
                    }
                    // Provoked: hitting the monster while it is after an NPC turns it on the hitter instead --
                    // priority the other way round from an exposed NPC, which is meant to be a magnet, not a wall.
                    var stranger = new FakePlayer(level, new GameProfile(UUID.nameUUIDFromBytes("cast:stranger".getBytes()), "CastStranger"));
                    stranger.setInvulnerable(false); // a FakePlayer starts invulnerable (its own constructor); a real attacking player is not, and a Mob may not target one that is
                    stranger.snapTo(hostile.getX() + 1, hostile.getY(), hostile.getZ(), 0F, 0F);
                    check("provoke: before any hit, still after the NPC", hostile.getTarget() == zb2);
                    com.sablednah.cast.npc.Exposure.provoke(hostile, stranger);
                    hostile.targetSelector.tick();
                    check("provoke: a hit turns it on whoever landed it", hostile.getTarget() == stranger);
                    hostile.targetSelector.tick();
                    check("provoke: it keeps after them while the window holds", hostile.getTarget() == stranger);
                    var untouched = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
                    if (untouched != null) {
                        com.sablednah.cast.npc.Exposure.provoke(untouched, stranger);
                        check("provoke: a mob nothing has lured is an untouched no-op", untouched.getTarget() == null);
                        untouched.discard();
                    }
                    stranger.discard();
                    com.sablednah.cast.npc.Exposure.cover(server, zombie);
                    Npcs.tick(server);
                    check("expose: covered, the body is invulnerable again", !Cast.isExposed(zombie) && zb2.isInvulnerable());
                    check("expose: covered, the goal is detached, not left to re-aim itself at nothing",
                            com.sablednah.cast.npc.Exposure.attachedCount() == 0);
                    hostile.targetSelector.tick();
                    check("expose: ...and the monster stops chasing", hostile.getTarget() != zb2);
                } else {
                    check("expose: a body and a monster to test with", false);
                }
                if (hostile != null) hostile.discard();
            }
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
