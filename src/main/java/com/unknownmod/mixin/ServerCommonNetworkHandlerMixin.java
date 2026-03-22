package com.unknownmod.mixin;

import com.mojang.authlib.GameProfile;
import com.unknownmod.util.ProfileApplier;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.network.ServerCommonNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.UUID;

@Mixin(ServerCommonNetworkHandler.class)
public abstract class ServerCommonNetworkHandlerMixin {

    @Shadow
    @Final
    protected ClientConnection connection;

    @Shadow
    @Final
    protected MinecraftServer server;

    @Shadow
    protected abstract GameProfile getProfile();

    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
    private void unknownmod$personalizePlayerList(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof PlayerListS2CPacket playerListPacket)) {
            return;
        }

        UUID viewerUuid = getProfile().id();
        if (viewerUuid == null) {
            return;
        }

        PlayerListS2CPacket personalizedPacket = buildPersonalizedPacket(playerListPacket, viewerUuid);
        if (personalizedPacket == null) {
            return;
        }

        connection.send(personalizedPacket);
        ci.cancel();
    }

    private PlayerListS2CPacket buildPersonalizedPacket(PlayerListS2CPacket packet, UUID viewerUuid) {
        if (server == null) {
            return null;
        }

        List<ServerPlayerEntity> players = packet.getEntries().stream()
                .map(entry -> server.getPlayerManager().getPlayer(entry.profileId()))
                .filter(java.util.Objects::nonNull)
                .toList();

        PlayerListS2CPacket personalizedPacket = new PlayerListS2CPacket(packet.getActions(), players);
        if (!ProfileApplier.personalizePlayerListPacket(server, personalizedPacket, viewerUuid)) {
            return null;
        }

        return personalizedPacket;
    }
}
