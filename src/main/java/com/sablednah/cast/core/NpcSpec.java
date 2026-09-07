package com.sablednah.cast.core;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;

/**
 * Everything Cast needs to put an NPC back in the world. The entity is
 * disposable; this is the identity.
 *
 * @param id         the npcId: stable across restarts, chunk loads and bodies
 * @param kind       human or mob
 * @param name       what is written above it (a human's is trimmed to 16 for the profile)
 * @param skin       for a human, the real account whose skin it wears
 * @param entityType for a mob, which creature
 * @param dimension  where it stands
 * @param pos        where it stands
 * @param yaw        which way it faces at rest
 * @param pitch      ... and how far up or down
 * @param roles      what right-clicking it does, dispatched in order
 * @param lookAtPlayers turn towards a nearby player
 * @param entityUuid for a mob, the live body's UUID once spawned
 */
public record NpcSpec(UUID id, NpcKind kind, String name, Optional<String> skin, Optional<Identifier> entityType,
        Identifier dimension, Vec3 pos, float yaw, float pitch, List<Identifier> roles, boolean lookAtPlayers,
        Optional<UUID> entityUuid) {

    public static final Codec<NpcSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            UUIDUtil.CODEC.fieldOf("id").forGetter(NpcSpec::id),
            NpcKind.CODEC.fieldOf("kind").forGetter(NpcSpec::kind),
            Codec.STRING.fieldOf("name").forGetter(NpcSpec::name),
            Codec.STRING.optionalFieldOf("skin").forGetter(NpcSpec::skin),
            Identifier.CODEC.optionalFieldOf("entity_type").forGetter(NpcSpec::entityType),
            Identifier.CODEC.fieldOf("dimension").forGetter(NpcSpec::dimension),
            Vec3.CODEC.fieldOf("pos").forGetter(NpcSpec::pos),
            Codec.FLOAT.optionalFieldOf("yaw", 0F).forGetter(NpcSpec::yaw),
            Codec.FLOAT.optionalFieldOf("pitch", 0F).forGetter(NpcSpec::pitch),
            Identifier.CODEC.listOf().optionalFieldOf("roles", List.of()).forGetter(NpcSpec::roles),
            Codec.BOOL.optionalFieldOf("look_at_players", true).forGetter(NpcSpec::lookAtPlayers),
            UUIDUtil.CODEC.optionalFieldOf("entity_uuid").forGetter(NpcSpec::entityUuid))
            .apply(i, NpcSpec::new));

    public NpcSpec withName(String n) {
        return new NpcSpec(id, kind, n, skin, entityType, dimension, pos, yaw, pitch, roles, lookAtPlayers, entityUuid);
    }

    public NpcSpec withSkin(Optional<String> s) {
        return new NpcSpec(id, kind, name, s, entityType, dimension, pos, yaw, pitch, roles, lookAtPlayers, entityUuid);
    }

    public NpcSpec withPose(Identifier dim, Vec3 p, float y, float x) {
        return new NpcSpec(id, kind, name, skin, entityType, dim, p, y, x, roles, lookAtPlayers, entityUuid);
    }

    public NpcSpec withRoles(List<Identifier> r) {
        return new NpcSpec(id, kind, name, skin, entityType, dimension, pos, yaw, pitch, r, lookAtPlayers, entityUuid);
    }

    public NpcSpec withLook(boolean look) {
        return new NpcSpec(id, kind, name, skin, entityType, dimension, pos, yaw, pitch, roles, look, entityUuid);
    }

    public NpcSpec withEntityUuid(Optional<UUID> u) {
        return new NpcSpec(id, kind, name, skin, entityType, dimension, pos, yaw, pitch, roles, lookAtPlayers, u);
    }

    /** The profile name a client will accept: at most 16 characters. */
    public String profileName() {
        String plain = name.replaceAll("[&§].", "");
        return plain.length() <= 16 ? plain : plain.substring(0, 16);
    }
}
