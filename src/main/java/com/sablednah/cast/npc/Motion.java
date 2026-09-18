package com.sablednah.cast.npc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.sablednah.cast.CastConfig;
import com.sablednah.cast.CastMod;
import com.sablednah.cast.api.NpcKind;
import com.sablednah.cast.core.LurkSpec;
import com.sablednah.cast.core.NpcSpec;
import com.sablednah.cast.core.NpcStore;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * NPCs that move on purpose: following a player, walking to a spot, and lurking.
 *
 * <p><b>A phantom has no legs.</b> It is in no level, so there is no navigation to ask;
 * it is stepped by hand every tick and each step is sent as a relative move the client
 * interpolates. Following walks <em>the leader's own trail</em> -- breadcrumbs dropped where
 * the player actually stood -- so it goes through the door the player went through, down
 * the stairs they took, and never tries to path through a wall. A mob body walks with its
 * own navigation and falls back to the trail when it is stuck.</p>
 *
 * <p><b>Following is leased.</b> The caller renews it (Chronicler does, once a second, while
 * an escort is under way); when renewals stop -- the quest ended, the server restarted, the
 * mod that asked went away -- the NPC stops and is anchored where it stands. Nothing here is
 * saved, on purpose: a follow that survived a restart with nobody renewing it would be a
 * stranger walking after you forever.</p>
 *
 * <p>A lurk IS saved (it is part of the NPC, set by an admin once) and runs from the
 * once-a-second NPC tick, with its dash and slink back as ordinary walks.</p>
 */
public final class Motion {

    /** Blocks between breadcrumbs. */
    private static final double CRUMB = 0.75D;
    /** A follower stops this close to its leader. */
    private static final double KEEP = 2.5D;
    private static final int MAX_CRUMBS = 512;

    private static final class Follow {
        ServerPlayer leader;
        long expires;
        final ArrayDeque<Vec3> crumbs = new ArrayDeque<>();
        double bestDistance = Double.MAX_VALUE;
        long lastProgress;
        /** Which side of the leader this one stands on, assigned once. See {@link #lateralOffset}. */
        int slot;
    }

    /** A walk to one point, then something to do on arrival. */
    private record Walk(Vec3 target, double speed, double mobSpeed, Runnable onArrive, long giveUpAt) {}

    private static final Map<UUID, Follow> FOLLOWS = new HashMap<>();
    private static final Map<UUID, Walk> WALKS = new HashMap<>();
    private static final Map<UUID, Lurk> LURKS = new HashMap<>();
    private record Later(long at, Runnable run) {}
    private static final List<Later> LATER = new ArrayList<>();
    /** Motion's own clock, advanced by {@link #tick}: leases and scares count the ticks this has seen, so a self-test driving tick() by hand sees time pass. */
    private static long TICKS;

    // --- the API's verbs ---

    /** Follow {@code leader} until {@code leaseTicks} pass without another call. False for an NPC that does not exist. */
    public static boolean follow(MinecraftServer server, UUID npcId, ServerPlayer leader, int leaseTicks) {
        if (NpcStore.get(server).get(npcId).isEmpty() || leader == null) return false;
        Follow f = FOLLOWS.get(npcId);
        if (f == null) {
            f = new Follow();
            f.slot = freeSlot(leader);
            FOLLOWS.put(npcId, f);
            WALKS.remove(npcId);
            Npcs.setAnchored(server, npcId, false);
            f.lastProgress = TICKS;
        }
        if (f.leader != leader) {
            // A new leader (a party member took over, or the same player respawned as a new object): a fresh trail.
            if (f.leader != null && !f.leader.getUUID().equals(leader.getUUID())) f.crumbs.clear();
            f.leader = leader;
            f.slot = freeSlot(leader); // a different leader's other followers may already hold this one
        }
        f.expires = TICKS + Math.max(20, leaseTicks);
        return true;
    }

    public static void stopFollowing(MinecraftServer server, UUID npcId) {
        if (FOLLOWS.remove(npcId) != null) settle(server, npcId);
    }

    public static Optional<UUID> leaderOf(UUID npcId) {
        Follow f = FOLLOWS.get(npcId);
        return f == null || f.leader == null ? Optional.empty() : Optional.of(f.leader.getUUID());
    }

