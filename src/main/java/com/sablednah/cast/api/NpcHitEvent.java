package com.sablednah.cast.api;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.Event;

/**
 * Something struck an NPC. NPCs take no damage, so this is the only trace a blow leaves:
 * a zombie's swing, an arrow, a player's punch. One blow is one event -- repeats inside
 * half a second are folded together, as vanilla's own hurt cooldown would. Fired on the
 * server thread, on the game bus. Monsters only go for an NPC someone has {@link Cast#expose exposed};
 * a player can hit any of them.
 */
public class NpcHitEvent extends Event {

    private final UUID npcId;
    private final Optional<Entity> attacker;

    public NpcHitEvent(UUID npcId, Optional<Entity> attacker) {
        this.npcId = npcId;
        this.attacker = attacker;
    }

    public UUID npcId() { return npcId; }

    /** Who swung or shot, when anyone did (a cactus did not). */
    public Optional<Entity> attacker() { return attacker; }
}
