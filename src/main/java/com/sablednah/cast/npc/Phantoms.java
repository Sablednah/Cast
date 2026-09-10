package com.sablednah.cast.npc;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.sablednah.cast.CastConfig;
import com.sablednah.cast.core.NpcSpec;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;

/**
 * The tracker for human phantoms: who can see which one, and the packets
 * that make it so. The server's own entity tracker never hears of these.
 *
 * <p>Order matters and is checked by the client: player info first (the
 * client refuses a player entity it has no info for), then the spawn, then
 * entity data and head rotation. Removal is the mirror. The info entry is
 * sent with {@code listed=false}, so nothing appears in the tab list.</p>
 */
public final class Phantoms {

    /** npcId -> the viewers currently holding the entity on their client. */
    private static final Map<UUID, Set<UUID>> VIEWERS = new HashMap<>();
    /**
     * npcId -> viewers who must keep it whatever the range: a possessor. A
     * camera packet names an entity id the client resolves in its own level,
     * so the phantom has to be on that client for the whole possession, and
     * the remove packet would eject the camera silently.
     */
    private static final Map<UUID, Set<UUID>> PINNED = new HashMap<>();

    public static void update(ServerLevel level, HumanNpc npc, NpcSpec spec) {
        int range = CastConfig.VIEW_RANGE.get();
        double in = (double) range * range;
        double out = (double) range * 1.25 * range * 1.25;
        Set<UUID> viewers = VIEWERS.computeIfAbsent(npc.npcId, k -> new HashSet<>());
        Set<UUID> pinned = PINNED.getOrDefault(npc.npcId, Set.of());
        // Every player, spectators included: a drifting Storyteller is a spectator.
        for (ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            boolean sameLevel = player.level() == level;
            boolean pin = pinned.contains(player.getUUID());
            double d = sameLevel ? player.distanceToSqr(npc.getX(), npc.getY(), npc.getZ()) : Double.MAX_VALUE;
            boolean viewing = viewers.contains(player.getUUID());
            if (!viewing && sameLevel && (pin || d <= in)) {
                show(player, npc);
                viewers.add(player.getUUID());
            } else if (viewing && !pin && (!sameLevel || d > out)) {
                hide(player, npc);
                viewers.remove(player.getUUID());
            }
        }
        // Drop viewers who left the server -- never a pinned one; a possessor unpins on logout via forget().
        viewers.removeIf(id -> !pinned.contains(id) && level.getServer().getPlayerList().getPlayer(id) == null);
    }

    public static void show(ServerPlayer viewer, HumanNpc npc) {
        send(viewer, new ClientboundPlayerInfoUpdatePacket(EnumSet.of(
                ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY), List.of(npc)));
        send(viewer, new ClientboundAddEntityPacket(npc.getId(), npc.getUUID(),
                npc.getX(), npc.getY(), npc.getZ(), npc.getXRot(), npc.getYRot(),
                EntityType.PLAYER, 0, Vec3.ZERO, npc.getYHeadRot()));
        List<SynchedEntityData.DataValue<?>> values = npc.getEntityData().getNonDefaultValues();
        if (values != null && !values.isEmpty()) {
            send(viewer, new ClientboundSetEntityDataPacket(npc.getId(), values));
        }
        send(viewer, new ClientboundRotateHeadPacket(npc, toByte(npc.getYHeadRot())));
        var worn = Equipment.worn(npc);
        if (!worn.isEmpty()) send(viewer, new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(npc.getId(), worn));
        scheduleUnlist(viewer, npc);
    }

    /** What the phantom holds changed: every viewer is told (an empty list clears nothing, so send every slot). */
    public static void broadcastEquipment(ServerLevel level, HumanNpc npc) {
        List<com.mojang.datafixers.util.Pair<net.minecraft.world.entity.EquipmentSlot, net.minecraft.world.item.ItemStack>> all = new java.util.ArrayList<>();
        for (var slot : net.minecraft.world.entity.EquipmentSlot.values()) all.add(com.mojang.datafixers.util.Pair.of(slot, npc.getItemBySlot(slot)));
        for (ServerPlayer p : viewersOf(level, npc)) send(p, new net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket(npc.getId(), all));
    }

    /** Player-info entries to withdraw: viewer -> npc -> the game time it is due. */
    private static final Map<UUID, Map<UUID, Long>> PENDING_UNLIST = new HashMap<>();

    /**
     * The player-info entry that made the phantom render is also what puts its name in the
     * client's command suggestions, beside the real players. The client only needs it long
     * enough to fetch the skin, so it is withdrawn a couple of seconds after the entity
     * appears (npcs.tabEntrySeconds); the rendered entity keeps the skin it resolved.
     */
    private static void scheduleUnlist(ServerPlayer viewer, HumanNpc npc) {
        int secs = CastConfig.TAB_ENTRY_SECONDS.get();
        if (secs <= 0) return;
        PENDING_UNLIST.computeIfAbsent(viewer.getUUID(), k -> new HashMap<>())
                .put(npc.getUUID(), viewer.level().getGameTime() + secs * 20L);
    }

    /** Once a second from the NPC tick: withdraw the entries whose time has come. */
    public static void tickUnlist(net.minecraft.server.MinecraftServer server) {
        long now = server.overworld().getGameTime();
        for (var it = PENDING_UNLIST.entrySet().iterator(); it.hasNext();) {
            var e = it.next();
            ServerPlayer viewer = server.getPlayerList().getPlayer(e.getKey());
            if (viewer == null) { it.remove(); continue; }
            List<UUID> due = new java.util.ArrayList<>();
            e.getValue().forEach((npc, at) -> { if (now >= at) due.add(npc); });
            if (!due.isEmpty()) {
                send(viewer, new ClientboundPlayerInfoRemovePacket(due));
                due.forEach(e.getValue()::remove);
            }
            if (e.getValue().isEmpty()) it.remove();
        }
    }

