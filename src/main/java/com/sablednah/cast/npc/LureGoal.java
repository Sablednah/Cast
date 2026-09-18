package com.sablednah.cast.npc;

import java.util.EnumSet;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;

/**
 * Makes a monster keep after an exposed NPC. Not a bare {@code Mob#setTarget} from outside --
 * that is fragile precisely because the mob's OWN {@code TargetGoal} re-evaluates every tick or
 * so and, seeing a target it did not choose, clears it again before an attack goal ever notices
 * (found the hard way: setting the target every second from {@link Exposure} produced monsters
 * that visibly ignored an exposed NPC, with nothing thrown and nothing logged -- the AI was
 * simply winning the argument every time). A goal in the mob's own {@code targetSelector},
 * flagged {@link Flag#TARGET}, is arbitrated by the SAME framework the mob's other targeting
 * goals answer to, so it wins on equal terms instead of being overwritten from outside it.
 *
 * <p>{@link Exposure} owns the lifecycle: attaches one per lured mob, calls {@link #aim} to
 * (re)point it, and removes it once the mob is no longer near anything exposed.</p>
 */
final class LureGoal extends Goal {

    private final Mob mob;
    private LivingEntity target;

    LureGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.TARGET));
    }

    void aim(LivingEntity target) {
        this.target = target;
    }

    /** Is this goal currently pointed at exactly this entity? {@link Exposure} uses it to find who to detach. */
    boolean aimedAt(net.minecraft.world.entity.Entity e) {
        return target == e;
    }

    @Override
    public boolean canUse() {
        return target != null && target.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        mob.setTarget(target);
    }

    @Override
    public void tick() {
        // Re-asserted every tick for the same reason this goal exists at all: something else in
        // the target selector may otherwise win a tick and clear it before this next runs.
        if (mob.getTarget() != target) mob.setTarget(target);
    }

    @Override
    public void stop() {
        if (mob.getTarget() == target) mob.setTarget(null);
        target = null;
    }
}
