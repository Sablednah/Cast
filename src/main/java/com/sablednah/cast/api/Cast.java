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
    /**
     * Make an NPC bigger or smaller: vanilla's {@code minecraft:scale} (clamped to its 0.0625-16),
     * so model, hitbox, eye height and nameplate follow. 1 is ordinary. Kept with the NPC and
     * re-applied to every new body. False if there is no such NPC. Since 1.2.0.
     */
    public static boolean setScale(MinecraftServer server, UUID npcId, double scale) {
        return com.sablednah.cast.npc.Npcs.setScale(server, npcId, scale);
    }

    /**
     * Ship a signed skin from code: NPCs whose skin is {@code id} ("mymod:doctor") wear it, no
     * account and no network. The same as a datapack's {@code data/<ns>/cast/skin/<name>.json}, which
     * wins if both exist. {@code value} and {@code signature} are what MineSkin returns. Since 1.2.0.
     */
    public static void registerSkin(Identifier id, String value, String signature) {
        com.sablednah.cast.npc.Skins.register(id, new com.sablednah.cast.npc.Skins.DataSkin(value, signature));
    }

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
     * A one-shot swing, for a scripted scene: a blacksmith striking an anvil, a guard warning
     * somebody off. Works on either kind -- a mob body swings under the same vanilla call any
     * other mob would; a human phantom (never an ordinary tracked entity) gets the same per-viewer
     * packet push every other piece of its state already goes through. False for an unknown NPC.
     */
    public static boolean swing(MinecraftServer server, UUID npcId, net.minecraft.world.InteractionHand hand) {
        return Npcs.swing(server, npcId, hand);
    }

    /**
     * Named for the laugh, kept for the use: an NPC that defies gravity stays exactly
     * where it was put with nothing underneath. Off (the default), it drops to the
     * ground once a second and its anchor follows it down.
     */
    public static void setDefyGravity(MinecraftServer server, UUID npcId, boolean defy) {
        Npcs.setDefyGravity(server, npcId, defy);
    }

    // --- being attacked ---

    /**
     * Make the NPC worth attacking for {@code leaseTicks}: monsters within {@code motion.lureRadius} are
     * set on it once a second, and every blow is an {@link NpcHitEvent}. It still takes no damage. Renew
     * to keep it up; when it lapses the monsters are called off. False when the NPC does not exist.
     */
    public static boolean expose(MinecraftServer server, UUID npcId, int leaseTicks) {
        return com.sablednah.cast.npc.Exposure.expose(server, npcId, leaseTicks);
    }

    public static boolean isExposed(UUID npcId) {
        return com.sablednah.cast.npc.Exposure.isExposed(npcId);
    }

    // --- moving ---

    /**
     * Follow a player on foot, walking the trail they actually walked, for {@code leaseTicks}.
     * Call again to renew; when the renewals stop the NPC stops and is anchored where it stands,
     * so a follow can never outlive whatever asked for it (a restart included). A new leader
     * starts a fresh trail. False when the NPC does not exist.
     */
    public static boolean follow(MinecraftServer server, UUID npcId, ServerPlayer leader, int leaseTicks) {
        return com.sablednah.cast.npc.Motion.follow(server, npcId, leader, leaseTicks);
    }

    public static void stopFollowing(MinecraftServer server, UUID npcId) {
        com.sablednah.cast.npc.Motion.stopFollowing(server, npcId);
    }

    /** Who the NPC is following, if anyone. */
    public static Optional<UUID> leaderOf(UUID npcId) {
        return com.sablednah.cast.npc.Motion.leaderOf(npcId);
    }

    /**
     * Walk to a point and stand there, anchored. A human walks a straight line hugging the
     * ground; a mob body uses its own navigation. Either one that cannot get there within a
     * minute is put there. False when the NPC does not exist.
     */
    public static boolean walkTo(MinecraftServer server, UUID npcId, Vec3 target) {
        return com.sablednah.cast.npc.Motion.walkTo(server, npcId, target);
    }

    /** Following or walking right now. */
    public static boolean isMoving(UUID npcId) {
        return com.sablednah.cast.npc.Motion.isMoving(npcId);
    }

    /**
     * Make the NPC a lurker, saved with it: it keeps to {@code home}, groans when a player is
     * within {@code radius}, and roughly every {@code everySeconds} while one is, rushes to
     * {@code door}, hammers on it and slinks back. The caged zombie in the basement.
     */
    public static void setLurk(MinecraftServer server, UUID npcId, Vec3 home, Vec3 door, double radius, int everySeconds) {
        Npcs.setLurk(server, npcId, Optional.of(new com.sablednah.cast.core.LurkSpec(home, door, radius, everySeconds, Optional.empty(), Optional.empty())));
    }

    public static void clearLurk(MinecraftServer server, UUID npcId) {
        Npcs.setLurk(server, npcId, Optional.empty());
    }

    /** Rush the door now, if it lurks. */
    public static boolean scare(MinecraftServer server, UUID npcId) {
        return com.sablednah.cast.npc.Motion.scare(server, npcId);
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
