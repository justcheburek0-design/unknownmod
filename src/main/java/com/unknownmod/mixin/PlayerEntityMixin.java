package com.unknownmod.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;

/**
 * Mixin that adds PlayerEntityAccessor to Player at runtime.
 * Using @Implements with a plain interface, NOT interface-type @Mixin,
 * to avoid conflicts with Sodium (which also uses interface mixins on Player).
 */
@Mixin(Player.class)
@Implements(@Interface(iface = PlayerEntityAccessor.class, prefix = "unknownmod$"))
public abstract class PlayerEntityMixin {
    @Accessor("gameProfile")
    public abstract GameProfile unknownmod$getGameProfile();

    @Accessor("gameProfile")
    public abstract void unknownmod$setGameProfile(GameProfile profile);
}
