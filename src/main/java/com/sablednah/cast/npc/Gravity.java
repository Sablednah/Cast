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

    private Gravity() {}
}
