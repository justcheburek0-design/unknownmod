package com.unknownmod.mixin.server;

import com.unknownmod.config.ConfigManager;
import com.unknownmod.util.ProfileApplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.SERVER)
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class PacketS2CFilterMixin {
    @Shadow
    protected MinecraftServer server;

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void unknownmod$filterPlayerInfo(Packet<?> packet, CallbackInfo ci) {
        if (!(packet instanceof ClientboundPlayerInfoUpdatePacket playerInfoPacket)) {
            return;
        }

        if (server == null) {
            return;
        }

        ProfileApplier.anonymizePlayerInfoPacket(server, playerInfoPacket, ConfigManager.getConfig());
    }
}
