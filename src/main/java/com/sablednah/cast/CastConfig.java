package com.sablednah.cast;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Server-owner dials. Read live via {@code .get()}. */
public final class CastConfig {

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue VIEW_RANGE;
    public static final ModConfigSpec.DoubleValue LOOK_RANGE;
    public static final ModConfigSpec.BooleanValue COYOTE;
    public static final ModConfigSpec.IntValue TAB_ENTRY_SECONDS;
    public static final ModConfigSpec.BooleanValue FETCH_SKINS;
    public static final ModConfigSpec.DoubleValue WALK_SPEED;
    public static final ModConfigSpec.DoubleValue RUSH_SPEED;
    public static final ModConfigSpec.DoubleValue FOLLOW_TELEPORT;
    public static final ModConfigSpec.DoubleValue LURK_GROWL_CHANCE;
    public static final ModConfigSpec.DoubleValue LURE_RADIUS;
    public static final ModConfigSpec.IntValue THREAT_SECONDS;

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
        TAB_ENTRY_SECONDS = BUILDER
                .comment("A phantom is announced to a client as a player so it renders with a skin; that entry also",
                        "puts its name in command suggestions (/tp, @-selectors). It is withdrawn this many seconds",
                        "after the phantom appears, once the client has the skin. 0 keeps it forever.")
                .defineInRange("tabEntrySeconds", 2, 0, 60);
        BUILDER.pop();

        BUILDER.comment("Moving NPCs: following, walking, lurking").push("motion");
        WALK_SPEED = BUILDER
                .comment("How far (blocks per tick) a human NPC walks. A player walks about 0.22 and sprints about 0.28.",
                        "A follower that has fallen well behind goes 1.6 times this until it catches up.")
                .defineInRange("walkSpeed", 0.22D, 0.02D, 1.0D);
        RUSH_SPEED = BUILDER
                .comment("How fast (blocks per tick) a human lurker rushes the door. Mob bodies rush on their own legs.")
                .defineInRange("rushSpeed", 0.45D, 0.05D, 2.0D);
        FOLLOW_TELEPORT = BUILDER
                .comment("A follower further than this (blocks) behind its leader appears a few steps back along the",
                        "trail, as a tamed wolf does. 0 never teleports: it walks the whole way, however long.")
                .defineInRange("followTeleport", 48.0D, 0.0D, 512.0D);
        LURK_GROWL_CHANCE = BUILDER
                .comment("Each second someone is near, the chance a hiding lurker groans.")
                .defineInRange("lurkGrowlChance", 0.3D, 0.0D, 1.0D);
        LURE_RADIUS = BUILDER
                .comment("An exposed NPC (an escort another mod has made worth attacking) draws monsters within this",
                        "many blocks. Not exposed, nothing hunts an NPC.")
                .defineInRange("lureRadius", 16.0D, 2.0D, 64.0D);
        THREAT_SECONDS = BUILDER
                .comment("A real hit on a monster currently after an exposed NPC draws its attention to whoever",
                        "landed it for this many seconds, so a player can pull it off the NPC on purpose; it",
                        "reverts to the NPC on its own once the window lapses with no further hit.")
                .defineInRange("threatSeconds", 5, 1, 60);
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
