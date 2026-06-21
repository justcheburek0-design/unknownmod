package com.unknownmod.mixin;

import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(net.minecraft.server.players.PlayerList.class)
public interface PlayerManagerAccessor {
    @Invoker("updateEntireScoreboard")
    void unknownmod$sendScoreboard(ServerScoreboard scoreboard, ServerPlayer player);
}
