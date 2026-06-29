package com.unknownmod.mixin.client;

import com.mojang.authlib.GameProfile;
import com.unknownmod.util.ProfileApplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Environment(EnvType.CLIENT)
@Mixin(net.minecraft.client.player.AbstractClientPlayer.class)
public abstract class AbstractClientPlayerEntityMixin {

    @Inject(method = "getPlayerInfo", at = @At("RETURN"), cancellable = true)
    private void unknownmod$refreshCachedEntry(CallbackInfoReturnable<PlayerInfo> cir) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }

        PlayerInfo currentEntry = connection.getPlayerInfo(((AbstractClientPlayer) (Object) this).getUUID());
        if (currentEntry == null) {
            return;
        }

        PlayerInfo returnValue = cir.getReturnValue();
        if (returnValue != null) {
            syncPlayerProfile(returnValue.getProfile());
            cir.setReturnValue(returnValue);
        }
    }

    private void syncPlayerProfile(GameProfile incomingProfile) {
        GameProfile currentProfile = ((AbstractClientPlayer) (Object) this).getGameProfile();
        if (incomingProfile == null || currentProfile == null) {
            return;
        }

        ProfileApplier.rememberOriginalProfile(incomingProfile);
    }
}
