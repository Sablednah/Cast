package com.sablednah.cast.api;

import net.minecraft.world.phys.Vec3;

/** What a look-ray hit: the NPC, where on its box, and how far from the eye -- so a caller can rank it against a wild mob. */
public record NpcHit(Npc npc, Vec3 hit, double distance) {}
