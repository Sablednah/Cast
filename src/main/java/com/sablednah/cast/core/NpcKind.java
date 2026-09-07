package com.sablednah.cast.core;

import java.util.Locale;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

/** The two bodies. */
public enum NpcKind {
    /** A player-model phantom: packets only, never in a level. */
    HUMAN,
    /** A real vanilla creature Cast owns from spawn. */
    MOB;

    public static final Codec<NpcKind> CODEC = Codec.STRING.comapFlatMap(s -> {
        try {
            return DataResult.success(NpcKind.valueOf(s.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return DataResult.error(() -> "kind must be human or mob, not '" + s + "'");
        }
    }, k -> k.name().toLowerCase(Locale.ROOT));
}
