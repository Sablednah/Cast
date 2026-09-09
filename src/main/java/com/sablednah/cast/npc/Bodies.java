package com.sablednah.cast.npc;

import java.util.Optional;

import com.sablednah.cast.CastConfig;
import com.sablednah.cast.CastMod;
import com.sablednah.cast.api.Cast;
import com.sablednah.cast.core.NpcSpec;
import com.sablednah.cast.neoforge.Feedback;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;

/**
 * Mob bodies: real creatures Cast owns from spawn. Ownership is what lets
 * the goal rebuild be one-way -- there is no "before" to restore. A body is a
 * normal saved entity, so it survives in its chunk; the spec remembers its
 * UUID and re-spawns one if it is gone.
 */
public final class Bodies {

    /** Spawn a fresh body for a spec. Returns empty if the type is unknown or not a Mob. */
    public static Optional<Mob> spawn(ServerLevel level, NpcSpec spec) {
        var type = spec.entityType().flatMap(id -> BuiltInRegistries.ENTITY_TYPE.get(id));
        if (type.isEmpty()) {
            CastMod.LOGGER.warn("Cast: NPC {} names unknown entity type {}", spec.id(), spec.entityType().orElse(null));
            return Optional.empty();
        }
        EntityType<?> t = type.get().value();
        Entity created = t.create(level, EntitySpawnReason.COMMAND); // COMMAND: ZombieMod's natural roll never touches it
        if (!(created instanceof Mob mob)) {
            CastMod.LOGGER.warn("Cast: NPC {} entity type {} is not a mob", spec.id(), spec.entityType().orElse(null));
            return Optional.empty();
        }
        mob.snapTo(spec.pos().x, spec.pos().y, spec.pos().z, spec.yaw(), spec.pitch());
        mob.setYHeadRot(spec.yaw());
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(BlockPos.containing(spec.pos())), EntitySpawnReason.COMMAND, null);
        configure(mob, spec);
        level.addFreshEntity(mob);
        return Optional.of(mob);
    }

    /** Make (or re-make) a body ours. Idempotent; called at spawn and re-asserted while loaded. */
    public static void configure(Mob mob, NpcSpec spec) {
        mob.getPersistentData().putString(Cast.MARKER, spec.id().toString());
        mob.setCustomName(Feedback.colored(spec.name()));
        mob.setCustomNameVisible(true);
        mob.setPersistenceRequired();
        Equipment.apply(mob, spec);
        mob.setInvulnerable(true);
        mob.setNoAi(false);
        var kb = mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE);
        if (kb != null) kb.setBaseValue(1.0D);
        // Ours from here: clear whatever the body was born with, then the idle set.
        mob.goalSelector.removeAllGoals(g -> true);
        mob.targetSelector.removeAllGoals(g -> true);
        if (spec.lookAtPlayers()) {
            mob.goalSelector.addGoal(1, new LookAtPlayerGoal(mob, Player.class, CastConfig.LOOK_RANGE.get().floatValue()));
        }
        mob.goalSelector.addGoal(2, new RandomLookAroundGoal(mob));
        Brains.neutralise(mob);
    }

    /**
     * The cheap per-second re-assert: a brain regrows behaviours on refresh, a
     * command can heal, and a zombie can shove. A body that has drifted more
     * than a block from its spot is put back -- it keeps its physical presence
     * (players bump into it) without being herded around the map.
     */
    public static void maintain(Mob mob, NpcSpec spec, boolean anchored) {
        maintain(mob, spec, anchored, null);
    }

    /**
     * Keep a body where it belongs. Anchored, a shove is undone -- but a fall is
     * not: straight down from the anchor with nothing under it, the body lands
     * (vanilla physics) and {@code adopt} is told the new spot, unless the spec
     * defies gravity, in which case it is hauled back up like any other shove.
     */
    public static void maintain(Mob mob, NpcSpec spec, boolean anchored, java.util.function.Consumer<net.minecraft.world.phys.Vec3> adopt) {
        Brains.neutralise(mob);
        if (!mob.isInvulnerable()) mob.setInvulnerable(true);
        // An anchored body is held up by us, not by the ground, so the gag can play before it drops;
        // let go (possessed, walked about) and vanilla gravity is back.
        if (!anchored) { mob.setNoGravity(false); Gravity.settle(spec.id()); return; }
        if (Gravity.landingAfterRelease(spec.id()) && !spec.defyGravity()) {
            // Re-anchored after a release (possession ended, a behaviour stopped). Vanilla gravity keeps it
            // until it lands: a body let go of mid-air falls, and where it lands is home. Only then do
            // we hold it up ourselves. (Otherwise a rooftop release pinned a villager to the sky.)
            mob.setNoGravity(false);
            if (!mob.onGround()) return;
            if (adopt != null && mob.distanceToSqr(spec.pos()) > 0.01D) adopt.accept(mob.position());
            Gravity.landed(spec.id());
        }
        mob.setNoGravity(true);
        if (!spec.defyGravity()) {
            net.minecraft.world.phys.Vec3 landing = Gravity.landing(mob.level() instanceof net.minecraft.server.level.ServerLevel sl ? sl : null, spec.pos());
            if (landing.y < spec.pos().y) {
                if (Gravity.coyote(spec.id(),
                        () -> mob.getLookControl().setLookAt(mob.getX(), mob.getY() - 3, mob.getZ()),
                        () -> mob.getLookControl().setLookAt(mob.getX(), mob.getEyeY() + 2, mob.getZ()))) {
                    mob.snapTo(landing.x, landing.y, landing.z, mob.getYRot(), 0F);
                    mob.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
                    if (adopt != null) adopt.accept(landing);
                }
                return;
            }
            Gravity.settle(spec.id());
        }
        if (mob.distanceToSqr(spec.pos()) <= 1.0D) return;
        mob.snapTo(spec.pos().x, spec.pos().y, spec.pos().z, mob.getYRot(), mob.getXRot());
        mob.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
    }

    private Bodies() {}
}
