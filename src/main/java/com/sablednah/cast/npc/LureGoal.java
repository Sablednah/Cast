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
 * <p><b>Provoked, it drops the NPC for whoever just hit it.</b> The first version held the NPC
 * unconditionally, which meant a player standing in the middle of the fight swinging away was
 * also flatly ignored -- exactly backwards for an escort, where drawing the mob off the charge is
 * the point. {@link #provoke} gives a real hit priority for a short window; once that lapses with
 * no further hit, aim reverts to the NPC on its own.</p>
 *
 * <p>{@link Exposure} owns the lifecycle: attaches one per lured mob, calls {@link #aim} to
 * (re)point it, {@link #provoke} when the mob is struck, and removes it once the mob is no
 * longer near anything exposed.</p>
 */
final class LureGoal extends Goal {

    private final Mob mob;
    private LivingEntity target;
    private LivingEntity threat;
    private long threatUntil;

    LureGoal(Mob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.TARGET));
    }

    void aim(LivingEntity target) {
        this.target = target;
    }

    /** Something just hit this mob: outranks the NPC until {@code untilGameTime}, then reverts on its own. */
    void provoke(LivingEntity attacker, long untilGameTime) {
        threat = attacker;
        threatUntil = untilGameTime;
    }

    /** The NPC, unless a live threat is still within its window -- then whoever landed that hit. */
    private LivingEntity effectiveTarget() {
        if (threat != null && threat.isAlive() && mob.level().getGameTime() < threatUntil) return threat;
        return target;
    }

    /** Is this goal currently pointed at exactly this entity? {@link Exposure} uses it to find who to detach. */
    boolean aimedAt(net.minecraft.world.entity.Entity e) {
        return target == e;
    }

    @Override
    public boolean canUse() {
        LivingEntity t = effectiveTarget();
        return t != null && t.isAlive();
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void start() {
        mob.setTarget(effectiveTarget());
    }

    @Override
    public void tick() {
        // Re-asserted every tick for the same reason this goal exists at all: something else in
        // the target selector may otherwise win a tick and clear it before this next runs -- and
        // the effective target itself can change tick to tick as a threat window opens and lapses.
        LivingEntity t = effectiveTarget();
        if (mob.getTarget() != t) mob.setTarget(t);
    }

    /**
     * A framework-driven stop does not mean {@link Exposure} is done with this goal -- only its
     * own {@code targetSelector.removeGoal} is that, and the goal object is simply discarded then,
     * aim and all. The selector calls {@code stop()} on ordinary re-evaluation churn too (a rival
     * goal briefly eligible, a condition flipping and back), and clearing the aim here erased the
     * very thing the very next {@code start()} needed -- found by the threat window itself
     * triggering exactly this: {@code effectiveTarget()} changing was enough to look, to the
     * selector, like this goal's run had ended.
     */
    @Override
    public void stop() {
        if (mob.getTarget() == effectiveTarget()) mob.setTarget(null);
    }
}