    /** Walk to a point at a walking pace, then stand there, anchored. False for an unknown NPC. */
    public static boolean walkTo(MinecraftServer server, UUID npcId, Vec3 target) {
        if (NpcStore.get(server).get(npcId).isEmpty()) return false;
        FOLLOWS.remove(npcId);
        startWalk(server, npcId, target, CastConfig.WALK_SPEED.get(), 1.0D, () -> settle(server, npcId), 20 * 60);
        return true;
    }

    /** Is it following or walking right now? A moving phantom is not dropped by gravity or turned to look. */
    public static boolean isMoving(UUID npcId) {
        return FOLLOWS.containsKey(npcId) || WALKS.containsKey(npcId);
    }

    public static boolean isFollowing(UUID npcId) {
        return FOLLOWS.containsKey(npcId);
    }

    private static void startWalk(MinecraftServer server, UUID npcId, Vec3 target, double speed, double mobSpeed, Runnable onArrive, int maxTicks) {
        Npcs.setAnchored(server, npcId, false);
        WALKS.put(npcId, new Walk(target, speed, mobSpeed, onArrive, TICKS + maxTicks));
    }

    /** Stop moving and hold this spot. */
    private static void settle(MinecraftServer server, UUID npcId) {
        NpcStore.get(server).get(npcId).ifPresent(spec -> {
            ServerLevel level = Npcs.levelOf(server, spec);
            if (level != null && spec.kind() == NpcKind.MOB) Npcs.bodyOf(level, spec).ifPresent(m -> m.getNavigation().stop());
        });
        Npcs.setAnchored(server, npcId, true);
    }

    // --- the tick: every server tick, for movers only ---

    public static void tick(MinecraftServer server) {
        long now = ++TICKS;
        if (!LATER.isEmpty()) {
            List<Later> due = new ArrayList<>();
            for (Iterator<Later> it = LATER.iterator(); it.hasNext();) {
                Later l = it.next();
                if (now >= l.at()) { due.add(l); it.remove(); }
            }
            for (Later l : due) safely(l.run());
        }
        if (FOLLOWS.isEmpty() && WALKS.isEmpty()) return;
        NpcStore store = NpcStore.get(server);
        for (UUID id : List.copyOf(FOLLOWS.keySet())) {
            Follow f = FOLLOWS.get(id);
            Optional<NpcSpec> spec = store.get(id);
            if (spec.isEmpty()) { FOLLOWS.remove(id); continue; }
            if (now > f.expires) { stopFollowing(server, id); continue; }
            try { tickFollow(server, spec.get(), f, now); }
            catch (RuntimeException e) { CastMod.LOGGER.error("Cast: following NPC {} threw; it stops", id, e); FOLLOWS.remove(id); }
        }
        for (UUID id : List.copyOf(WALKS.keySet())) {
            Walk w = WALKS.get(id);
            Optional<NpcSpec> spec = store.get(id);
            if (spec.isEmpty()) { WALKS.remove(id); continue; }
            try { tickWalk(server, spec.get(), w, now); }
            catch (RuntimeException e) { CastMod.LOGGER.error("Cast: walking NPC {} threw; it stops", id, e); WALKS.remove(id); }
        }
    }

    /** The lowest slot not already held by one of this leader's other followers. */
    private static int freeSlot(ServerPlayer leader) {
        java.util.BitSet taken = new java.util.BitSet();
        for (Follow other : FOLLOWS.values()) if (other.leader == leader) taken.set(other.slot);
        return taken.nextClearBit(0);
    }

    /**
     * A small, stable displacement from the leader for this one to aim at, so two followers of the
     * same leader do not converge on literally the same point -- reported in play as two NPCs
     * standing exactly on top of each other, faces flickering between them. Slot 0/1 sit either
     * side of the leader's own heading, 2/3 a step further out, and so on.
     */
    private static Vec3 lateralOffset(Follow f, ServerPlayer leader) {
        if (f.slot == 0) return Vec3.ZERO; // the first follower gets the plain spot; only the rest step aside
        int side = f.slot % 2 == 1 ? -1 : 1;
        int rank = (f.slot + 1) / 2;
        double rad = Math.toRadians(leader.getYRot() + 90.0F);
        double dist = rank * 1.4D;
        return new Vec3(-Math.sin(rad) * side * dist, 0, Math.cos(rad) * side * dist);
    }

