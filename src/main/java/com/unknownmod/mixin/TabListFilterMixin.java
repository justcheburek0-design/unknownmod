package com.unknownmod.mixin;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;

@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class TabListFilterMixin {

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void unknownmod$filterTabList(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ClientboundPlayerInfoUpdatePacket playerInfoPacket) {
            // Clear entries so the tab list appears empty
            ((com.unknownmod.mixin.PlayerListS2CPacketAccessor) playerInfoPacket).setEntries(Collections.emptyList());
        }
    }
}
