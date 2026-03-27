package com.unknownmod.mixin.client;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.Environment;
import net.fabricmc.api.EnvType;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.player.SkinTextures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Environment(EnvType.CLIENT)
@Mixin(PlayerListEntry.class)
public interface PlayerListEntryAccessor {
    @Mutable
    @Accessor("profile")
    void setProfile(GameProfile profile);

    @Accessor("texturesSupplier")
    void setTexturesSupplier(java.util.function.Supplier<SkinTextures> texturesSupplier);
}
