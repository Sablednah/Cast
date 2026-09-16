package com.sablednah.cast.npc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.sablednah.cast.CastConfig;
import com.sablednah.cast.api.NpcHitEvent;
import com.sablednah.cast.api.NpcKind;
import com.sablednah.cast.core.NpcSpec;
import com.sablednah.cast.core.NpcStore;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NPCs worth attacking. An NPC takes no damage and, left alone, nothing hunts it: a phantom
 * is in no level for a monster to notice, and a body is invulnerable. <b>Exposed</b>, the
 * monsters near it are pointed at it once a second, and every blow that lands is reported as
 * an {@link NpcHitEvent} -- still no damage. An escort that can go wrong is built on this.
 *
 * <p>Leased like a follow: the caller renews it, and when it lapses the monsters that were
 * after the NPC are called off and a body is invulnerable again.</p>
 */
public final class Exposure {

    private static final Map<UUID, Long> EXPOSED = new HashMap<>();
    /** npcId -> game time of the last reported hit, so a burst of blows is one. */
    private static final Map<UUID, Long> LAST_HIT = new HashMap<>();
    private static final long HIT_COOLDOWN = 10L;

    public static boolean expose(MinecraftServer server, UUID npcId, int leaseTicks) {
        if (NpcStore.get(server).get(npcId).isEmpty()) return false;
        EXPOSED.put(npcId, server.overworld().getGameTime() + Math.max(20, leaseTicks));
        return true;
    }

    public static boolean isExposed(UUID npcId) {
        return EXPOSED.containsKey(npcId);
    }

    public static void cover(MinecraftServer server, UUID npcId) {
        if (EXPOSED.remove(npcId) == null) return;
        NpcStore.get(server).get(npcId).ifPresent(spec -> {
            ServerLevel level = Npcs.levelOf(server, spec);
            Entity body = entity(level, spec);
            if (level == null || body == null) return;
            if (body instanceof Mob m) m.setInvulnerable(true);
            // Called off: a monster left chasing a ghost would stand there swinging at the air.
            for (Mob m : level.getEntitiesOfClass(Mob.class, body.getBoundingBox().inflate(48), m -> m.getTarget() == body)) m.setTarget(null);
        });
    }

    /** Once a second: lapses, and the lure. */
    public static void tick(MinecraftServer server) {
        if (EXPOSED.isEmpty()) return;
        NpcStore store = NpcStore.get(server);
        for (UUID id : List.copyOf(EXPOSED.keySet())) {
            Optional<NpcSpec> spec = store.get(id);
            if (spec.isEmpty()) { EXPOSED.remove(id); continue; }
            ServerLevel level = Npcs.levelOf(server, spec.get());
            if (level == null) continue;
            if (level.getGameTime() > EXPOSED.get(id)) { cover(server, id); continue; }
            lure(level, spec.get());
        }
    }

    /** Point the monsters around it at it: those with nobody to chase, or with someone further off than it. */
    static int lure(ServerLevel level, NpcSpec spec) {
        Entity e = entity(level, spec);
        if (!(e instanceof LivingEntity target)) return 0;
        if (target instanceof Mob m && m.isInvulnerable()) m.setInvulnerable(false); // a hit must reach the damage event to be seen
        double r = CastConfig.LURE_RADIUS.get();
        int n = 0;
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(target.blockPosition()).inflate(r),
                m -> m instanceof Enemy && m.isAlive() && Npcs.npcIdOf(m).isEmpty())) {
            if (lureOne(m, target)) n++;
        }
        return n;
    }

    /** One monster: set on the NPC unless it already has someone nearer to chase. Package-visible for the self-test. */
    public static boolean lureOne(Mob m, LivingEntity target) {
        LivingEntity current = m.getTarget();
        if (current == target) return true;
        if (current == null || !current.isAlive() || current.distanceToSqr(m) > target.distanceToSqr(m) + 9.0D) {
            m.setTarget(target);
            return true;
        }
        return false;
    }

    /** A blow landed on an NPC. Reports it (once per half second) and says whether it was one. */
    public static boolean hit(Entity npcEntity, Optional<Entity> attacker) {
        Optional<UUID> id = Npcs.npcIdOf(npcEntity);
        if (id.isEmpty()) return false;
        long now = npcEntity.level().getGameTime();
        Long last = LAST_HIT.get(id.get());
        if (last != null && now - last < HIT_COOLDOWN && now >= last) return false;
        LAST_HIT.put(id.get(), now);
        NeoForge.EVENT_BUS.post(new NpcHitEvent(id.get(), attacker));
        return true;
    }

    private static Entity entity(ServerLevel level, NpcSpec spec) {
        if (level == null) return null;
        return spec.kind() == NpcKind.HUMAN ? Npcs.human(spec.id()) : Npcs.bodyOf(level, spec).orElse(null);
    }

    public static void forget(UUID npcId) {
        EXPOSED.remove(npcId);
        LAST_HIT.remove(npcId);
    }

    public static void clear() {
        EXPOSED.clear();
        LAST_HIT.clear();
    }

    private Exposure() {}
}
