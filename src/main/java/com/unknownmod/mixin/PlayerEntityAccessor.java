package com.unknownmod.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import sun.misc.Unsafe;

import java.lang.reflect.Field;

@Mixin(Player.class)
public interface PlayerEntityAccessor {
    @Accessor("gameProfile")
    GameProfile getGameProfile();

    static void setGameProfile(Player player, GameProfile profile) {
        try {
            Field f = Player.class.getDeclaredField("gameProfile");
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Unsafe unsafe = (Unsafe) unsafeField.get(null);
            long offset = unsafe.objectFieldOffset(f);
            unsafe.getAndSetObject(player, offset, profile);
        } catch (Exception e) {
            throw new RuntimeException("Cannot set gameProfile on Player via Unsafe", e);
        }
    }
}
