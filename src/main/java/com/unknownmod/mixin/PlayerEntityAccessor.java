package com.unknownmod.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;

@Mixin(Player.class)
public interface PlayerEntityAccessor {
    @Accessor("gameProfile")
    GameProfile getGameProfile();

    /**
     * Set the gameProfile field on a Player instance.
     * Uses VarHandle (Java 9+) which works reliably with final fields in Java 21+,
     * unlike sun.misc.Unsafe which requires extra --add-opens and may be restricted.
     */
    static void setGameProfile(Player player, GameProfile profile) {
        try {
            Field f = Player.class.getDeclaredField("gameProfile");
            f.setAccessible(true);
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(Player.class, MethodHandles.lookup());
            VarHandle handle = lookup.findVarHandle(Player.class, "gameProfile", GameProfile.class);
            handle.set(player, profile);
        } catch (Exception e) {
            throw new RuntimeException("Cannot set gameProfile on Player via VarHandle", e);
        }
    }
}
