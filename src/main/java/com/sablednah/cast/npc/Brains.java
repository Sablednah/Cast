package com.sablednah.cast.npc;

import net.minecraft.world.entity.Mob;

/**
 * Is this creature driven by a Brain rather than by goals?
 *
 * <p>Goal flags only arbitrate between goals; a Brain ticks in
 * {@code customServerAiStep} and never asks them. So the holder-goal trick
 * that parks a zombie is a silent no-op on a villager, and a brain body
 * needs its <em>behaviours</em> removed instead.</p>
 *
 * <p>Vanilla answers the question: every LivingEntity has a Brain (the base
 * class supplies an empty one, so presence means nothing), and
 * {@code Brain.isBrainDead()} is true exactly when memories, sensors and
 * behaviours are all empty -- a goal mob. It is a "born with a brain"
 * classifier: after {@link #neutralise} the memories and sensors remain, so
 * the answer does not change, which is what the once-a-second re-assert
 * relies on. Maintained by Mojang, so the 20-class list in the self-test is
 * a fixture, never something the runtime depends on.</p>
 */
public final class Brains {

    public static boolean isBrainDriven(Mob mob) {
        return !mob.getBrain().isBrainDead();
    }

    /**
     * Take the behaviours off a brain body. Memories stay registered, so
     * vanilla code that reads them keeps working. {@code removeAllBehaviors}
     * is marked visible-for-testing upstream: public, works, and watched by the
     * self-test so a version drop says so before a player does.
     */
    public static void neutralise(Mob mob) {
        if (isBrainDriven(mob)) mob.getBrain().removeAllBehaviors();
    }

    private Brains() {}
}
