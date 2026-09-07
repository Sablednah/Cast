package com.sablednah.cast.neoforge;

import com.sablednah.cast.CastMod;
import com.sablednah.cast.api.NpcRemovedEvent;
import com.sablednah.cast.core.NpcStore;
import com.sablednah.cast.npc.Npcs;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Game-bus hooks. Thin; the rules live in {@code npc/}. */
public final class CastEvents {

    private static int tickCounter;

    @SubscribeEvent
    static void onAboutToStart(ServerAboutToStartEvent event) {
        Lang.load();
    }

    @SubscribeEvent
    static void onStarted(ServerStartedEvent event) {
        NpcStore store = NpcStore.get(event.getServer());
        CastMod.LOGGER.info("Cast: {} NPC(s) in the store, {} role(s) registered [{}]", store.size(),
                Npcs.roleIds().size(), String.join(", ", Npcs.roleIds().stream().map(Object::toString).toList()));
        if (Boolean.getBoolean("cast.selftest")) SelfTest.run(event.getServer());
    }

    @SubscribeEvent
    static void onStopping(ServerStoppingEvent event) {
        Npcs.onServerStopping();
    }

    @SubscribeEvent
    static void onTick(ServerTickEvent.Post event) {
        if (++tickCounter < 20) return;
        tickCounter = 0;
        Npcs.tick(event.getServer());
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) Npcs.onPlayerGone(p);
    }

    /** A viewer in another dimension holds nothing of ours; forget them so the next tick re-sends. */
    @SubscribeEvent
    static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) Npcs.onPlayerGone(p);
    }

    /** Mob bodies: right-click dispatches roles, and never opens the body's own screen (a villager's trades). */
    @SubscribeEvent
    static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide() || event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Npcs.npcIdOf(event.getTarget()).ifPresent(id -> {
            Npcs.interact(player, id, event.getHand());
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        });
    }

    /** A proxy armor stand from before a restart has no phantom behind it: discard on sight. */
    @SubscribeEvent
    static void onJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (com.sablednah.cast.npc.Proxies.isOrphan(event.getEntity())) event.getEntity().discard();
    }

    @SubscribeEvent
    static void onDamage(LivingIncomingDamageEvent event) {
        if (Npcs.npcIdOf(event.getEntity()).isPresent()) event.setCanceled(true);
    }

    /** A body left the level: say why, on the server thread, for whoever holds a camera on it. */
    @SubscribeEvent
    static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        Entity e = event.getEntity();
        if (Npcs.npcIdOf(e).isEmpty()) return;
        Entity.RemovalReason r = e.getRemovalReason();
        NpcRemovedEvent.Reason reason = r == null ? NpcRemovedEvent.Reason.UNLOAD : switch (r) {
            case KILLED -> NpcRemovedEvent.Reason.DEATH;
            case CHANGED_DIMENSION -> NpcRemovedEvent.Reason.DIMENSION_CHANGE;
            case DISCARDED -> NpcRemovedEvent.Reason.REMOVED;
            default -> NpcRemovedEvent.Reason.UNLOAD;
        };
        Npcs.onBodyGone(e, reason);
    }

    private CastEvents() {}
}
