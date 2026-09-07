package com.sablednah.cast.neoforge;

import com.sablednah.cast.CastMod;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/** One node, through NeoForge's PermissionAPI (Standards and LuckPerms both handle it). */
public final class CastPermissions {

    /** The {@code /cast} tree. Op level 2 also passes. */
    public static final PermissionNode<Boolean> ADMIN = new PermissionNode<>(
            CastMod.MODID, "admin", PermissionTypes.BOOLEAN, (player, uuid, context) -> false);

    @SubscribeEvent
    static void onGather(PermissionGatherEvent.Nodes event) {
        event.addNodes(ADMIN);
    }

    public static boolean isAdmin(CommandSourceStack source) {
        if (Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source)) return true;
        return source.getEntity() instanceof ServerPlayer player && PermissionAPI.getPermission(player, ADMIN);
    }

    private CastPermissions() {}
}
