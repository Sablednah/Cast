package com.sablednah.cast.api;

import java.util.UUID;

import net.neoforged.bus.api.Event;

/**
 * An NPC's body has gone -- from a kill, a chunk unload, a dimension change,
 * or a removal. Fired on the server thread, on the game bus, so a mod holding
 * a camera on it can let go on every path.
 */
public class NpcRemovedEvent extends Event {

    /**
     * DEATH a body was killed; UNLOAD its chunk went (or the server is
     * stopping); DIMENSION_CHANGE the body or a pinned viewer changed level;
     * REMOVED an explicit removal; REBODY the phantom was rebuilt (a skin or
     * name arrived) -- the old entity id is gone from every client.
     */
    public enum Reason { DEATH, UNLOAD, DIMENSION_CHANGE, REMOVED, REBODY }

    private final UUID npcId;
    private final Reason reason;

    public NpcRemovedEvent(UUID npcId, Reason reason) {
        this.npcId = npcId;
        this.reason = reason;
    }

    public UUID npcId() { return npcId; }

    public Reason reason() { return reason; }
}
