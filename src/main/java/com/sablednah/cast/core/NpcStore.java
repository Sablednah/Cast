package com.sablednah.cast.core;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Every NPC, and every skin ever fetched. SavedData, world-global. The store
 * is the truth; entities are rebuilt from it. Logs its count on start so an
 * empty store is distinguishable from a lost one (26.1 moves the file).
 */
public final class NpcStore extends SavedData {

    /** A signed skin from the session service: the value and signature the client checks. */
    public record Skin(UUID account, String value, String signature, long fetchedAt) {
        static final Codec<Skin> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("account").forGetter(Skin::account),
                Codec.STRING.fieldOf("value").forGetter(Skin::value),
                Codec.STRING.fieldOf("signature").forGetter(Skin::signature),
                Codec.LONG.optionalFieldOf("fetched_at", 0L).forGetter(Skin::fetchedAt))
                .apply(i, Skin::new));
    }

    private static final Codec<NpcStore> CODEC = RecordCodecBuilder.create(i -> i.group(
            NpcSpec.CODEC.listOf().optionalFieldOf("npcs", List.of()).forGetter(s -> List.copyOf(s.npcs.values())),
            Codec.unboundedMap(Codec.STRING, Skin.CODEC).optionalFieldOf("skins", Map.of()).forGetter(s -> s.skins))
            .apply(i, NpcStore::new));

    public static final SavedDataType<NpcStore> TYPE = new SavedDataType<>(net.minecraft.resources.Identifier.fromNamespaceAndPath("cast", "npcs"), NpcStore::new, CODEC, null);

    private final Map<UUID, NpcSpec> npcs = new LinkedHashMap<>();
    private final Map<String, Skin> skins = new LinkedHashMap<>();

    public NpcStore() {}

    private NpcStore(List<NpcSpec> specs, Map<String, Skin> skins) {
        specs.forEach(s -> npcs.put(s.id(), s));
        this.skins.putAll(skins);
    }

    public static NpcStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public Optional<NpcSpec> get(UUID id) {
        return Optional.ofNullable(npcs.get(id));
    }

    public Collection<NpcSpec> all() {
        return Collections.unmodifiableCollection(npcs.values());
    }

    public void put(NpcSpec spec) {
        npcs.put(spec.id(), spec);
        setDirty();
    }

    public boolean remove(UUID id) {
        boolean had = npcs.remove(id) != null;
        if (had) setDirty();
        return had;
    }

    public int size() {
        return npcs.size();
    }

    public Optional<Skin> skin(String account) {
        return Optional.ofNullable(skins.get(account.toLowerCase(Locale.ROOT)));
    }

    public void putSkin(String account, Skin skin) {
        skins.put(account.toLowerCase(Locale.ROOT), skin);
        setDirty();
    }
}
