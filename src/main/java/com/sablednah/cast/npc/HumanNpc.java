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
        // Every outer skin layer (hat, jacket, sleeves, trousers) on. The CLIENT draws a phantom as an ordinary
        // remote player and reads this synced byte, whose default is 0 -- all layers off. Overriding
        // isModelPartShown only ever lied to the server: in play Sarge lost his beard (it is on the hat layer),
        // Okafor her glasses. Set here it is a non-default value, so show()'s entity-data packet carries it.
        getEntityData().set(DATA_PLAYER_MODE_CUSTOMISATION, ALL_LAYERS);
    }

    /** Every {@link net.minecraft.world.entity.player.PlayerModelPart}'s mask, from the enum rather than a magic 0x7F. */
    private static final byte ALL_LAYERS;
    static {
        int mask = 0;
        for (var part : net.minecraft.world.entity.player.PlayerModelPart.values()) mask |= part.getMask();
        ALL_LAYERS = (byte) mask;
    }

    /** For the self-test: the synced byte a client reads to decide which skin layers to draw. */
    public static net.minecraft.network.syncher.EntityDataAccessor<Byte> skinLayersData() {
        return DATA_PLAYER_MODE_CUSTOMISATION;
    }

    public static byte allLayers() {
        return ALL_LAYERS;
    }

    public static HumanNpc create(ServerLevel level, NpcSpec spec, NpcStore store) {
        // A v3 UUID from the npcId: stable, and it cannot collide with a real account.
        UUID profileId = UUID.nameUUIDFromBytes(("cast:" + spec.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // authlib 9's GameProfile is immutable -- properties() cannot be put to (that was a
        // tick-loop crash in play), so the skin goes in through the constructor.
        GameProfile profile;
        var skin = spec.skin().flatMap(s -> Skins.resolve(level.getServer(), store, s));
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
        Scale.apply(npc, spec.scale());
        npc.snapTo(spec.pos().x, spec.pos().y, spec.pos().z, spec.yaw(), spec.pitch());
        npc.setYHeadRot(spec.yaw());
        return npc;
    }

    /**
     * A phantom takes no damage, but a monster set on it swings, and the swing arrives here
     * directly (a mob's attack calls this, not the level). Reported, never applied.
     */
    @Override
    public boolean hurtServer(net.minecraft.server.level.ServerLevel level, net.minecraft.world.damagesource.DamageSource source, float amount) {
        Exposure.hit(this, java.util.Optional.ofNullable(source.getEntity() != null ? source.getEntity() : source.getDirectEntity()));
        return false;
    }

    /** Never in the tab list. */
    @Override
    public boolean allowsListing() {
        return false;
    }

}
