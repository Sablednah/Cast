package com.sablednah.cast.npc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.sablednah.cast.CastConfig;
import com.sablednah.cast.CastMod;
import com.sablednah.cast.api.Cast;
import com.sablednah.cast.api.Npc;
import com.sablednah.cast.api.NpcHit;
import com.sablednah.cast.api.NpcRemovedEvent;
import com.sablednah.cast.api.Role;
import com.sablednah.cast.api.NpcKind;
import com.sablednah.cast.core.NpcSpec;
import com.sablednah.cast.core.NpcStore;
import com.sablednah.cast.neoforge.Feedback;
import com.sablednah.cast.neoforge.Lang;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;

/**
 * The manager: specs in the store, bodies in the world, and the once-a-second
 * tick that keeps the two agreeing. Every public method takes an npcId.
 */
public final class Npcs {

    /** Human phantoms currently materialised, by npcId. */
    private static final Map<UUID, HumanNpc> HUMANS = new HashMap<>();
    /** Mob bodies Cast has seen alive, by npcId: an entity added this tick is not in the level index until the next. */
    private static final Map<UUID, Mob> MOBS = new HashMap<>();
    private static final Map<Identifier, Role> ROLES = new LinkedHashMap<>();
    private static final Set<UUID> ROLES_OFF = new HashSet<>();

    // --- spawning ---

    public static UUID spawnHuman(ServerLevel level, Vec3 pos, float yaw, String name, Optional<String> skin, List<Identifier> roles) {
        NpcSpec spec = new NpcSpec(UUID.randomUUID(), NpcKind.HUMAN, name, skin, Optional.empty(),
                level.dimension().identifier(), pos, yaw, 0F, roles, true, Optional.empty());
        NpcStore.get(level.getServer()).put(spec);
        skin.ifPresent(s -> Skins.ensure(level.getServer(), s, spec.id()));
        tickOne(level.getServer(), spec);
        return spec.id();
    }

    public static UUID spawnMob(ServerLevel level, Vec3 pos, float yaw, Identifier entityType, String name, List<Identifier> roles) {
        NpcSpec spec = new NpcSpec(UUID.randomUUID(), NpcKind.MOB, name, Optional.empty(), Optional.of(entityType),
                level.dimension().identifier(), pos, yaw, 0F, roles, true, Optional.empty());
        NpcStore.get(level.getServer()).put(spec);
        tickOne(level.getServer(), spec);
        return spec.id();
    }

    /** Idempotent, and works unloaded. */
    public static boolean remove(MinecraftServer server, UUID npcId) {
        NpcStore store = NpcStore.get(server);
        Optional<NpcSpec> spec = store.get(npcId);
        boolean had = store.remove(npcId);
        spec.ifPresent(s -> {
            ServerLevel level = level(server, s);
            HumanNpc human = HUMANS.remove(npcId);
            if (human != null && level != null) Phantoms.hideFromAll(level, human);
            Proxies.remove(npcId);
            if (level != null) body(level, s).ifPresent(Entity::discard);
            MOBS.remove(npcId);
        });
        BROKEN.remove(npcId);
        UNANCHORED.remove(npcId);
        if (had) NeoForge.EVENT_BUS.post(new NpcRemovedEvent(npcId, NpcRemovedEvent.Reason.REMOVED));
        return had;
    }

    // --- the tick ---

    private static final Set<UUID> BROKEN = new HashSet<>();
    /** NPCs whose anchor is suspended -- a possessor is walking them somewhere. */
    private static final Set<UUID> UNANCHORED = new HashSet<>();

    /**
     * Anchoring: a body is put back on its spot once a second, so a shove
     * does not herd it away. A possessor moves an NPC on purpose, so
     * possession suspends the anchor; re-anchoring makes wherever the body
     * stands now its new spot.
     */
    public static void setAnchored(MinecraftServer server, UUID npcId, boolean anchored) {
        if (!anchored) {
            UNANCHORED.add(npcId);
            return;
        }
        UNANCHORED.remove(npcId);
        NpcStore store = NpcStore.get(server);
        store.get(npcId).ifPresent(spec -> {
            ServerLevel level = level(server, spec);
            if (level == null) return;
            Entity body = spec.kind() == NpcKind.HUMAN ? HUMANS.get(npcId) : body(level, spec).orElse(null);
            if (body != null) {
                store.put(spec.withPose(level.dimension().identifier(), body.position(), body.getYRot(), body.getXRot()));
            }
        });
    }

    public static boolean isAnchored(UUID npcId) {
        return !UNANCHORED.contains(npcId);
    }

