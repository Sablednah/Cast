package com.sablednah.cast.npc;

import java.util.UUID;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.sablednah.cast.CastMod;
import com.sablednah.cast.core.NpcSpec;
import com.sablednah.cast.core.NpcStore;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * The human body: a player entity that is never added to a level.
 *
 * <p>It exists to be the source of packets -- its profile (name and signed
 * skin) fills the player-info entry, its entity id and position fill the
 * spawn packet, its rotation fills the head packets. NeoForge's FakePlayer
 * supplies the dummy connection that makes a ServerPlayer constructible
 * without a client. Because it is never in a level it never ticks, never
 * counts towards sleeping, never loads a chunk and never anchors a spawn.</p>
 */
public final class HumanNpc extends FakePlayer {

    public final UUID npcId;

    private HumanNpc(ServerLevel level, GameProfile profile, UUID npcId) {
        super(level, profile);
        this.npcId = npcId;
    }

    public static HumanNpc create(ServerLevel level, NpcSpec spec, NpcStore store) {
        // A v3 UUID from the npcId: stable, and it cannot collide with a real account.
        UUID profileId = UUID.nameUUIDFromBytes(("cast:" + spec.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // authlib 9's GameProfile is immutable -- properties() cannot be put to (that was a
        // tick-loop crash in play), so the skin goes in through the constructor.
        GameProfile profile;
        var skin = spec.skin().flatMap(store::skin);
        if (skin.isPresent()) {
            try {
                profile = new GameProfile(profileId, spec.profileName(), new PropertyMap(ImmutableMultimap.of(
                        "textures", new Property("textures", skin.get().value(), skin.get().signature()))));
            } catch (RuntimeException e) {
                CastMod.LOGGER.warn("Cast: could not apply cached skin to NPC {} -- default skin ({})", spec.id(), e.toString());
                profile = new GameProfile(profileId, spec.profileName());
            }
        } else {
            profile = new GameProfile(profileId, spec.profileName());
        }
        HumanNpc npc = new HumanNpc(level, profile, spec.id());
        npc.snapTo(spec.pos().x, spec.pos().y, spec.pos().z, spec.yaw(), spec.pitch());
        npc.setYHeadRot(spec.yaw());
        return npc;
    }

    /** Never in the tab list. */
    @Override
    public boolean allowsListing() {
        return false;
    }

    /** Every skin layer on: without this a phantom is hatless and sleeveless. */
    @Override
    public boolean isModelPartShown(net.minecraft.world.entity.player.PlayerModelPart part) {
        return true;
    }
}
