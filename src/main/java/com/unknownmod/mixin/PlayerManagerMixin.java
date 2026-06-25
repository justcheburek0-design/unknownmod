package com.unknownmod.mixin;

import com.unknownmod.config.ConfigManager;
import com.unknownmod.state.IdentityStore;
import com.unknownmod.state.PlayerHistoryStateManager;
import com.unknownmod.state.RevelationManager;
import com.unknownmod.state.ServerContextHolder;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerManagerMixin {

    @Shadow
    @Final
    private MinecraftServer server;

    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void unknownmod$onPlayerJoin(net.minecraft.network.Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        java.util.UUID uuid = player.getUUID();

        // Запоминаем оригинальный профиль
        IdentityStore.remember(player.getGameProfile());

        ServerContextHolder.setServer(server);
        
        // Record join in history (silently, no broadcast)
        java.util.Optional<String> originalNameOpt = IdentityStore.getOriginalName(uuid);
        String originalName = originalNameOpt.orElse(player.getName().getString());
        PlayerHistoryStateManager.getServerState(server).recordJoin(uuid, originalName, System.currentTimeMillis());
    }

    // Suppress the join broadcast sent by vanilla placeNewPlayer
    @Redirect(
            method = "placeNewPlayer",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;broadcastSystemMessage(Lnet/minecraft/network/chat/Component;Z)V")
    )
    private void unknownmod$suppressJoinBroadcast(PlayerList instance, Component message, boolean overlay) {
        // Silent join - no broadcast
    }

    @Inject(method = "remove", at = @At("TAIL"))
    private void unknownmod$onPlayerLeave(ServerPlayer player, CallbackInfo ci) {
        // Silent leave - no broadcast
        ServerContextHolder.setServer(server);
    }

    static {
        ServerLifecycleEvents.SERVER_STOPPING.register(minecraftServer -> ServerContextHolder.clearServer());
    }
}
