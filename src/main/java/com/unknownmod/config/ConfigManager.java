package com.unknownmod.config;

import com.unknownmod.UnknownMod;
import com.unknownmod.util.SkinFetcher;
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
            boolean configExisted = Files.exists(CONFIG_PATH);
            CommentedConfigurationNode root = loader.load();
            config = root.get(UnknownConfig.class);

            if (config == null) {
                config = new UnknownConfig();
            }

            boolean migratedSkin = normalizeAnonymousSkin(root, config);
            if (migratedSkin || !configExisted) {
                save();
            }
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

    private static boolean normalizeAnonymousSkin(CommentedConfigurationNode root, UnknownConfig config) {
        if (config == null || config.anonymous == null || config.anonymous.skin == null) {
            return false;
        }

        UnknownConfig.SkinSettings skin = config.anonymous.skin;
        boolean hasCachedTexture = skin.texture != null && !skin.texture.isBlank()
                && skin.signature != null && !skin.signature.isBlank();

        if (hasCachedTexture) {
            return false;
        }

        String type = readLegacyString(root, "anonymous", "skin", "type");
        String nickname = readLegacyString(root, "anonymous", "skin", "nickname");
        if (("skin".equalsIgnoreCase(type) || "nickname".equalsIgnoreCase(type))
                && nickname != null && !nickname.isBlank()) {
            SkinFetcher.SkinData resolved = SkinFetcher.fetchTexturesByNickname(nickname);
            if (resolved != null && !resolved.value.isBlank() && !resolved.signature.isBlank()) {
                skin.texture = resolved.value;
                skin.signature = resolved.signature;
                return true;
            }
        }

        return false;
    }

    private static String readLegacyString(CommentedConfigurationNode root, String... path) {
        CommentedConfigurationNode node = root;
        for (String segment : path) {
            if (node == null) {
                return "";
            }
            node = node.node(segment);
        }

        if (node == null || node.virtual()) {
            return "";
        }

        String value = node.getString();
        return value == null ? "" : value;
    }
}
