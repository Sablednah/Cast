package com.sablednah.cast.npc;

import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.sablednah.cast.CastConfig;
import com.sablednah.cast.CastMod;
import com.sablednah.cast.core.NpcStore;

import net.minecraft.util.Util;
import net.minecraft.server.MinecraftServer;

/**
 * Signed skins from the session service, fetched off-thread and cached in
 * the store. A player-model skin must be signed or the client ignores it;
 * the signature covers the value, not the wearer, so any real account's
 * skin can dress an NPC.
 *
 * <p><b>Never fails a spawn.</b> A rejected, missing or slow fetch leaves the
 * NPC in the default skin and standing where it was put. An NPC that
 * sometimes does not appear is worse than one that is occasionally Steve.</p>
 */
public final class Skins {

    /**
     * A signed skin shipped as data, not fetched: {@code data/<ns>/cast/skin/<name>.json}, the value
     * and signature MineSkin returns for an uploaded PNG. Signed by Mojang and permanent, so it needs
     * no account (which could change its skin under you) and no network (so it works offline).
     */
    public record DataSkin(String value, String signature) {
        public static final com.mojang.serialization.Codec<DataSkin> CODEC =
                com.mojang.serialization.codecs.RecordCodecBuilder.create(i -> i.group(
                        com.mojang.serialization.Codec.STRING.fieldOf("value").forGetter(DataSkin::value),
                        com.mojang.serialization.Codec.STRING.fieldOf("signature").forGetter(DataSkin::signature))
                        .apply(i, DataSkin::new));
    }

    /** Skins a mod registered in code ({@code Cast.registerSkin}); a datapack's file of the same id wins. */
    private static final java.util.Map<net.minecraft.resources.Identifier, DataSkin> REGISTERED = new java.util.concurrent.ConcurrentHashMap<>();
    /** Namespaced skins already warned about, so a missing file is said once, not every rebody. */
    private static final java.util.Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public static void register(net.minecraft.resources.Identifier id, DataSkin skin) {
        REGISTERED.put(id, skin);
    }

    /** {@code "zarp:okafor"} is a shipped skin; {@code "Notch"} is an account (account names cannot hold a colon). */
    public static boolean isShipped(String name) {
        return name.indexOf(':') >= 0;
    }

    /**
     * What an NPC naming {@code name} wears: a shipped skin (datapack, then code) for a namespaced
     * name, else the account skin already fetched into the store. Empty means the default skin.
     */
    public static Optional<NpcStore.Skin> resolve(MinecraftServer server, NpcStore store, String name) {
        if (!isShipped(name)) return store.skin(name);
        var id = net.minecraft.resources.Identifier.tryParse(name.trim().toLowerCase(java.util.Locale.ROOT));
        if (id == null) return Optional.empty();
        Optional<DataSkin> data = server.registryAccess().lookup(com.sablednah.cast.CastRegistries.SKIN)
                .flatMap(r -> r.getOptional(id));
        if (data.isEmpty()) data = Optional.ofNullable(REGISTERED.get(id));
        return data.map(d -> new NpcStore.Skin(new UUID(0L, 0L), d.value(), d.signature(), 0L));
    }

    /** Fetch if not cached; on success, tell the manager so the phantom is re-dressed. */
    public static void ensure(MinecraftServer server, String account, UUID npcId) {
        if (isShipped(account)) {
            // Shipped skins are never fetched: there is no account to ask. Missing is said once, with where it should be.
            if (resolve(server, NpcStore.get(server), account).isEmpty() && WARNED.add(account)) {
                var id = net.minecraft.resources.Identifier.tryParse(account.trim().toLowerCase(java.util.Locale.ROOT));
                CastMod.LOGGER.warn("Cast: no skin '{}' -- ship it as data/{}/cast/skin/{}.json (value + signature, as MineSkin gives them); "
                        + "NPC {} keeps the default skin until then", account,
                        id == null ? "<ns>" : id.getNamespace(), id == null ? "<name>" : id.getPath(), npcId);
            }
            return;
        }
        if (!CastConfig.FETCH_SKINS.get()) return;
        NpcStore store = NpcStore.get(server);
        if (store.skin(account).isPresent()) return;
        Util.backgroundExecutor().execute(() -> {
            Optional<NpcStore.Skin> fetched = fetch(server, account);
            server.execute(() -> {
                if (fetched.isEmpty()) {
                    CastMod.LOGGER.warn("Cast: no signed skin for account '{}' -- NPC {} keeps the default skin", account, npcId);
                    return;
                }
                NpcStore.get(server).putSkin(account, fetched.get());
                Npcs.rebody(server, npcId);
            });
        });
    }

    private static Optional<NpcStore.Skin> fetch(MinecraftServer server, String account) {
        try {
            var id = server.services().profileRepository().findProfileByName(account);
            if (id.isEmpty()) return Optional.empty();
            ProfileResult result = server.services().sessionService().fetchProfile(id.get().id(), true);
            if (result == null) return Optional.empty();
            for (Property p : result.profile().properties().get("textures")) {
                if (p.signature() != null && !p.signature().isEmpty()) {
                    return Optional.of(new NpcStore.Skin(id.get().id(), p.value(), p.signature(), System.currentTimeMillis()));
                }
            }
            return Optional.empty();
        } catch (RuntimeException e) {
            CastMod.LOGGER.warn("Cast: skin fetch for '{}' failed: {}", account, e.toString());
            return Optional.empty();
        }
    }

    private Skins() {}
}
