package com.unknownmod.mixin.client;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.unknownmod.util.ProfileApplier;
import com.unknownmod.mixin.PlayerEntityAccessor;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;
import java.util.UUID;

@Environment(EnvType.CLIENT)
@Mixin(net.minecraft.client.multiplayer.ClientPacketListener.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Shadow
    public abstract PlayerInfo getPlayerListEntry(UUID uuid);

    @Inject(method = "onPlayerList", at = @At("TAIL"))
    private void unknownmod$refreshPlayerCache(ClientboundPlayerInfoUpdatePacket packet, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }

        for (ClientboundPlayerInfoUpdatePacket.Entry packetEntry : packet.entries()) {
            PlayerInfo listEntry = getPlayerListEntry(packetEntry.profileId());
            if (listEntry == null) {
                continue;
            }

            GameProfile incomingProfile = packetEntry.profile();
            GameProfile mergedProfile = mergePreservingTextures(listEntry.getProfile(), incomingProfile);
            if (needsRefresh(listEntry.getProfile(), mergedProfile)) {
                ((PlayerListEntryAccessor) listEntry).setProfile(mergedProfile);
                ((PlayerListEntryAccessor) listEntry).setTexturesSupplier(null);
            }
        }

        for (AbstractClientPlayer player : client.level.players()) {
            PlayerInfo listEntry = getPlayerListEntry(player.getUUID());
            if (listEntry == null) {
                continue;
            }

            GameProfile incomingProfile = mergePreservingTextures(player.getGameProfile(), listEntry.getProfile());
            if (needsRefresh(player.getGameProfile(), incomingProfile)) {
                ((PlayerEntityAccessor) (Object) player).setGameProfile(incomingProfile);
            }
        }
    }

    private static GameProfile mergePreservingTextures(GameProfile currentProfile, GameProfile incomingProfile) {
        if (incomingProfile == null) {
            return null;
        }

        Property currentTextures = ProfileApplier.getTextures(currentProfile);
        if (currentTextures == null || ProfileApplier.hasTextures(incomingProfile)) {
            return incomingProfile;
        }

        return ProfileApplier.copyWithTextures(incomingProfile, currentTextures);
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