    public static int pendingUnlist() { int n = 0; for (var m : PENDING_UNLIST.values()) n += m.size(); return n; }

    public static void hide(ServerPlayer viewer, HumanNpc npc) {
        send(viewer, new ClientboundRemoveEntitiesPacket(npc.getId()));
        send(viewer, new ClientboundPlayerInfoRemovePacket(List.of(npc.getUUID())));
    }

    /** Take a phantom off every client that has it -- a removal, a skin change, a re-body. */
    public static void hideFromAll(ServerLevel level, HumanNpc npc) {
        Set<UUID> viewers = VIEWERS.remove(npc.npcId);
        if (viewers == null) return;
        for (UUID id : viewers) {
            ServerPlayer p = level.getServer().getPlayerList().getPlayer(id);
            if (p != null) hide(p, npc);
        }
    }

    /** The phantom turned: tell everyone holding it. */
    public static void broadcastRotation(ServerLevel level, HumanNpc npc) {
        byte yaw = toByte(npc.getYRot());
        byte pitch = toByte(npc.getXRot());
        for (ServerPlayer p : viewersOf(level, npc)) {
            send(p, new ClientboundMoveEntityPacket.Rot(npc.getId(), yaw, pitch, true));
            send(p, new ClientboundRotateHeadPacket(npc, toByte(npc.getYHeadRot())));
        }
    }

    /** The phantom was driven somewhere: a full position sync. */
    public static void broadcastPosition(ServerLevel level, HumanNpc npc) {
        for (ServerPlayer p : viewersOf(level, npc)) {
            send(p, ClientboundEntityPositionSyncPacket.of(npc));
            send(p, new ClientboundRotateHeadPacket(npc, toByte(npc.getYHeadRot())));
        }
    }

    /** A player left or changed dimension: forget them everywhere. Returns the npcIds they were pinned to. */
    public static Set<UUID> forget(ServerPlayer player) {
        for (Set<UUID> viewers : VIEWERS.values()) viewers.remove(player.getUUID());
        Set<UUID> wasPinned = new HashSet<>();
        PINNED.forEach((npc, set) -> { if (set.remove(player.getUUID())) wasPinned.add(npc); });
        return wasPinned;
    }

    /** Pin, and show at once if not already on this client: the camera packet that follows names our entity id. */
    public static void pin(ServerPlayer player, UUID npcId, HumanNpc npc) {
        PINNED.computeIfAbsent(npcId, k -> new HashSet<>()).add(player.getUUID());
        Set<UUID> viewers = VIEWERS.computeIfAbsent(npcId, k -> new HashSet<>());
        if (npc != null && !viewers.contains(player.getUUID())) {
            show(player, npc);
            viewers.add(player.getUUID());
        }
    }

    public static void unpin(ServerPlayer player, UUID npcId) {
        Set<UUID> set = PINNED.get(npcId);
        if (set != null) set.remove(player.getUUID());
    }

    public static boolean isPinned(UUID npcId) {
        Set<UUID> set = PINNED.get(npcId);
        return set != null && !set.isEmpty();
    }

    public static void clear() {
        PENDING_UNLIST.clear();
        VIEWERS.clear();
        PINNED.clear();
    }

    /**
     * The phantom moved from {@code from} to where it stands now. Under eight
     * blocks in the same level this is a relative move the client interpolates
     * (a walk); beyond that a position sync, which the client snaps (a
     * teleport). Vanilla draws the line in the same place.
     */
    public static void broadcastMove(ServerLevel level, HumanNpc npc, Vec3 from) {
        double dx = npc.getX() - from.x, dy = npc.getY() - from.y, dz = npc.getZ() - from.z;
        boolean relative = Math.abs(dx) < 8 && Math.abs(dy) < 8 && Math.abs(dz) < 8;
        byte yaw = toByte(npc.getYRot());
        byte pitch = toByte(npc.getXRot());
        for (ServerPlayer p : viewersOf(level, npc)) {
            if (relative) {
                send(p, new ClientboundMoveEntityPacket.PosRot(npc.getId(),
                        (short) Math.round(dx * 4096), (short) Math.round(dy * 4096), (short) Math.round(dz * 4096),
                        yaw, pitch, true));
            } else {
                send(p, ClientboundEntityPositionSyncPacket.of(npc));
            }
            send(p, new ClientboundRotateHeadPacket(npc, toByte(npc.getYHeadRot())));
        }
    }

    public static boolean isViewing(ServerPlayer player, UUID npcId) {
        Set<UUID> v = VIEWERS.get(npcId);
        return v != null && v.contains(player.getUUID());
    }

    private static List<ServerPlayer> viewersOf(ServerLevel level, HumanNpc npc) {
        Set<UUID> viewers = VIEWERS.get(npc.npcId);
        if (viewers == null) return List.of();
        return viewers.stream().map(id -> level.getServer().getPlayerList().getPlayer(id))
                .filter(p -> p != null).toList();
    }

    private static void send(ServerPlayer player, Packet<?> packet) {
        if (player.connection != null) player.connection.send(packet);
    }

    static byte toByte(float degrees) {
        return (byte) Math.floor(degrees * 256.0F / 360.0F);
    }

    private Phantoms() {}
}
