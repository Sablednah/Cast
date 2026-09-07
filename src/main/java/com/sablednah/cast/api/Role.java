package com.sablednah.cast.api;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

/** What an NPC does when right-clicked. Register with {@link Cast#registerRole}. */
@FunctionalInterface
public interface Role {
    /** @return true if handled -- later roles on the NPC are not asked */
    boolean onInteract(ServerPlayer player, Npc npc, InteractionHand hand);
}
