package com.unknownmod.mixin;

import net.minecraft.server.players.PlayerList;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.network.chat.MutableComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(net.minecraft.server.network.ServerPlayerConnection.class)
public abstract class ServerPlayNetworkHandlerMixin {

    @Redirect(
            method = "cleanUp",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/PlayerList;broadcast(Lnet/minecraft/text/MutableComponent;Z)V")
    )
    private void unknownmod$skipVanillaLeaveBroadcast(PlayerList instance, MutableComponent message, boolean overlay) {
    }
}