    /** A per-follower pace, so two NPCs walking the same crumb queue do not stay perfectly in step. */
    private static double paceOf(NpcSpec spec, double base) {
        int h = spec.id().hashCode();
        return base * (0.92D + (Math.floorMod(h, 17)) / 100.0D); // 0.92 .. 1.08
    }

    private static void tickFollow(MinecraftServer server, NpcSpec spec, Follow f, long now) {
        ServerPlayer leader = f.leader;
        ServerLevel level = Npcs.levelOf(server, spec);
        if (level == null || leader.isRemoved() || leader.level() != level) return; // wait where we are
        Vec3 feet = leader.position();
        Vec3 anchor = feet.add(lateralOffset(f, leader));
        Vec3 last = f.crumbs.peekLast();
        if ((leader.onGround() || leader.isInWater() || leader.onClimbable()) && (last == null || last.distanceToSqr(feet) >= CRUMB * CRUMB)) {
            f.crumbs.addLast(feet);
            if (f.crumbs.size() > MAX_CRUMBS) f.crumbs.pollFirst();
        }
        Vec3 at = position(spec);
        double toLeader = at.distanceTo(anchor);
        double teleport = CastConfig.FOLLOW_TELEPORT.get();
        if (teleport > 0 && toLeader > teleport && !f.crumbs.isEmpty()) {
            // Too far behind to catch up on foot: appear a few steps back along the trail, as a tamed wolf would.
            Vec3 behind = crumbBehind(f, 3);
            Npcs.drive(server, spec.id(), behind, spec.yaw(), 0F);
            f.crumbs.clear();
            f.crumbs.addLast(feet);
            return;
        }
        if (toLeader <= KEEP) {
            // Close enough: drop the crumbs we have already passed, and stand.
            while (f.crumbs.size() > 1 && f.crumbs.peekFirst().distanceToSqr(at) < 4.0D) f.crumbs.pollFirst();
            if (spec.kind() == NpcKind.MOB) Npcs.bodyOf(level, spec).ifPresent(m -> m.getNavigation().stop());
            f.bestDistance = toLeader;
            f.lastProgress = now;
            return;
        }
        if (toLeader < f.bestDistance - 0.5D) { f.bestDistance = toLeader; f.lastProgress = now; }
        // Lagging far behind on the trail: pick up the pace.
        double speed = paceOf(spec, CastConfig.WALK_SPEED.get() * (f.crumbs.size() > 12 ? 1.6D : 1.0D));
        if (spec.kind() == NpcKind.HUMAN) {
            HumanNpc h = Npcs.human(spec.id());
            if (h == null) return;
            double budget = speed;
            while (budget > 0 && !f.crumbs.isEmpty()) {
                Vec3 next = f.crumbs.peekFirst().add(lateralOffset(f, leader));
                double d = h.position().distanceTo(next);
                if (d <= budget) {
                    stepHuman(server, level, h, spec.id(), next, d, false);
                    f.crumbs.pollFirst();
                    budget -= Math.max(d, 0.05D);
                    if (h.position().distanceTo(anchor) <= KEEP) break;
                } else {
                    stepHuman(server, level, h, spec.id(), next, budget, false);
                    budget = 0;
                }
            }
            return;
        }
        Npcs.bodyOf(level, spec).ifPresent(mob -> {
            if (now % 10 == 0) mob.getNavigation().moveTo(anchor.x, anchor.y, anchor.z, 1.25D);
            // Stuck (a fence, a gap it will not jump): take the next step of the trail by hand.
            if (now - f.lastProgress > 60 && !f.crumbs.isEmpty()) {
                Vec3 hop = f.crumbs.pollFirst().add(lateralOffset(f, leader));
                mob.snapTo(hop.x, hop.y, hop.z, mob.getYRot(), mob.getXRot());
                mob.setDeltaMovement(Vec3.ZERO);
                f.lastProgress = now - 50; // one hop every ten ticks until it is moving again
            }
            NpcStore.get(server).put(spec.withPose(spec.dimension(), mob.position(), mob.getYRot(), mob.getXRot()));
        });
    }

    private static Vec3 crumbBehind(Follow f, int back) {
        Vec3[] all = f.crumbs.toArray(new Vec3[0]);
        return all[Math.max(0, all.length - 1 - back)];
    }

