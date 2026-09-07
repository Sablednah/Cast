package com.sablednah.cast.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;


import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * A handle on one NPC, whichever body it wears. Snapshot fields; ask
 * {@link Cast} again rather than keeping one across ticks.
 *
 * @param entity     the live server entity: a mob body, or a human phantom (a player
 *                   entity that is never in a level -- fine for a camera, not for
 *                   {@code level.getEntity}); empty when the body is not materialised
 * @param canPossess whether a camera can be bound to it right now
 */
public record Npc(UUID id, NpcKind kind, String name, Identifier dimension, Vec3 pos, float yaw, float pitch,
        List<Identifier> roles, Optional<Entity> entity, boolean loaded, boolean canPossess) {

    public boolean isHuman() {
        return kind == NpcKind.HUMAN;
    }

    public boolean isMob() {
        return kind == NpcKind.MOB;
    }
}
