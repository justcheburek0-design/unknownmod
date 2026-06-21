package com.unknownmod.mixin;

import com.mojang.authlib.GameProfile;
import com.unknownmod.util.ProfileApplier;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.UUID;

@Mixin(net.minecraft.server.network.ServerCommonPacketListenerImpl.class)
public abstract class ServerCommonNetworkHandlerMixin {

    @Shadow
    @Final
    protected Connection connection;

    @Shadow
    @Final
    protected MinecraftServer server;

    @Shadow
    protected abstract GameProfile getProfile();

    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
    private void unknownmod$personalizePlayerList(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ClientboundPlayerInfoUpdatePacket playerListPacket)) {
            return;
        }

        UUID viewerUuid = getProfile().id();
        if (viewerUuid == null) {
            return;
        }

        ClientboundPlayerInfoUpdatePacket personalizedPacket = buildPersonalizedPacket(playerListPacket, viewerUuid);
        if (personalizedPacket == null) {
            return;
        }

        connection.send(personalizedPacket);
        ci.cancel();
    }

    private ClientboundPlayerInfoUpdatePacket buildPersonalizedPacket(ClientboundPlayerInfoUpdatePacket packet, UUID viewerUuid) {
        if (server == null) {
            return null;
        }

        List<ServerPlayer> players = packet.entries().stream()
                .map(entry -> server.getPlayerList().getPlayer(entry.profileId()))
                .filter(java.util.Objects::nonNull)
                .toList();

        ClientboundPlayerInfoUpdatePacket personalizedPacket = new ClientboundPlayerInfoUpdatePacket(packet.actions(), players);
        if (!ProfileApplier.personalizePlayerListPacket(server, personalizedPacket, viewerUuid)) {
            return null;
        }

        return personalizedPacket;
    }
}
