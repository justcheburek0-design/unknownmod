package com.unknownmod.mixin.client;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Environment(EnvType.CLIENT)
@Mixin(PlayerInfo.class)
public interface PlayerListEntryAccessor {
    @Accessor("profile")
    GameProfile getProfile();

    @Accessor("skinLookup")
    void setTexturesSupplier(java.util.function.Supplier<PlayerSkin> texturesSupplier);
}
