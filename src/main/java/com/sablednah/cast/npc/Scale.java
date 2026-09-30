package com.sablednah.cast.npc;

import com.sablednah.cast.core.NpcSpec;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * An NPC's size: vanilla's own {@code minecraft:scale} attribute, so the model, hitbox, eye height
 * (what a look-at aims from, what a click reaches) and nameplate all follow with nothing of ours to
 * keep in step. A mob body's tracker syncs it; a phantom has no tracker, so {@link Phantoms#show}
 * sends it (a change re-bodies the phantom, which shows it again).
 */
public final class Scale {

    /** Set it on a body if it differs. Cheap enough for the per-second re-assert. */
    public static void apply(LivingEntity body, double scale) {
        var attr = body.getAttribute(Attributes.SCALE);
        double want = NpcSpec.clampScale(scale);
        if (attr == null || attr.getBaseValue() == want) return;
        attr.setBaseValue(want);
        // Vanilla resizes on a dirty attribute from the entity's own tick; a phantom is never ticked that
        // way, so without this its hitbox and eye height stayed ordinary (the self-test caught it).
        body.refreshDimensions();
    }

    private Scale() {}
}
