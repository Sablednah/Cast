package com.sablednah.cast.npc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.brigadier.StringReader;
import com.mojang.datafixers.util.Pair;
import com.sablednah.cast.CastMod;
import com.sablednah.cast.core.NpcSpec;

import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;

/**
 * What an NPC holds and wears. Items are kept as strings in the spec, in the
 * form {@code /give} takes ({@code minecraft:potion[potion_contents={potion:'minecraft:healing'}]}),
 * and built into stacks at use time -- no {@code ItemStack} in a codec, and a
 * typo is reported once with the NPC named rather than breaking the store.
 */
public final class Equipment {

    public static Optional<EquipmentSlot> slot(String name) {
        try {
            return Optional.of(EquipmentSlot.byName(name.trim().toLowerCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Parse an item string; empty (and a warning naming the NPC) when it is wrong. */
    public static ItemStack parse(HolderLookup.Provider registries, String item, String who) {
        try {
            // 26.x: parse() returns an ItemInput (a record of holder + component patch) rather than ItemResult.
            net.minecraft.commands.arguments.item.ItemInput r = new ItemParser(registries).parse(new StringReader(item.trim()));
            ItemStack stack = new ItemStack(r.item(), 1);
            stack.applyComponents(r.components());
            return stack;
        } catch (Exception e) {
            CastMod.LOGGER.warn("Cast: NPC {} cannot hold '{}': {}", who, item, e.getMessage());
            return ItemStack.EMPTY;
        }
    }

    /** Dress a body or a phantom from its spec: every named slot set, every other slot cleared. */
    public static void apply(LivingEntity entity, NpcSpec spec) {
        Map<String, String> wants = spec.equipment();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            String item = wants.get(slot.getName());
            ItemStack stack = item == null ? ItemStack.EMPTY : parse(entity.level().registryAccess(), item, spec.name());
            entity.setItemSlot(slot, stack);
            if (entity instanceof Mob mob) mob.setDropChance(slot, 0F);
        }
    }

    /** The non-empty slots, for the equipment packet. */
    public static List<Pair<EquipmentSlot, ItemStack>> worn(LivingEntity entity) {
        List<Pair<EquipmentSlot, ItemStack>> out = new ArrayList<>();
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            ItemStack s = entity.getItemBySlot(slot);
            if (!s.isEmpty()) out.add(Pair.of(slot, s));
        }
        return out;
    }

    private Equipment() {}
}
