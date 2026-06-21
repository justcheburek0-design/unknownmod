package com.unknownmod.util;

import com.unknownmod.UnknownMod;
import com.unknownmod.config.ConfigManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;


public final class DebugMessenger {
    private static final String PREFIX = "[UnknownMod][Debug] ";

    private DebugMessenger() {
    }

    public static boolean isEnabled() {
        return ConfigManager.getConfig().debug.enabled;
    }

    public static void debug(MinecraftServer server, String message) {
        if (isEnabled()) {
            announce(server, message);
        }
    }

    public static void announce(MinecraftServer server, String message) {
        if (message == null || message.isBlank()) {
            return;
        }

        UnknownMod.LOGGER.info(PREFIX + message);
        if (server == null) {
            return;
        }

        Component text = Component.literal(PREFIX + message);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (server.getPlayerList().isOp(new NameAndId(player.getGameProfile()))) {
                player.sendSystemMessage(text);
            }
        }
    }
}
