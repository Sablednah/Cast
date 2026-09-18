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
 * monsters near it are pointed at it, and every blow that lands is reported as an
 * {@link NpcHitEvent} -- still no damage. An escort that can go wrong is built on this.
 *
 * <p><b>Why a goal, not a bare {@code Mob#setTarget}.</b> The first version called {@code
 * setTarget} once a second from here and, in real play, monsters visibly ignored an exposed
 * NPC -- nothing thrown, nothing logged, because there was nothing wrong to catch: the mob's
 * own {@code TargetGoal} re-evaluates on its own schedule and, seeing a target it did not pick
 * itself, cleared it again before any attack goal noticed. {@link LureGoal} is added to the
 * mob's own {@code targetSelector} instead, so it is arbitrated by the same framework the
 * mob's other targeting goals answer to and wins on equal terms rather than being overwritten
 * from outside it.</p>
 *
 * <p>Leased like a follow: the caller renews it, and when it lapses the monsters that were
 * after the NPC are called off (their goal detached) and a body is invulnerable again.</p>
 */
public final class Exposure {

    private static final Map<UUID, Long> EXPOSED = new HashMap<>();
    /** npcId -> game time of the last reported hit, so a burst of blows is one. */
    private static final Map<UUID, Long> LAST_HIT = new HashMap<>();
    private static final long HIT_COOLDOWN = 10L;

    /** A monster currently carrying a {@link LureGoal}, keyed by its own entity id. */
    private record Attached(Mob mob, LureGoal goal) {}
    private static final Map<UUID, Attached> ATTACHED = new HashMap<>();

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
            if (level == null) return;
            if (body instanceof Mob m) m.setInvulnerable(true);
            // Called off: leaving the goal attached would just have it re-aim at nothing next tick.
            if (body != null) detachAllAiming(body);
        });
    }

    /** Once a second: lapses, the lure, and sweeping anything nobody re-aimed this pass. */
    public static void tick(MinecraftServer server) {
        if (!EXPOSED.isEmpty()) {
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
        sweep();
    }

    /** Point the monsters around it at it. Package-visible for the self-test. */
    static int lure(ServerLevel level, NpcSpec spec) {
        Entity e = entity(level, spec);
        if (!(e instanceof LivingEntity target)) return 0;
        if (target instanceof Mob m && m.isInvulnerable()) m.setInvulnerable(false); // a hit must reach the damage event to be seen
        double r = CastConfig.LURE_RADIUS.get();
        int n = 0;
        for (Mob m : level.getEntitiesOfClass(Mob.class, new AABB(target.blockPosition()).inflate(r),
                m -> m instanceof Enemy && m.isAlive() && Npcs.npcIdOf(m).isEmpty())) {
            attach(m).aim(target);
            n++;
        }
        return n;
    }

    /** This mob's lure goal, adding it to the target selector the first time the mob is seen. */
    private static LureGoal attach(Mob m) {
        Attached existing = ATTACHED.get(m.getUUID());
        if (existing != null && existing.mob() == m) return existing.goal();
        LureGoal goal = new LureGoal(m);
        m.targetSelector.addGoal(0, goal);
        ATTACHED.put(m.getUUID(), new Attached(m, goal));
        return goal;
    }

    /** Every mob whose lure goal is aimed at this exact entity, detached -- its NPC just stopped being exposed. */
    private static void detachAllAiming(Entity target) {
        for (UUID id : List.copyOf(ATTACHED.keySet())) {
            Attached a = ATTACHED.get(id);
            if (a.goal().aimedAt(target)) detach(id, a);
        }
    }

    private static void detach(UUID mobId, Attached a) {
        a.mob().targetSelector.removeGoal(a.goal());
        ATTACHED.remove(mobId);
    }

    /** Drop anything dead or unloaded. A mob simply out of every exposed radius keeps its goal parked
     * (harmless: {@link LureGoal#canUse} answers false the moment its aim is cleared, which cover() does)
     * rather than being torn down and rebuilt on every pass it happens to fall outside {@code lure()}'s scan. */
    private static void sweep() {
        if (ATTACHED.isEmpty()) return;
        for (UUID id : List.copyOf(ATTACHED.keySet())) {
            Attached a = ATTACHED.get(id);
            if (!a.mob().isAlive() || a.mob().isRemoved()) detach(id, a);
        }
    }

    /** One monster, aimed directly. Package-visible for the self-test. */
    public static boolean lureOne(Mob m, LivingEntity target) {
        attach(m).aim(target);
        return true;
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
        for (Attached a : ATTACHED.values()) {
            try { a.mob().targetSelector.removeGoal(a.goal()); } catch (RuntimeException ignored) {}
        }
        ATTACHED.clear();
    }

    /** How many monsters currently carry a lure goal -- the self-test's window onto the attach side. */
    public static int attachedCount() { return ATTACHED.size(); }

    private Exposure() {}
}
