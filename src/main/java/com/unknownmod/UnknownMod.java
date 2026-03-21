package com.unknownmod;

import com.unknownmod.config.ConfigManager;
import com.unknownmod.event.PlayerDeathHandler;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UnknownMod implements ModInitializer {
    public static final String MOD_ID = "unknownmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Unknown Mod is initializing!");
        ConfigManager.load();
        PlayerDeathHandler.register();
    }
}
