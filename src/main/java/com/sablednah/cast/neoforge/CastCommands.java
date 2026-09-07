package com.sablednah.cast.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.sablednah.cast.api.Cast;
import com.sablednah.cast.api.Npc;
import com.sablednah.cast.core.NpcKind;
import com.sablednah.cast.npc.Brains;
import com.sablednah.cast.npc.Npcs;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /cast} -- admin only. Placement is "where you are looking"; every
 * other verb acts on the NPC you are looking at, or the nearest within reach.
 * Names may contain spaces, so they are quoted strings, never {@code word()}.
 */
public final class CastCommands {

    private static final double REACH = 6.0D;

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("cast")
                .requires(CastPermissions::isAdmin)
                .then(Commands.literal("spawn")
                        .then(Commands.literal("human")
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(ctx -> spawnHuman(ctx, Optional.empty()))
                                        .then(Commands.argument("skin", StringArgumentType.word())
                                                .executes(ctx -> spawnHuman(ctx, Optional.of(StringArgumentType.getString(ctx, "skin")))))))
                        .then(Commands.literal("mob")
                                .then(Commands.argument("type", IdentifierArgument.id())
                                        .then(Commands.argument("name", StringArgumentType.string())
                                                .executes(CastCommands::spawnMob)))))
                .then(Commands.literal("remove").executes(CastCommands::remove))
                .then(Commands.literal("list").executes(CastCommands::list))
                .then(Commands.literal("status").executes(CastCommands::status))
                .then(Commands.literal("skin").then(Commands.argument("account", StringArgumentType.word()).executes(CastCommands::skin)))
                .then(Commands.literal("name").then(Commands.argument("name", StringArgumentType.greedyString()).executes(CastCommands::rename)))
                .then(Commands.literal("look").then(Commands.argument("on", BoolArgumentType.bool()).executes(CastCommands::look)))
                .then(Commands.literal("here").executes(CastCommands::here))
                .then(Commands.literal("say").then(Commands.argument("text", StringArgumentType.greedyString()).executes(CastCommands::say)))
                .then(Commands.literal("role")
                        .then(Commands.literal("add").then(Commands.argument("role", IdentifierArgument.id()).executes(ctx -> role(ctx, true))))
                        .then(Commands.literal("remove").then(Commands.argument("role", IdentifierArgument.id()).executes(ctx -> role(ctx, false))))));
    }

    // --- placement and targeting ---

    private static Vec3 placement(ServerPlayer player) {
        HitResult hit = player.pick(REACH, 0.0F, false);
        if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) {
            var pos = b.getBlockPos().relative(b.getDirection());
            return new Vec3(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        }
        return player.position();
    }

    private static Optional<Npc> target(ServerPlayer player) {
        Optional<Npc> looked = Cast.npcAt(player, REACH);
        if (looked.isPresent()) return looked;
        Npc best = null;
        double bestD = 16.0;
        for (Npc n : Cast.all(player.level().getServer())) {
            if (!n.dimension().equals(player.level().dimension().identifier())) continue;
            double d = n.pos().distanceToSqr(player.position());
            if (d < bestD) { bestD = d; best = n; }
        }
        return Optional.ofNullable(best);
    }

    private static Optional<Npc> targetOrSay(ServerPlayer player) {
        Optional<Npc> t = target(player);
        if (t.isEmpty()) Feedback.chat(player, Lang.get("msg.none_here"));
        return t;
    }

    // --- verbs ---

    private static int spawnHuman(CommandContext<CommandSourceStack> ctx, Optional<String> skin) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String name = StringArgumentType.getString(ctx, "name");
        float yaw = player.getYRot() + 180F;
        UUID id = Cast.spawnHuman(player.level(), placement(player), yaw, name, skin, List.of());
        Feedback.chat(player, Lang.fmt("msg.spawned", "name", name, "kind", "human", "id", id));
        if (name.length() > 16) Feedback.chat(player, Lang.get("msg.named.long"));
        if (skin.isPresent() && !com.sablednah.cast.CastConfig.FETCH_SKINS.get()) Feedback.chat(player, Lang.get("msg.skin.off"));
        return 1;
    }

    private static int spawnMob(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Identifier type = IdentifierArgument.getId(ctx, "type");
        String name = StringArgumentType.getString(ctx, "name");
        var holder = BuiltInRegistries.ENTITY_TYPE.get(type);
        if (holder.isEmpty()) {
            Feedback.chat(player, Lang.fmt("msg.spawned.unknown", "type", type));
            return 0;
        }
        float yaw = player.getYRot() + 180F;
        UUID id = Cast.spawnMob(player.level(), placement(player), yaw, type, name, List.of());
        Optional<Npc> npc = Cast.byId(player.level().getServer(), id);
        if (npc.isEmpty() || npc.get().entity().isEmpty()) {
            Cast.remove(player.level().getServer(), id);
            Feedback.chat(player, Lang.get("msg.spawned.not_mob"));
            return 0;
        }
        Feedback.chat(player, Lang.fmt("msg.spawned", "name", name, "kind", type.getPath(), "id", id));
        if (npc.get().entity().get() instanceof Mob mob && Brains.isBrainDriven(mob)) {
            Feedback.chat(player, Lang.get("msg.spawned.brain"));
        }
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Npc> t = targetOrSay(player);
        if (t.isEmpty()) return 0;
        Cast.remove(player.level().getServer(), t.get().id());
        Feedback.chat(player, Lang.fmt("msg.removed", "name", t.get().name()));
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        List<Npc> all = Cast.all(ctx.getSource().getServer());
        if (all.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Feedback.colored(Lang.get("msg.list.none")), false);
            return 0;
        }
        List<String> lines = new ArrayList<>();
        lines.add(Lang.fmt("msg.list.header", "count", all.size()));
        for (Npc n : all) {
            lines.add(Lang.fmt("msg.list.entry", "kind", n.kind().name().toLowerCase(), "name", n.name(),
                    "x", (int) n.pos().x, "y", (int) n.pos().y, "z", (int) n.pos().z, "dim", n.dimension().getPath(),
                    "roles", n.roles().isEmpty() ? "" : n.roles().toString()));
        }
        String joined = String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Feedback.colored(joined), false);
        return all.size();
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        String line = Lang.fmt("msg.status", "count", Cast.all(ctx.getSource().getServer()).size(),
                "loaded", Npcs.loadedHumans(),
                "roles", Npcs.roleIds().isEmpty() ? "none" : String.join(", ", Npcs.roleIds().stream().map(Object::toString).toList()));
        ctx.getSource().sendSuccess(() -> Feedback.colored(line), false);
        return 1;
    }

    private static int skin(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Npc> t = targetOrSay(player);
        if (t.isEmpty()) return 0;
        String account = StringArgumentType.getString(ctx, "account");
        Npcs.setSkin(player.level().getServer(), t.get().id(), Optional.of(account));
        Feedback.chat(player, Lang.get(com.sablednah.cast.CastConfig.FETCH_SKINS.get() ? "msg.skin" : "msg.skin.off")
                .replace("{account}", account).replace("{name}", t.get().name()));
        return 1;
    }

    private static int rename(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Npc> t = targetOrSay(player);
        if (t.isEmpty()) return 0;
        String name = StringArgumentType.getString(ctx, "name");
        Cast.rename(player.level().getServer(), t.get().id(), name);
        Feedback.chat(player, Lang.fmt("msg.named", "name", name));
        if (t.get().kind() == NpcKind.HUMAN && name.length() > 16) Feedback.chat(player, Lang.get("msg.named.long"));
        return 1;
    }

    private static int look(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Npc> t = targetOrSay(player);
        if (t.isEmpty()) return 0;
        boolean on = BoolArgumentType.getBool(ctx, "on");
        Npcs.setLook(player.level().getServer(), t.get().id(), on);
        Feedback.chat(player, Lang.fmt("msg.look", "value", on));
        return 1;
    }

    private static int here(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Npc> t = targetOrSay(player);
        if (t.isEmpty()) return 0;
        Cast.drive(player.level().getServer(), t.get().id(), placement(player), player.getYRot() + 180F, 0F);
        Feedback.chat(player, Lang.fmt("msg.moved", "name", t.get().name()));
        return 1;
    }

    private static int say(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Npc> t = targetOrSay(player);
        if (t.isEmpty()) return 0;
        Cast.say(player.level().getServer(), t.get().id(), StringArgumentType.getString(ctx, "text"), 16.0);
        return 1;
    }

    private static int role(CommandContext<CommandSourceStack> ctx, boolean add) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Optional<Npc> t = targetOrSay(player);
        if (t.isEmpty()) return 0;
        Identifier role = IdentifierArgument.getId(ctx, "role");
        List<Identifier> roles = new ArrayList<>(t.get().roles());
        if (add) {
            if (!roles.contains(role)) roles.add(role);
            if (!Npcs.roleIds().contains(role)) {
                Feedback.chat(player, Lang.fmt("msg.role.unknown", "role", role,
                        "known", Npcs.roleIds().isEmpty() ? "none" : Npcs.roleIds().toString()));
            }
            Feedback.chat(player, Lang.fmt("msg.role.added", "role", role));
        } else {
            roles.remove(role);
            Feedback.chat(player, Lang.fmt("msg.role.removed", "role", role));
        }
        Cast.setRoles(player.level().getServer(), t.get().id(), roles);
        return 1;
    }

    private CastCommands() {}
}
