package com.sablednah.cast;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-owner dials. Read live via {@code .get()}. */
public final class CastConfig {

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue VIEW_RANGE;
    public static final ModConfigSpec.DoubleValue LOOK_RANGE;
    public static final ModConfigSpec.BooleanValue COYOTE;
    public static final ModConfigSpec.BooleanValue FETCH_SKINS;

    static {
        BUILDER.comment("NPCs").push("npcs");
        VIEW_RANGE = BUILDER
                .comment("How far (blocks) a human NPC is sent to a player. Beyond this it is",
                        "removed from their client. Mob NPCs use the server's normal tracking.")
                .defineInRange("viewRange", 64, 16, 256);
        LOOK_RANGE = BUILDER
                .comment("How close (blocks) a player must be for an NPC to turn and look at them.")
                .defineInRange("lookRange", 8.0D, 0.0D, 32.0D);
        COYOTE = BUILDER
                .comment("When the ground goes from under an NPC: look down, look back up at you, THEN fall.",
                        "Two seconds of dawning realisation. Off, they just drop.")
                .define("coyote", true);
        BUILDER.pop();

        BUILDER.comment("Skins").push("skins");
        FETCH_SKINS = BUILDER
                .comment("Fetch signed skins from Mojang for human NPCs that name a skin account.",
                        "Off, or when the fetch fails, an NPC wears the default skin and still spawns.")
                .define("fetchSkins", true);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private CastConfig() {}
}
