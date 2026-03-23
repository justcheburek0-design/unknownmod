package com.unknownmod.util;

import com.unknownmod.UnknownMod;
import com.unknownmod.config.ConfigManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

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

        Text text = Text.literal(PREFIX + message);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (server.getPlayerManager().isOperator(new PlayerConfigEntry(player.getGameProfile()))) {
                player.sendMessage(text, false);
            }
        }
    }
}
