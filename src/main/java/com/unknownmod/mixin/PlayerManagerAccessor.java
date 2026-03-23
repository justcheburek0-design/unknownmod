package com.unknownmod.mixin;

import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(net.minecraft.server.PlayerManager.class)
public interface PlayerManagerAccessor {
    @Invoker("sendScoreboard")
    void unknownmod$sendScoreboard(ServerScoreboard scoreboard, ServerPlayerEntity player);
}
