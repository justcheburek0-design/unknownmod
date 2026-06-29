package com.unknownmod.mixin.client;

import com.mojang.authlib.GameProfile;
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

@Environment(EnvType.CLIENT)
@Mixin(net.minecraft.client.player.AbstractClientPlayer.class)
public abstract class AbstractClientPlayerEntityMixin {
    @Shadow
    private PlayerInfo playerInfo;

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

        this.playerInfo = currentEntry;
        cir.setReturnValue(currentEntry);
    }
}
