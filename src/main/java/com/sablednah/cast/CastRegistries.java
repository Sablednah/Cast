package com.sablednah.cast;

import com.sablednah.cast.npc.Skins;

import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.registries.NewDatapackRegistryEvent;

/**
 * Cast's datapack registries. One so far: {@code cast:skin}, signed skins a datapack ships as
 * {@code data/<ns>/cast/skin/<name>.json} -- {@code {"value": ..., "signature": ...}}, exactly what
 * MineSkin hands back for an uploaded PNG. An NPC whose skin is {@code "<ns>:<name>"} wears it with
 * no account and no network. Frozen like every datapack registry: a new skin file needs a restart.
 */
public final class CastRegistries {

    public static final ResourceKey<Registry<Skins.DataSkin>> SKIN =
            ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(CastMod.MODID, "skin"));

    static void register(NewDatapackRegistryEvent event) {
        // No network codec: the client never needs the registry -- the phantom's profile carries the texture.
        event.worldRegistry(SKIN, Skins.DataSkin.CODEC);
    }

    private CastRegistries() {}
}
