package com.sablednah.cast.npc;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * NPCs obey gravity unless told to defy it. A phantom has no physics at all
 * and a body's anchor would haul it back up, so both are dropped by hand:
 * once a second, if nothing solid is under the feet, the feet go down to the
 * first thing that is, and the anchor follows. Mine the block out from under
 * Okafor and she lands, rather than hangs.
 */
public final class Gravity {

    /** Where these feet would land: the same spot if it is already on something, else lower. Never higher. */
    public static Vec3 landing(ServerLevel level, Vec3 feet) {
        if (level == null) return feet;
        BlockPos below = BlockPos.containing(feet.x, feet.y - 0.001, feet.z);
        int y = below.getY();
        int floor = level.getMinY();
        while (y >= floor && level.getBlockState(new BlockPos(below.getX(), y, below.getZ()))
                .getCollisionShape(level, new BlockPos(below.getX(), y, below.getZ())).isEmpty()) {
            y--;
        }
        if (y < floor) return feet; // nothing all the way down: leave it, a void is not a floor
        double landY = y + 1;
        return landY < feet.y - 0.01 ? new Vec3(feet.x, landY, feet.z) : feet;
    }

    /** Who is mid-realisation, and how far along: 1 = has looked down, 2 = has looked back up. */
    private static final java.util.Map<java.util.UUID, Integer> COYOTE = new java.util.HashMap<>();

    /**
     * The gag. Called once a second while the ground is missing: the first call
     * looks down, the second looks back up, the third says "drop". Wile E. Coyote
     * never fell before he had time to think about it; neither do these.
     * Off in config, it is always "drop".
     */
    public static boolean coyote(java.util.UUID id, Runnable lookDown, Runnable lookUp) {
        if (!com.sablednah.cast.CastConfig.COYOTE.get()) return true;
        int stage = COYOTE.getOrDefault(id, 0);
        if (stage == 0) { lookDown.run(); COYOTE.put(id, 1); return false; }
        if (stage == 1) { lookUp.run(); COYOTE.put(id, 2); return false; }
        COYOTE.remove(id);
        return true;
    }

    /** Bodies re-anchored since their last landing: vanilla gravity finishes the fall before we hold them. */
    private static final java.util.Set<java.util.UUID> LANDING = new java.util.HashSet<>();

    public static void releasedToLand(java.util.UUID id) { LANDING.add(id); }
    public static boolean landingAfterRelease(java.util.UUID id) { return LANDING.contains(id); }
    public static void landed(java.util.UUID id) { LANDING.remove(id); }

    /** The ground came back (or the NPC went): nothing to realise. */
    public static void settle(java.util.UUID id) { COYOTE.remove(id); LANDING.remove(id); }

    public static boolean realising(java.util.UUID id) { return COYOTE.containsKey(id); }

    public static void clear() { COYOTE.clear(); LANDING.clear(); }

    private Gravity() {}
}
