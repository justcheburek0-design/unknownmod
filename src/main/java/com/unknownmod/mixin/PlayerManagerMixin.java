package com.unknownmod.mixin;

import com.unknownmod.config.ConfigManager;
import com.unknownmod.state.IdentityStore;
import com.unknownmod.state.PlayerHistoryStateManager;
import com.unknownmod.state.RevelationManager;
import com.unknownmod.state.ServerContextHolder;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerManagerMixin {

    @Shadow
    @Final
    private MinecraftServer server;

    @Inject(method = "placeNewPlayer", at = @At("TAIL"))
    private void unknownmod$sendJoinMessage(net.minecraft.network.Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        java.util.UUID uuid = player.getUUID();

        // Запоминаем оригинальный профиль
        IdentityStore.remember(player.getGameProfile());

        ServerContextHolder.setServer(server);
        boolean revealed = RevelationManager.isRevealed(server, uuid);
        java.util.Optional<String> originalNameOpt = IdentityStore.getOriginalName(uuid);
        String originalName = originalNameOpt.orElse(player.getName().getString());

        if (revealed) {
            MutableComponent joinMessage = Component.literal("+" + originalName).withStyle(ChatFormatting.GREEN);
            player.sendSystemMessage(joinMessage);
        } else {
            String displayName = player.getName().getString();
            MutableComponent joinMessage = Component.literal("+" + displayName).withStyle(ChatFormatting.GRAY);
            player.sendSystemMessage(joinMessage);
        }

        // Record join in history
        PlayerHistoryStateManager.getServerState(server).recordJoin(uuid, originalName, System.currentTimeMillis());
    }

    @Inject(method = "remove", at = @At("TAIL"))
    private void unknownmod$sendLeaveMessage(ServerPlayer player, CallbackInfo ci) {
        java.util.UUID uuid = player.getUUID();

        ServerContextHolder.setServer(server);
        boolean revealed = RevelationManager.isRevealed(server, uuid);
        java.util.Optional<String> originalNameOpt = IdentityStore.getOriginalName(uuid);
        String originalName = originalNameOpt.orElse(player.getName().getString());

        if (revealed) {
            server.getPlayerList().broadcastSystemMessage(
                Component.literal("-" + originalName).withStyle(ChatFormatting.RED), false);
        } else {
            server.getPlayerList().broadcastSystemMessage(
                Component.literal("-" + player.getName().getString()).withStyle(ChatFormatting.DARK_GRAY), false);
        }
    }
}
