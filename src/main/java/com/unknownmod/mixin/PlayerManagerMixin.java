package com.unknownmod.mixin;

import com.unknownmod.config.ConfigManager;
import com.unknownmod.state.RevelationManager;
import com.unknownmod.util.MessageFormatter;
import com.unknownmod.util.ProfileApplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerManager.class)
public abstract class PlayerManagerMixin {

    @Shadow
    @Final
    private MinecraftServer server;

    @org.spongepowered.asm.mixin.Unique
    private boolean unknownmod$suppressLeaveBroadcast;

    @Redirect(
            method = "onPlayerConnect",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/PlayerManager;broadcast(Lnet/minecraft/text/Text;Z)V")
    )
    private void unknownmod$skipJoinBroadcast(PlayerManager instance, Text message, boolean overlay) {
    }

    @Inject(method = "onPlayerConnect", at = @At("TAIL"))
    private void unknownmod$sendJoinMessage(net.minecraft.network.ClientConnection connection, ServerPlayerEntity player, ConnectedClientData clientData, CallbackInfo ci) {
        ProfileApplier.refreshPlayer(server, player);
        broadcastCustomMessage(ConfigManager.getConfig().messages.joined, player);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void unknownmod$markLeaveBroadcastSuppressed(ServerPlayerEntity player, CallbackInfo ci) {
        unknownmod$suppressLeaveBroadcast = true;
    }

    @Inject(method = "broadcast(Lnet/minecraft/text/Text;Z)V", at = @At("HEAD"), cancellable = true)
    private void unknownmod$skipLeaveBroadcast(Text message, boolean overlay, CallbackInfo ci) {
        if (unknownmod$suppressLeaveBroadcast) {
            ci.cancel();
        }
    }

    @Inject(method = "remove", at = @At("TAIL"))
    private void unknownmod$sendLeaveMessage(ServerPlayerEntity player, CallbackInfo ci) {
        unknownmod$suppressLeaveBroadcast = false;
        broadcastCustomMessage(ConfigManager.getConfig().messages.left, player);
    }

    private void broadcastCustomMessage(String template, ServerPlayerEntity player) {
        String displayName = ProfileApplier.getDisplayName(server, player);
        if (displayName == null || displayName.isBlank()) {
            displayName = player.getName().getString();
        }

        Text playerText = Text.literal(displayName);
        if (RevelationManager.isRevealed(server, player.getUuid())) {
            playerText = playerText.copy().formatted(Formatting.RED);
        }

        Text message = MessageFormatter.formatWithTextPlaceholder(template, "player", playerText);
        if (message == null || message.getString().isBlank()) {
            return;
        }

        server.getPlayerManager().broadcast(message, false);
    }
}
