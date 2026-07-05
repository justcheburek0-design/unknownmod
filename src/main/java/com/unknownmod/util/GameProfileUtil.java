package com.unknownmod.util;

import com.mojang.authlib.GameProfile;
import com.unknownmod.mixin.PlayerEntityMixin;
import net.minecraft.world.entity.player.Player;

/**
 * Utility for mutating Player.gameProfile through the generated Mixin accessor.
 * Uses static @Accessor methods from PlayerEntityMixin — no interface casting.
 * Do not use low-level final-field writes here: on Java 25 they throw
 * UnsupportedOperationException and cause "Invalid player data" during login.
 */
public final class GameProfileUtil {

    private GameProfileUtil() {}

    /**
     * Replace the gameProfile field on a Player instance.
     */
    public static void setGameProfile(Player player, GameProfile profile) {
        PlayerEntityMixin.setGameProfile(player, profile);
    }

    /**
     * Get the gameProfile field from a Player instance.
     */
    public static GameProfile getGameProfile(Player player) {
        return PlayerEntityMixin.getGameProfile(player);
    }
}