    /** One bad NPC must never take the server tick with it: log once, skip it, keep going. */
    public static void tick(MinecraftServer server) {
        for (NpcSpec spec : List.copyOf(NpcStore.get(server).all())) {
            try {
                tickOne(server, spec);
            } catch (RuntimeException e) {
                if (BROKEN.add(spec.id())) {
                    CastMod.LOGGER.error("Cast: NPC {} ({}) threw and is skipped until restart or removal -- /cast remove it", spec.id(), spec.name(), e);
                }
            }
        }
    }

    private static void tickOne(MinecraftServer server, NpcSpec spec) {
        ServerLevel level = level(server, spec);
        if (level == null) return;
        boolean loaded = level.isLoaded(BlockPos.containing(spec.pos()));
        if (spec.kind() == NpcKind.HUMAN) {
            HumanNpc human = HUMANS.get(spec.id());
            if (!loaded) {
                if (human != null) {
                    Phantoms.hideFromAll(level, human);
                    HUMANS.remove(spec.id());
                    Proxies.remove(spec.id());
                    UNANCHORED.remove(spec.id());
                    NeoForge.EVENT_BUS.post(new NpcRemovedEvent(spec.id(), NpcRemovedEvent.Reason.UNLOAD));
                }
                return;
            }
            if (human == null) {
                human = HumanNpc.create(level, spec, NpcStore.get(server));
                HUMANS.put(spec.id(), human);
            }
            Proxies.ensure(level, human, spec);
            if (spec.lookAtPlayers()) look(level, human, spec);
            Phantoms.update(level, human, spec);
        } else {
            if (!loaded) return;
            Optional<Mob> body = body(level, spec);
            if (body.isPresent()) {
                Bodies.maintain(body.get(), spec, isAnchored(spec.id()));
                return;
            }
            Bodies.spawn(level, spec).ifPresent(mob -> {
                MOBS.put(spec.id(), mob);
                NpcStore.get(server).put(spec.withEntityUuid(Optional.of(mob.getUUID())));
            });
        }
    }

    /** Turn a phantom towards the nearest player in look range, or back to rest. */
    private static void look(ServerLevel level, HumanNpc human, NpcSpec spec) {
        double range = CastConfig.LOOK_RANGE.get();
        ServerPlayer nearest = level.getNearestPlayer(human.getX(), human.getY(), human.getZ(), range, false) instanceof ServerPlayer p ? p : null;
        float yaw = spec.yaw();
        float pitch = spec.pitch();
        if (nearest != null) {
            double dx = nearest.getX() - human.getX();
            double dz = nearest.getZ() - human.getZ();
            double dy = nearest.getEyeY() - human.getEyeY();
            yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        }
        if (Math.abs(yaw - human.getYRot()) > 1.0F || Math.abs(pitch - human.getXRot()) > 1.0F) {
            human.setYRot(yaw);
            human.setYHeadRot(yaw);
            human.setXRot(pitch);
            Phantoms.broadcastRotation(level, human);
        }
    }

    /**
     * Re-dress a human (skin arrived, name changed): take it down everywhere
     * and let the tick bring it back with a new entity id. Anyone holding a
     * camera on the old id is told, because the remove packet has just
     * ejected it.
     */
    public static void rebody(MinecraftServer server, UUID npcId) {
        NpcStore.get(server).get(npcId).ifPresent(spec -> {
            ServerLevel level = level(server, spec);
            HumanNpc human = HUMANS.remove(npcId);
            // A new body under the same npcId starts anchored, whatever the old one was doing:
            // a suspended anchor must never outlive the body it was suspended for.
            UNANCHORED.remove(npcId);
            if (human != null && level != null) {
                Phantoms.hideFromAll(level, human);
                NeoForge.EVENT_BUS.post(new NpcRemovedEvent(npcId, NpcRemovedEvent.Reason.REBODY));
            }
            tickOne(server, spec);
        });
    }

    // --- lookups ---

