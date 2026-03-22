package com.unknownmod.state;

import net.minecraft.server.MinecraftServer;

public final class ServerContextHolder {
    private static volatile MinecraftServer server;

    private ServerContextHolder() {
    }

    public static void setServer(MinecraftServer minecraftServer) {
        server = minecraftServer;
    }

    public static MinecraftServer getServer() {
        return server;
    }
}
