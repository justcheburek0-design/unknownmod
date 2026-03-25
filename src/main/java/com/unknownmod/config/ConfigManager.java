package com.unknownmod.config;

import com.unknownmod.UnknownMod;
import net.fabricmc.loader.api.FabricLoader;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.NodeStyle;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigManager {
    private static UnknownConfig config = new UnknownConfig();
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("unknown");
    private static final Path CONFIG_PATH = CONFIG_DIR.resolve("config.yml");

    private static final YamlConfigurationLoader loader = YamlConfigurationLoader.builder()
            .path(CONFIG_PATH)
            .nodeStyle(NodeStyle.BLOCK)
            .build();

    public static void load() {
        try {
            CommentedConfigurationNode root = loader.load();
            config = root.get(UnknownConfig.class);

            if (config == null) {
                config = new UnknownConfig();
            }

            save();
        } catch (Exception e) {
            UnknownMod.LOGGER.error("Failed to load config from " + CONFIG_PATH, e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            CommentedConfigurationNode root = loader.createNode();
            root.set(UnknownConfig.class, config);
            loader.save(root);
        } catch (Exception e) {
            UnknownMod.LOGGER.error("Failed to save config to " + CONFIG_PATH, e);
        }
    }

    public static UnknownConfig getConfig() {
        return config;
    }
}
