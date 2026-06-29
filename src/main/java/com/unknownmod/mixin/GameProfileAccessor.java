package com.unknownmod.mixin;

import com.mojang.authlib.properties.PropertyMap;
import com.mojang.authlib.GameProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameProfile.class)
public interface GameProfileAccessor {
    @Accessor("properties")
    PropertyMap getProperties();
}
