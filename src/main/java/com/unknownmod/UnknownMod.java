package com.unknownmod;

import com.unknownmod.command.UnknownCommand;
import com.unknownmod.command.PlayersCommand;
import com.unknownmod.config.ConfigManager;
import com.unknownmod.event.PlayerDeathHandler;
import com.unknownmod.state.ServerContextHolder;
import com.unknownmod.state.RevelationManager;
import com.unknownmod.util.AppearanceSyncManager;
import com.unknownmod.util.ProfileApplier;
import com.unknownmod.worldgen.ChunkOverrideGenerator;
import com.unknownmod.worldgen.ChunkWorldgenManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UnknownMod implements ModInitializer {
    public static final String MOD_ID = "unknownmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Unknown Mod is initializing!");
        ConfigManager.load();
        ChunkWorldgenManager.load();
        PlayerDeathHandler.register();
        RevelationManager.register();
        AppearanceSyncManager.register();
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            var server = ServerContextHolder.getServer();
            if (server == null) {
                return;
            }

            ProfileApplier.refreshPlayer(server, newPlayer);
        });
        ChunkOverrideGenerator.register();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            UnknownCommand.register(dispatcher);
            PlayersCommand.register(dispatcher);
        });
    }
}
