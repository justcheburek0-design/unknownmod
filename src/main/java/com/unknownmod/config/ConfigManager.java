package com.unknownmod.config;

import com.unknownmod.UnknownMod;
import net.fabricmc.loader.api.FabricLoader;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;
import org.spongepowered.configurate.yaml.NodeStyle;

import java.nio.file.Path;

public class ConfigManager {
    private static UnknownConfig config = new UnknownConfig();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("unknown.yml");

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
            save(); // Сохраняем значения по умолчанию, если файл пустой
        } catch (Exception e) {
            UnknownMod.LOGGER.error("Не удалось загрузить конфигурацию unknown.yml", e);
        }
    }

    public static void save() {
        try {
            CommentedConfigurationNode root = loader.createNode();
            root.set(UnknownConfig.class, config);
            loader.save(root);
        } catch (Exception e) {
            UnknownMod.LOGGER.error("Не удалось сохранить конфигурацию unknown.yml", e);
        }
    }

    public static UnknownConfig getConfig() {
        return config;
    }
}