    private static void tickWalk(MinecraftServer server, NpcSpec spec, Walk w, long now) {
        ServerLevel level = Npcs.levelOf(server, spec);
        if (level == null) { WALKS.remove(spec.id()); return; }
        Vec3 at = position(spec);
        boolean arrived = horizontal(at, w.target()) <= (spec.kind() == NpcKind.MOB ? 1.0D : 0.2D);
        if (!arrived && now > w.giveUpAt()) {
            // Could not get there: be there, rather than stand in a corner forever mid-scene.
            Npcs.drive(server, spec.id(), w.target(), spec.yaw(), 0F);
            arrived = true;
        }
        if (arrived) {
            WALKS.remove(spec.id());
            safely(w.onArrive());
            return;
        }
        if (spec.kind() == NpcKind.HUMAN) {
            HumanNpc h = Npcs.human(spec.id());
            if (h != null) stepHuman(server, level, h, spec.id(), w.target(), w.speed(), true);
            return;
        }
        Npcs.bodyOf(level, spec).ifPresent(mob -> {
            if (mob.getNavigation().isDone() || now % 20 == 0) mob.getNavigation().moveTo(w.target().x, w.target().y, w.target().z, w.mobSpeed());
            NpcStore.get(server).put(spec.withPose(spec.dimension(), mob.position(), mob.getYRot(), mob.getXRot()));
        });
    }

    /**
     * Move a phantom up to {@code speed} towards {@code target}, facing where it goes. A trail step
     * keeps the crumb's height (the player stood there); a free walk hugs the ground, climbing a
     * block or dropping to the floor, so a straight line across a room does not float or sink.
     */
    private static void stepHuman(MinecraftServer server, ServerLevel level, HumanNpc h, UUID npcId, Vec3 target, double speed, boolean hugGround) {
        Vec3 from = h.position();
        Vec3 d = target.subtract(from);
        double len = d.length();
        Vec3 to = len <= speed ? target : from.add(d.scale(speed / len));
        if (hugGround) {
            Vec3 up = Gravity.unbury(level, to);
            // One block is a step, climbed. Taller is a wall, and a phantom has no body to be stopped by:
            // it keeps its height and goes through, rather than freezing in front of it for a minute.
            to = up.y - to.y > 1.01D ? to : Gravity.landing(level, up);
        }
        float yaw = Math.abs(d.x) + Math.abs(d.z) > 0.01D ? (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0D) : h.getYRot();
        h.snapTo(to.x, to.y, to.z, yaw, 0F);
        h.setYHeadRot(yaw);
        Phantoms.broadcastMove(level, h, from);
        NpcStore store = NpcStore.get(server);
        Vec3 landed = to;
        store.get(npcId).ifPresent(s -> store.put(s.withPose(s.dimension(), landed, yaw, 0F)));
    }

