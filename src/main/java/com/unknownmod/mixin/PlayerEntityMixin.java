package com.unknownmod.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin for Player.gameProfile.
 * Abstract class with @Accessor — generates accessor methods at compile time
 * without requiring any interface or @Implements.
 */
@Mixin(Player.class)
public abstract class PlayerEntityMixin {
    @Accessor("gameProfile")
    public static native GameProfile getGameProfile(Player player);

    @Accessor("gameProfile")
    public static native void setGameProfile(Player player, GameProfile profile);
}
