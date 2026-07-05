package com.unknownmod.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Class-type mixin on Player that makes it implement PlayerEntityAccessor.
 * Uses class-type @Mixin (NOT interface-type), avoiding Sodium conflict.
 * 
 * The @Accessor methods are injected directly into Player.class and
 * satisfy the PlayerEntityAccessor interface contract.
 */
@Mixin(Player.class)
public abstract class PlayerEntityMixin implements PlayerEntityAccessor {
    @Accessor("gameProfile")
    @Override
    public abstract GameProfile getGameProfile();

    @Accessor("gameProfile")
    @Override
    public abstract void setGameProfile(GameProfile profile);
}
