package com.unknownmod.mixin.client;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.unknownmod.mixin.PlayerEntityAccessor;
import com.unknownmod.util.ProfileApplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;

@Environment(EnvType.CLIENT)
@Mixin(net.minecraft.client.player.AbstractClientPlayer.class)
public abstract class AbstractClientPlayerEntityMixin {
    @Shadow
    private PlayerInfo playerListEntry;

    @Inject(method = "getPlayerInfo", at = @At("RETURN"), cancellable = true)
    private void unknownmod$refreshCachedEntry(CallbackInfoReturnable<PlayerInfo> cir) {
        ClientPacketListener networkHandler = Minecraft.getInstance().getConnection();
        if (networkHandler == null) {
            return;
        }

        PlayerInfo currentEntry = Minecraft.getInstance().getConnection().getPlayerInfo(((AbstractClientPlayer) (Object) this).getUUID());
        if (currentEntry == null) {
            return;
        }

        this.playerListEntry = currentEntry;
        syncPlayerProfile(currentEntry.getProfile());
        cir.setReturnValue(currentEntry);
    }

    private void syncPlayerProfile(GameProfile incomingProfile) {
        GameProfile currentProfile = ((AbstractClientPlayer) (Object) this).getGameProfile();
        GameProfile mergedProfile = mergePreservingTextures(currentProfile, incomingProfile);
        if (!needsRefresh(currentProfile, mergedProfile)) {
            return;
        }

        ((PlayerEntityAccessor) (Object) this).setGameProfile(mergedProfile);
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

        return !Objects.equals(currentProfile.id(), incomingProfile.id())
                || !Objects.equals(currentProfile.name(), incomingProfile.name())
                || !Objects.equals(currentProfile.properties().get("textures"), incomingProfile.properties().get("textures"));
    }
}
