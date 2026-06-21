package com.unknownmod.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(net.minecraft.world.entity.player.Player.class)
public interface PlayerEntityAccessor {
    @Mutable
    @Accessor("gameProfile")
    void setGameProfile(GameProfile profile);
}
