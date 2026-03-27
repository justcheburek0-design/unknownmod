package com.unknownmod.mixin.client;

import com.mojang.authlib.GameProfile;
import com.unknownmod.mixin.PlayerEntityAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;
import java.util.UUID;

@Environment(EnvType.CLIENT)
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Shadow
    public abstract PlayerListEntry getPlayerListEntry(UUID uuid);

    @Inject(method = "onPlayerList", at = @At("TAIL"))
    private void unknownmod$refreshPlayerCache(PlayerListS2CPacket packet, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) {
            return;
        }

        for (PlayerListS2CPacket.Entry packetEntry : packet.getEntries()) {
            PlayerListEntry listEntry = getPlayerListEntry(packetEntry.profileId());
            if (listEntry == null) {
                continue;
            }

            GameProfile incomingProfile = packetEntry.profile();
            if (needsRefresh(listEntry.getProfile(), incomingProfile)) {
                ((PlayerListEntryAccessor) listEntry).setProfile(incomingProfile);
                ((PlayerListEntryAccessor) listEntry).setTexturesSupplier(null);
            }
        }

        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            PlayerListEntry listEntry = getPlayerListEntry(player.getUuid());
            if (listEntry == null) {
                continue;
            }

            GameProfile incomingProfile = listEntry.getProfile();
            if (needsRefresh(player.getGameProfile(), incomingProfile)) {
                ((PlayerEntityAccessor) (Object) player).setGameProfile(incomingProfile);
            }
        }
    }

    private static boolean needsRefresh(GameProfile currentProfile, GameProfile incomingProfile) {
        if (currentProfile == null || incomingProfile == null) {
            return false;
        }

        if (!Objects.equals(currentProfile.id(), incomingProfile.id())) {
            return true;
        }

        if (!Objects.equals(currentProfile.name(), incomingProfile.name())) {
            return true;
        }

        return !Objects.equals(currentProfile.properties().get("textures"), incomingProfile.properties().get("textures"));
    }
}
