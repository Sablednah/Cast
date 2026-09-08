package com.sablednah.cast.npc;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.sablednah.cast.api.Cast;
import com.sablednah.cast.core.NpcSpec;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;

/**
 * A phantom has no server body, so a zombie walks through it and a player
 * cannot bump into it -- while a villager NPC, being real, gets shoved
 * about. The proxy closes that gap: an invisible, invulnerable, silent armor
 * stand standing exactly where the phantom stands. Armor stands block
 * movement, so mobs and players meet something solid; the client draws
 * nothing for an invisible one; and a right-click that lands on the proxy
 * rather than the phantom reaches the same roles through the marker.
 *
 * <p>Proxies are never meant to outlive their phantom: they carry a tag, and
 * one found in a level with no live phantom behind it (a restart, a crash) is
 * discarded on sight.</p>
 */
public final class Proxies {

    public static final String TAG = "cast_proxy";
    private static final Map<UUID, ArmorStand> PROXIES = new HashMap<>();

    public static void ensure(ServerLevel level, HumanNpc npc, NpcSpec spec) {
        ArmorStand proxy = PROXIES.get(npc.npcId);
        // Liveness is "not removed", never isAlive(): a stand created this tick answers false
        // to isAlive, and asking it re-created a proxy every second -- an armor stand leak.
        if (proxy != null && !proxy.isRemoved() && proxy.level() == level) {
            if (proxy.distanceToSqr(npc.getX(), npc.getY(), npc.getZ()) > 0.01) {
                proxy.snapTo(npc.getX(), npc.getY(), npc.getZ(), npc.getYRot(), 0F);
            }
            return;
        }
        Entity created = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        if (!(created instanceof ArmorStand stand)) {
            com.sablednah.cast.CastMod.LOGGER.warn("Cast: could not create a proxy for NPC {} (got {})", npc.npcId, created);
            return;
        }
        stand.snapTo(npc.getX(), npc.getY(), npc.getZ(), npc.getYRot(), 0F);
        stand.setInvisible(true);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        // Gravity stays ON: vanilla's canBeCollidedWith is "not a marker and not no-gravity",
        // so a floating stand would be walked through -- the whole point lost.
        stand.setNoBasePlate(true);
        stand.setCustomNameVisible(false);
        stand.addTag(TAG);
        stand.getPersistentData().putString(Cast.MARKER, npc.npcId.toString());
        // Registered BEFORE it joins the level: the orphan reaper runs on EntityJoinLevelEvent and
        // discards any tagged stand it does not know, so the other order reaps our own proxy at birth.
        PROXIES.put(npc.npcId, stand);
        level.addFreshEntity(stand);
    }

    public static void remove(UUID npcId) {
        ArmorStand proxy = PROXIES.remove(npcId);
        if (proxy != null) proxy.discard();
    }

    /** A proxy with no live phantom behind it: a leftover from a restart. */
    public static boolean isOrphan(Entity entity) {
        if (!(entity instanceof ArmorStand) || !entity.entityTags().contains(TAG)) return false;
        return !PROXIES.containsValue(entity);
    }

    public static java.util.Optional<ArmorStand> of(UUID npcId) {
        ArmorStand p = PROXIES.get(npcId);
        return p != null && !p.isRemoved() ? java.util.Optional.of(p) : java.util.Optional.empty();
    }

    public static boolean isProxy(Entity entity) {
        return entity instanceof ArmorStand && entity.entityTags().contains(TAG);
    }

    public static void clear() {
        PROXIES.values().forEach(Entity::discard);
        PROXIES.clear();
    }

    private Proxies() {}
}