    /** Where the NPC actually is: the live body when there is one, else the spec. */
    private static Vec3 position(NpcSpec spec) {
        if (spec.kind() == NpcKind.HUMAN) {
            HumanNpc h = Npcs.human(spec.id());
            return h == null ? spec.pos() : h.position();
        }
        return Npcs.cachedBody(spec.id()).map(Mob::position).orElse(spec.pos());
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x, dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // --- lurking ---

    private enum Phase { HIDING, RUSHING, AT_DOOR, SLINKING }

    private static final class Lurk {
        Phase phase = Phase.HIDING;
        long nextRush;
    }

    /** Once a second from the NPC tick, for a loaded NPC with a lurk. */
    public static void lurkSecond(MinecraftServer server, NpcSpec spec) {
        LurkSpec l = spec.lurk().orElse(null);
        if (l == null) { LURKS.remove(spec.id()); return; }
        if (FOLLOWS.containsKey(spec.id())) return; // someone is leading it out: the cell can wait
        ServerLevel level = Npcs.levelOf(server, spec);
        if (level == null) return;
        long now = TICKS;
        Lurk state = LURKS.computeIfAbsent(spec.id(), k -> {
            Lurk s = new Lurk();
            s.nextRush = now + jitter(level, l.every());
            return s;
        });
        if (state.phase != Phase.HIDING) return;
        Vec3 at = position(spec);
        ServerPlayer near = nearest(level, l.home(), l.radius());
        if (near == null) {
            // Nobody to frighten. If a restart or a shove left it out of its corner, back into the dark.
            if (horizontal(at, l.home()) > 1.5D && !WALKS.containsKey(spec.id())) slink(server, spec.id(), l, state);
            return;
        }
        var rng = level.getRandom();
        if (rng.nextFloat() < CastConfig.LURK_GROWL_CHANCE.get()) {
            play(level, at, l.growl(), SoundEvents.ZOMBIE_AMBIENT, 1.0F, 0.6F + rng.nextFloat() * 0.3F);
        }
        if (now >= state.nextRush && rng.nextFloat() < 0.35F) rush(server, spec.id(), l, state);
    }

    /** Make a lurker rush the door now -- the admin's test button, and the API's. False when it is not lurking. */
    public static boolean scare(MinecraftServer server, UUID npcId) {
        Optional<NpcSpec> spec = NpcStore.get(server).get(npcId);
        if (spec.isEmpty() || spec.get().lurk().isEmpty()) return false;
        Lurk state = LURKS.computeIfAbsent(npcId, k -> new Lurk());
        rush(server, npcId, spec.get().lurk().get(), state);
        return true;
    }

    private static void rush(MinecraftServer server, UUID npcId, LurkSpec l, Lurk state) {
        state.phase = Phase.RUSHING;
        startWalk(server, npcId, l.door(), CastConfig.RUSH_SPEED.get(), 2.2D, () -> hammer(server, npcId, l, state), 20 * 5);
    }

    /** At the door: three blows, the last the loudest, then a moment's stare before it goes back. */
    private static void hammer(MinecraftServer server, UUID npcId, LurkSpec l, Lurk state) {
        state.phase = Phase.AT_DOOR;
        long now = TICKS;
        for (int n = 0; n < 3; n++) {
            final float volume = n == 2 ? 2.5F : 1.8F;
            LATER.add(new Later(now + n * 9L, () -> NpcStore.get(server).get(npcId).ifPresent(spec -> {
                ServerLevel level = Npcs.levelOf(server, spec);
                if (level != null) play(level, position(spec), l.thump(), SoundEvents.ZOMBIE_ATTACK_WOODEN_DOOR, volume, 0.8F + level.getRandom().nextFloat() * 0.2F);
            })));
        }
        LATER.add(new Later(now + 40L, () -> slink(server, npcId, l, state)));
    }

    private static void slink(MinecraftServer server, UUID npcId, LurkSpec l, Lurk state) {
        state.phase = Phase.SLINKING;
        startWalk(server, npcId, l.home(), CastConfig.WALK_SPEED.get() * 0.5D, 0.7D, () -> {
            settle(server, npcId);
            state.phase = Phase.HIDING;
            NpcStore.get(server).get(npcId).map(s -> Npcs.levelOf(server, s)).ifPresent(level ->
                    state.nextRush = TICKS + jitter(level, l.every()));
        }, 20 * 15);
    }

    private static long jitter(ServerLevel level, int everySeconds) {
        return (long) (Math.max(1, everySeconds) * 20L * (0.6D + level.getRandom().nextDouble() * 0.8D));
    }

    private static ServerPlayer nearest(ServerLevel level, Vec3 at, double radius) {
        ServerPlayer best = null;
        double bestD = radius * radius;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            double d = p.distanceToSqr(at);
            if (d <= bestD) { bestD = d; best = p; }
        }
        return best;
    }

    private static void play(ServerLevel level, Vec3 at, Optional<Identifier> sound, SoundEvent fallback, float volume, float pitch) {
        SoundEvent event = sound.flatMap(id -> BuiltInRegistries.SOUND_EVENT.get(id)).map(h -> h.value()).orElse(fallback);
        level.playSound(null, at.x, at.y, at.z, event, SoundSource.HOSTILE, volume, pitch);
    }

    private static void safely(Runnable r) {
        try { r.run(); } catch (RuntimeException e) { CastMod.LOGGER.error("Cast: a motion callback threw", e); }
    }

    /** What is lurking and in which phase, for the self-test and /cast status. */
    public static String lurkPhase(UUID npcId) {
        Lurk l = LURKS.get(npcId);
        return l == null ? "none" : l.phase.name().toLowerCase(java.util.Locale.ROOT);
    }

    public static void forget(UUID npcId) {
        FOLLOWS.remove(npcId);
        WALKS.remove(npcId);
        LURKS.remove(npcId);
    }

    public static void clear() {
        FOLLOWS.clear();
        WALKS.clear();
        LURKS.clear();
        LATER.clear();
    }

    private Motion() {}
}
