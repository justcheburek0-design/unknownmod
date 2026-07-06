package com.unknownmod.util;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Field;

/**
 * Utility for mutating Player.gameProfile via reflection.
 * Accessor mixins on Player.class cause conflicts with Sodium (both use interface-type mixins).
 * Reflection approach bypasses mixin system entirely.
 */
public final class GameProfileUtil {
    private static final Field GAME_PROFILE_FIELD;

    static {
        try {
            Field field = Player.class.getDeclaredField("gameProfile");
            field.setAccessible(true);
            GAME_PROFILE_FIELD = field;
        } catch (NoSuchFieldException e) {
            throw new RuntimeException("Failed to find gameProfile field on Player", e);
        }
    }

    private GameProfileUtil() {}

    /**
     * Replace the gameProfile field on a Player instance.
     */
    public static void setGameProfile(Player player, GameProfile profile) {
        try {
            GAME_PROFILE_FIELD.set(player, profile);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to set gameProfile on Player", e);
        }
    }

    /**
     * Get the gameProfile field from a Player instance.
     */
    public static GameProfile getGameProfile(Player player) {
        try {
            return (GameProfile) GAME_PROFILE_FIELD.get(player);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to get gameProfile from Player", e);
        }
    }
}