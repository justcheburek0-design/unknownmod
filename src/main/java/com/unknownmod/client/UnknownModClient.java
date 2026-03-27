package com.unknownmod.client;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UnknownModClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("unknownmod");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Unknown Mod client initialized.");
    }
}
