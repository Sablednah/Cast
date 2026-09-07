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
        Vec3 here = Vec3.atBottomCenterOf(level.getRespawnData().globalPos().pos());
        level.setChunkForced(((int) here.x) >> 4, ((int) here.z) >> 4, true);
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
            var proxy = com.sablednah.cast.npc.Proxies.of(human);
            var proxyEntity = proxy.orElse(null);
            Npcs.tick(server);
            check("a second tick does not make a second proxy", proxyEntity != null && com.sablednah.cast.npc.Proxies.of(human).orElse(null) == proxyEntity);
            check("phantom has a solid proxy standing in its space", proxy
                    .map(p -> p.distanceToSqr(here) < 0.01 && p.isInvisible() && p.isInvulnerable()).orElse(false));
            check("human is not listed", h.flatMap(Npc::entity).map(e -> !((HumanNpc) e).allowsListing()).orElse(false));

            Optional<Npc> v = Cast.byId(server, villager);
            check("villager body exists", v.flatMap(Npc::entity).isPresent());
            check("villager body carries the public marker", v.flatMap(Npc::entity).map(e -> Cast.isNpc(e) && Cast.npcIdOf(e).map(villager::equals).orElse(false)).orElse(false));
            check("villager brain has no behaviours", v.flatMap(Npc::entity).map(e -> ((Mob) e).getBrain().getRunningBehaviors().isEmpty()).orElse(false));
            check("villager is invulnerable", v.flatMap(Npc::entity).map(e -> e.isInvulnerable()).orElse(false));
            Optional<Npc> z = Cast.byId(server, zombie);
            check("zombie body exists and is ours", z.flatMap(Npc::entity).map(Cast::isNpc).orElse(false));
            // Anchoring: a shoved body goes home; a possessed one is left where its wearer walks it.
            Mob zb = (Mob) z.flatMap(Npc::entity).orElseThrow();
            Vec3 home = zb.position();
            zb.snapTo(home.x + 3, home.y, home.z, 0F, 0F);
            Npcs.tick(server);
            check("anchored body snaps home after a shove", zb.distanceToSqr(home) < 0.01);
            Cast.setAnchored(server, zombie, false);
            zb.snapTo(home.x + 3, home.y, home.z, 0F, 0F);
            Npcs.tick(server);
            check("unanchored body stays where it was walked", zb.distanceToSqr(home) > 8.0);
            Cast.setAnchored(server, zombie, true);
            Npcs.tick(server);
            check("re-anchoring adopts the new spot", zb.distanceToSqr(home) > 8.0
                    && Cast.byId(server, zombie).map(n -> n.pos().distanceToSqr(zb.position()) < 0.01).orElse(false));
            check("zombie has only our goals", z.flatMap(Npc::entity).map(e -> ((Mob) e).goalSelector.getAvailableGoals().size() == 2).orElse(false));

            // --- packets to a viewer: must not throw ---
            FakePlayer viewer = new FakePlayer(level, new GameProfile(UUID.nameUUIDFromBytes("cast:viewer".getBytes()), "CastViewer"));
            viewer.snapTo(here.x, here.y, here.z - 3, 0F, 0F); // three blocks back, facing +z at the phantom
            HumanNpc phantom = (HumanNpc) h.flatMap(Npc::entity).orElseThrow();
            boolean ok = true;
            try { Phantoms.show(viewer, phantom); Phantoms.hide(viewer, phantom); } catch (RuntimeException e) { ok = false; CastMod.LOGGER.error("packets", e); }
            check("phantom show/hide packets build", ok);

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
            check("npcAt finds the phantom in front of the viewer", Cast.npcAt(viewer, 6).map(n -> n.id().equals(human)).orElse(false));
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
            Cast.drive(server, human, here.add(0, 0, 3), 45F, 0F);
            check("drive moves the spec", Cast.byId(server, human).map(n -> n.pos().z > here.z + 2).orElse(false));
            check("say with nobody near returns 0", Cast.say(server, human, "hello?", 8.0) == 0);
            check("rename lands", Cast.byId(server, human).map(n -> n.name().equals("Renamed")).orElse(false));
            viewer.discard();
        } finally {
            check("remove is idempotent (first)", Cast.remove(server, human));
            check("remove is idempotent (second)", !Cast.remove(server, human));
            Cast.remove(server, villager);
            Cast.remove(server, zombie);
            check("store back to where it was", store.size() == before);
            Npcs.tick(server);
            check("proxy gone with its phantom", com.sablednah.cast.npc.Proxies.of(human).isEmpty());
            level.setChunkForced(((int) here.x) >> 4, ((int) here.z) >> 4, false);
        }

        // The accessor mixin must be LISTED in cast.mixins.json, not just present in the package: an
        // unlisted accessor throws IllegalClassLoadError on the first interact packet of any kind --
        // which is every hit on every mob. Found by Sable hitting a zombie. Exercise it here.
        try {
            var probe = new FakePlayer(level, new GameProfile(UUID.nameUUIDFromBytes("cast:probe".getBytes()), "CastProbe"));
            var packet = net.minecraft.network.protocol.game.ServerboundInteractPacket.createAttackPacket(probe, false);
            check("interact accessor mixin is applied", ((com.sablednah.cast.mixin.InteractPacketAccessor) packet).cast$entityId() == probe.getId());
            probe.discard();
        } catch (Throwable t) {
            check("interact accessor mixin is applied (" + t + ")", false);
        }

        check("lang catalogue", Lang.catalogueSize() > 15 && !Feedback.colored("&6x").getString().contains("§"));
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
