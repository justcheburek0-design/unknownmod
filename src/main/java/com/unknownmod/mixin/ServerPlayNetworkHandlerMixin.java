package com.unknownmod.mixin;

import net.minecraft.server.players.PlayerList;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(net.minecraft.server.network.ServerGamePacketListenerImpl.class)
public abstract class ServerPlayNetworkHandlerMixin {

    // Suppress vanilla leave broadcast in removePlayerFromWorld (called from onDisconnect)
    @Redirect(
            method = "removePlayerFromWorld",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V")
    )
    private void unknownmod$skipVanillaLeaveBroadcast(PlayerList instance, Component message, boolean overlay) {
        // Silent leave - no broadcast
    }
}
