package com.sablednah.cast.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sablednah.cast.npc.Brains;
import com.sablednah.cast.npc.Npcs;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * The door for other mods. Import this package from one guarded class, behind
 * a {@code ModList.isLoaded("cast")} check that sits outside it.
 *
 * <p>Identity is the {@code npcId}, never an entity reference: bodies are
 * rebuilt on chunk load and a human phantom is not in any level. Every
 * NPC body also carries {@code cast:npc} (the npcId as a string) in its
 * persistent data -- the documented, public way to recognise one from
 * outside, including for mods that never call this class.</p>
 */
public final class Cast {

    /** The persistent-data key every mob body carries. Public contract. */
    public static final String MARKER = "cast:npc";

    // --- creating and removing ---

    /** A player-model NPC. {@code skin} names a real account whose skin it wears (fetched, cached; default skin if not). */
    public static UUID spawnHuman(ServerLevel level, Vec3 pos, float yaw, String name, Optional<String> skin, List<Identifier> roles) {
        return Npcs.spawnHuman(level, pos, yaw, name, skin, roles);
    }

    /**
     * A creature NPC. Brain-driven bodies (villagers among them) are accepted:
     * their behaviours are removed every second and ordinary look goals added.
     */
    public static UUID spawnMob(ServerLevel level, Vec3 pos, float yaw, Identifier entityType, String name, List<Identifier> roles) {
        return Npcs.spawnMob(level, pos, yaw, entityType, name, roles);
    }

    /** Idempotent; works while the body is unloaded. */
    public static boolean remove(MinecraftServer server, UUID npcId) {
        return Npcs.remove(server, npcId);
    }

    // --- finding ---

    public static Optional<Npc> byId(MinecraftServer server, UUID npcId) {
        return Npcs.handle(server, npcId);
    }

    public static List<Npc> all(MinecraftServer server) {
        return Npcs.handles(server);
    }

    public static boolean isNpc(Entity entity) {
        return Npcs.npcIdOf(entity).isPresent();
    }

    public static Optional<UUID> npcIdOf(Entity entity) {
        return Npcs.npcIdOf(entity);
    }

    /** The NPC the player is looking at within {@code reach}, either body. */
    public static Optional<Npc> npcAt(ServerPlayer viewer, double reach) {
        return npcHitAt(viewer, reach).map(NpcHit::npc);
    }

    /** As {@link #npcAt}, with where it was hit and how far -- rank it against your own ray. */
    public static Optional<NpcHit> npcHitAt(ServerPlayer viewer, double reach) {
        return Npcs.lookedAt(viewer, reach);
    }

    /**
     * Keep a phantom on this player's client whatever the range -- for the
     * whole of a possession. A camera packet names an entity id the client
     * resolves in its own level, so the phantom must stay spawned there, and
     * the ordinary out-of-range remove would eject the camera silently.
     * Unpin on release. Ignored for mob bodies, which the server tracks itself.
     */
    public static void pinViewer(ServerPlayer player, UUID npcId) {
        Npcs.pin(player, npcId);
    }

    public static void unpinViewer(ServerPlayer player, UUID npcId) {
        Npcs.unpin(player, npcId);
    }

    /** Is this creature driven by a Brain, so that goal parking would do nothing? */
    public static boolean isBrainDriven(Mob mob) {
        return Brains.isBrainDriven(mob);
    }

    // --- doing ---

    /**
     * Speak as the NPC to players within {@code radius}: a chat line styled with
     * its name. Returns how many heard it -- a line delivered to nobody is
     * something a narrator needs to know.
     */
    public static int say(MinecraftServer server, UUID npcId, String text, double radius) {
        return Npcs.say(server, npcId, text, radius);
    }

    /** Turn the NPC's face towards a point. */
    public static void lookAt(MinecraftServer server, UUID npcId, Vec3 target) {
        Npcs.lookAt(server, npcId, target);
    }

    /**
     * Move the NPC. <b>This is a teleport, and says so</b>: a human phantom has no
     * legs to path with, so the body appears at the new place. Use it for
     * scene changes, not for walking; a mob body can be walked by its own
     * navigation.
     */
    public static void drive(MinecraftServer server, UUID npcId, Vec3 pos, float yaw, float pitch) {
        Npcs.drive(server, npcId, pos, yaw, pitch);
    }

    /**
     * A body is put back on its spot once a second, so a shove does not herd it
     * away. Suspend that while you walk an NPC somewhere on purpose (a
     * possession); re-anchoring makes wherever it stands now its spot.
     */
    public static void setAnchored(MinecraftServer server, UUID npcId, boolean anchored) {
        Npcs.setAnchored(server, npcId, anchored);
    }

    /**
     * Dress an NPC: {@code slot} is mainhand / offhand / head / chest / legs / feet,
     * {@code item} an item string as {@code /give} takes it (null or blank clears the
     * slot). Vanilla clients see it on phantoms and bodies alike. False when the slot
     * or the item is wrong, with the reason logged.
     */
    public static boolean equip(MinecraftServer server, UUID npcId, String slot, String item) {
        return Npcs.equip(server, npcId, slot, item);
    }

    /** What an NPC is wearing, by slot name. */
    public static java.util.Map<String, String> equipment(MinecraftServer server, UUID npcId) {
        return com.sablednah.cast.core.NpcStore.get(server).get(npcId).map(com.sablednah.cast.core.NpcSpec::equipment).orElse(java.util.Map.of());
    }

    /**
     * Named for the laugh, kept for the use: an NPC that defies gravity stays exactly
     * where it was put with nothing underneath. Off (the default), it drops to the
     * ground once a second and its anchor follows it down.
     */
    public static void setDefyGravity(MinecraftServer server, UUID npcId, boolean defy) {
        Npcs.setDefyGravity(server, npcId, defy);
    }

    public static void rename(MinecraftServer server, UUID npcId, String name) {
        Npcs.rename(server, npcId, name);
    }

    public static void setRoles(MinecraftServer server, UUID npcId, List<Identifier> roles) {
        Npcs.setRoles(server, npcId, roles);
    }

    // --- roles ---

    public static void registerRole(Identifier id, Role role) {
        Npcs.registerRole(id, role);
    }

    /** Stop role dispatch for one player -- a Storyteller working on an NPC, not talking to it. */
    public static void setRolesEnabled(ServerPlayer player, boolean enabled) {
        Npcs.setRolesEnabled(player, enabled);
    }

    private Cast() {}
}
