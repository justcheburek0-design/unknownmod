package com.unknownmod.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;

/**
 * Interface for accessing Player.gameProfile field.
 * This is NOT a mixin — it's a plain interface that gets added to Player
 * via PlayerEntityMixin (class-type mixin with @Implements).
 * Using a plain interface avoids the interface-mixin conflict with Sodium
 * (both try interface-type @Mixin on Player.class).
 */
public interface PlayerEntityAccessor {
    GameProfile getGameProfile();
    void setGameProfile(GameProfile profile);
}
