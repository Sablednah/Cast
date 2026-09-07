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

    /** Fetch if not cached; on success, tell the manager so the phantom is re-dressed. */
    public static void ensure(MinecraftServer server, String account, UUID npcId) {
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
