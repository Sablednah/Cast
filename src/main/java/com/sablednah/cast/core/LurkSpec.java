package com.sablednah.cast.core;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * A lurker: an NPC that keeps to a dark spot and, now and then, rushes somewhere loud.
 * The caged zombie in the basement -- it hides at the back ({@code home}), groans when
 * someone comes within {@code radius}, and about once every {@code every} seconds while
 * someone is there it dashes to {@code door}, hammers on it, and slinks back.
 *
 * @param home   where it hides; also its anchor between scares
 * @param door   where it rushes to
 * @param radius how close a player must be before it groans or rushes
 * @param every  roughly how many seconds between rushes while someone is near (randomised either side)
 * @param growl  the sound it makes while hiding; absent, a zombie's groan
 * @param thump  the sound at the door; absent, a zombie hammering on a wooden door
 */
public record LurkSpec(Vec3 home, Vec3 door, double radius, int every, Optional<Identifier> growl, Optional<Identifier> thump) {

    public static final Codec<LurkSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Vec3.CODEC.fieldOf("home").forGetter(LurkSpec::home),
            Vec3.CODEC.fieldOf("door").forGetter(LurkSpec::door),
            Codec.DOUBLE.optionalFieldOf("radius", 8.0D).forGetter(LurkSpec::radius),
            Codec.INT.optionalFieldOf("every", 20).forGetter(LurkSpec::every),
            Identifier.CODEC.optionalFieldOf("growl").forGetter(LurkSpec::growl),
            Identifier.CODEC.optionalFieldOf("thump").forGetter(LurkSpec::thump))
            .apply(i, LurkSpec::new));

    public LurkSpec withRadius(double r) { return new LurkSpec(home, door, r, every, growl, thump); }
    public LurkSpec withEvery(int e) { return new LurkSpec(home, door, radius, e, growl, thump); }
}
