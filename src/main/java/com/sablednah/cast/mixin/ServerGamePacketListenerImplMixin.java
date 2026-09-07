package com.sablednah.cast.mixin;

import com.sablednah.cast.npc.Npcs;

import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one mixin, and it exists because a human phantom has no server entity:
 * vanilla's interact handler looks the id up in the level, finds nothing, and
 * drops the click on the floor. This catches the click first.
 *
 * <p>Injected at HEAD, which runs on the netty thread before vanilla's own
 * {@code ensureRunningOnSameThread} reschedules the packet. So it acts only
 * on the server thread and lets the first pass fall through -- vanilla then
 * re-invokes the method on the main thread, where this fires again and
 * handles it. Only a main-hand plain interact counts; the interact-at that
 * precedes it and the attack are ignored, so a click is one event.</p>
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    @Shadow public ServerPlayer player;

    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void cast$handleInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        if (player == null || !player.level().getServer().isSameThread()) return;
        int id = ((InteractPacketAccessor) packet).cast$entityId();
        var human = Npcs.humanByEntityId(id);
        if (human.isEmpty()) return;
        packet.dispatch(new ServerboundInteractPacket.Handler() {
            @Override public void onInteraction(InteractionHand hand) {
                if (hand == InteractionHand.MAIN_HAND) Npcs.interact(player, human.get().npcId, hand);
            }
            @Override public void onInteraction(InteractionHand hand, Vec3 pos) {}
            @Override public void onAttack() {}
        });
        ci.cancel();
    }
}