    private static ServerLevel level(MinecraftServer server, NpcSpec spec) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, spec.dimension()));
    }

    private static Optional<Mob> body(ServerLevel level, NpcSpec spec) {
        Mob cached = MOBS.get(spec.id());
        if (cached != null && cached.isAlive() && !cached.isRemoved() && cached.level() == level) return Optional.of(cached);
        Optional<Mob> found = spec.entityUuid().map(level::getEntity).filter(e -> e instanceof Mob && e.isAlive()).map(e -> (Mob) e);
        found.ifPresentOrElse(m -> MOBS.put(spec.id(), m), () -> MOBS.remove(spec.id()));
        return found;
    }

    public static Optional<UUID> npcIdOf(Entity entity) {
        if (entity instanceof HumanNpc h) return Optional.of(h.npcId);
        // A proxy stands in for its phantom: same id, same roles. Never a body of its own.
        if (entity == null) return Optional.empty();
        return entity.getPersistentData().getString(Cast.MARKER).flatMap(s -> {
            try { return Optional.of(UUID.fromString(s)); } catch (IllegalArgumentException e) { return Optional.empty(); }
        });
    }

    public static Optional<HumanNpc> humanByEntityId(int entityId) {
        return HUMANS.values().stream().filter(h -> h.getId() == entityId).findFirst();
    }

    public static Optional<Npc> handle(MinecraftServer server, UUID npcId) {
        return NpcStore.get(server).get(npcId).map(spec -> {
            ServerLevel level = level(server, spec);
            boolean loaded = level != null && level.isLoaded(BlockPos.containing(spec.pos()));
            Optional<Entity> entity = spec.kind() == NpcKind.HUMAN
                    ? Optional.ofNullable(HUMANS.get(npcId))
                    : (level == null ? Optional.empty() : body(level, spec).map(m -> m));
            return new Npc(spec.id(), spec.kind(), spec.name(), spec.dimension(), spec.pos(), spec.yaw(), spec.pitch(),
                    spec.roles(), entity, loaded, entity.isPresent());
        });
    }

    public static List<Npc> handles(MinecraftServer server) {
        List<Npc> out = new ArrayList<>();
        for (NpcSpec s : NpcStore.get(server).all()) handle(server, s.id()).ifPresent(out::add);
        return out;
    }

    /** The NPC on the player's line of sight, either body, with the hit point and distance. Phantoms are tested by hand; they are in no level. */
    public static Optional<NpcHit> lookedAt(ServerPlayer viewer, double reach) {
        Vec3 from = viewer.getEyePosition();
        Vec3 to = from.add(viewer.getLookAngle().scale(reach));
        double best = Double.MAX_VALUE;
        UUID hitId = null;
        Vec3 hitAt = null;
        for (HumanNpc h : HUMANS.values()) {
            if (h.level() != viewer.level()) continue;
            Optional<Vec3> clip = h.getBoundingBox().inflate(0.3).clip(from, to);
            if (clip.isPresent()) {
                double d = clip.get().distanceToSqr(from);
                if (d < best) { best = d; hitId = h.npcId; hitAt = clip.get(); }
            }
        }
        for (Entity e : viewer.level().getEntities(viewer, viewer.getBoundingBox().inflate(reach), en -> npcIdOf(en).isPresent())) {
            Optional<Vec3> clip = e.getBoundingBox().inflate(0.3).clip(from, to);
            if (clip.isPresent()) {
                double d = clip.get().distanceToSqr(from);
                if (d < best) { best = d; hitId = npcIdOf(e).orElse(null); hitAt = clip.get(); }
            }
        }
        if (hitId == null) return Optional.empty();
        final Vec3 at = hitAt;
        final double dist = Math.sqrt(best);
        return handle(viewer.level().getServer(), hitId).map(n -> new NpcHit(n, at, dist));
    }

    public static void pin(ServerPlayer player, UUID npcId) {
        NpcStore.get(player.level().getServer()).get(npcId).ifPresent(spec -> tickOne(player.level().getServer(), spec));
        Phantoms.pin(player, npcId, HUMANS.get(npcId));
    }

    public static void unpin(ServerPlayer player, UUID npcId) {
        Phantoms.unpin(player, npcId);
    }

    // --- doing ---

    public static int say(MinecraftServer server, UUID npcId, String text, double radius) {
        Optional<NpcSpec> spec = NpcStore.get(server).get(npcId);
        if (spec.isEmpty()) return 0;
        ServerLevel level = level(server, spec.get());
        if (level == null) return 0;
        double r2 = radius * radius;
        int heard = 0;
        for (ServerPlayer p : level.players()) {
            if (p.distanceToSqr(spec.get().pos()) <= r2) {
                Feedback.chat(p, Lang.fmt("msg.say", "name", spec.get().name(), "text", text));
                heard++;
            }
        }
        return heard;
    }

    public static void lookAt(MinecraftServer server, UUID npcId, Vec3 target) {
        NpcStore.get(server).get(npcId).ifPresent(spec -> {
            double dx = target.x - spec.pos().x, dz = target.z - spec.pos().z, dy = target.y - (spec.pos().y + 1.6);
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            drive(server, npcId, spec.pos(), yaw, pitch);
        });
    }

    /** A teleport, and documented as one. */
    public static void drive(MinecraftServer server, UUID npcId, Vec3 pos, float yaw, float pitch) {
        NpcStore store = NpcStore.get(server);
        store.get(npcId).ifPresent(spec -> {
            NpcSpec moved = spec.withPose(spec.dimension(), pos, yaw, pitch);
            store.put(moved);
            ServerLevel level = level(server, spec);
            if (level == null) return;
            if (spec.kind() == NpcKind.HUMAN) {
                HumanNpc h = HUMANS.get(npcId);
                if (h != null) {
                    Vec3 from = h.position();
                    h.snapTo(pos.x, pos.y, pos.z, yaw, pitch);
                    h.setYHeadRot(yaw);
                    Phantoms.broadcastMove(level, h, from);
                }
            } else {
                body(level, spec).ifPresent(m -> { m.snapTo(pos.x, pos.y, pos.z, yaw, pitch); m.setYHeadRot(yaw); });
            }
        });
    }

    public static void rename(MinecraftServer server, UUID npcId, String name) {
        NpcStore store = NpcStore.get(server);
        store.get(npcId).ifPresent(spec -> {
            store.put(spec.withName(name));
            if (spec.kind() == NpcKind.HUMAN) rebody(server, npcId);
            else { ServerLevel level = level(server, spec); if (level != null) body(level, spec).ifPresent(m -> Bodies.configure(m, spec.withName(name))); }
        });
    }

    public static void setSkin(MinecraftServer server, UUID npcId, Optional<String> skin) {
        NpcStore store = NpcStore.get(server);
        store.get(npcId).ifPresent(spec -> {
            store.put(spec.withSkin(skin));
            skin.ifPresent(s -> Skins.ensure(server, s, npcId));
            rebody(server, npcId);
        });
    }

    public static void setRoles(MinecraftServer server, UUID npcId, List<Identifier> roles) {
        NpcStore store = NpcStore.get(server);
        store.get(npcId).ifPresent(spec -> store.put(spec.withRoles(roles)));
    }

    public static void setLook(MinecraftServer server, UUID npcId, boolean look) {
        NpcStore store = NpcStore.get(server);
        store.get(npcId).ifPresent(spec -> {
            store.put(spec.withLook(look));
            if (spec.kind() == NpcKind.MOB) { ServerLevel level = level(server, spec); if (level != null) body(level, spec).ifPresent(m -> Bodies.configure(m, spec.withLook(look))); }
        });
    }

    // --- roles ---

    public static void registerRole(Identifier id, Role role) {
        ROLES.put(id, role);
    }

    public static void setRolesEnabled(ServerPlayer player, boolean enabled) {
        if (enabled) ROLES_OFF.remove(player.getUUID()); else ROLES_OFF.add(player.getUUID());
    }

    public static boolean rolesEnabled(ServerPlayer player) {
        return !ROLES_OFF.contains(player.getUUID());
    }

    public static Set<Identifier> roleIds() {
        return ROLES.keySet();
    }

    /** A right-click reached an NPC: dispatch its roles in order. Returns true if any role handled it. */
    public static boolean interact(ServerPlayer player, UUID npcId, InteractionHand hand) {
        if (!rolesEnabled(player)) return false;
        Optional<Npc> npc = handle(player.level().getServer(), npcId);
        if (npc.isEmpty()) return false;
        for (Identifier roleId : npc.get().roles()) {
            Role role = ROLES.get(roleId);
            if (role == null) {
                CastMod.LOGGER.debug("Cast: NPC {} carries role {} that nothing registered", npcId, roleId);
                continue;
            }
            try {
                if (role.onInteract(player, npc.get(), hand)) return true;
            } catch (RuntimeException e) {
                CastMod.LOGGER.error("Cast: role {} threw on NPC {}", roleId, npcId, e);
            }
        }
        return false;
    }

    // --- lifecycle from events ---

    public static void onBodyGone(Entity entity, NpcRemovedEvent.Reason reason) {
        npcIdOf(entity).ifPresent(id -> {
            if (NpcStore.get(entity.level().getServer()).get(id).isPresent()) {
                NeoForge.EVENT_BUS.post(new NpcRemovedEvent(id, reason));
            }
        });
    }

    /** A player left or changed level: a phantom they were pinned to is, for them, gone. */
    public static void onPlayerGone(ServerPlayer player) {
        for (UUID npcId : Phantoms.forget(player)) {
            NeoForge.EVENT_BUS.post(new NpcRemovedEvent(npcId, NpcRemovedEvent.Reason.DIMENSION_CHANGE));
        }
        ROLES_OFF.remove(player.getUUID());
    }

    /** Shutdown: every phantom goes, and says so -- a level-based hook would never fire for one. */
    public static void onServerStopping() {
        for (UUID npcId : List.copyOf(HUMANS.keySet())) {
            NeoForge.EVENT_BUS.post(new NpcRemovedEvent(npcId, NpcRemovedEvent.Reason.UNLOAD));
        }
        HUMANS.clear();
        MOBS.clear();
        UNANCHORED.clear();
        Proxies.clear();
        Phantoms.clear();
        ROLES_OFF.clear();
    }

    public static int loadedHumans() {
        return HUMANS.size();
    }

    private Npcs() {}
}
