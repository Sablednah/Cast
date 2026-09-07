package com.sablednah.cast.mixin;

import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The packet keeps its entity id private and vanilla resolves it itself; we need the number. */
@Mixin(ServerboundInteractPacket.class)
public interface InteractPacketAccessor {
    @Accessor("entityId")
    int cast$entityId();
}
