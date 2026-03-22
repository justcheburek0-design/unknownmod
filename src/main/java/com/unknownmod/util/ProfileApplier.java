package com.unknownmod.util;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.unknownmod.config.ConfigManager;
import com.unknownmod.config.UnknownConfig;
import com.unknownmod.mixin.GameProfileAccessor;
import com.unknownmod.mixin.PlayerEntityAccessor;
import com.unknownmod.state.IdentityStore;
import com.unknownmod.state.RevelationManager;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.List;

public final class ProfileApplier {
    private ProfileApplier() {
    }

    public static void rememberOriginalProfile(GameProfile profile) {
        IdentityStore.remember(profile);
    }

    public static void refreshAllOnline(MinecraftServer server) {
        UnknownConfig config = ConfigManager.getConfig();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            applyCurrentProfile(server, player, config);
        }

        syncPlayerList(server);
    }

    public static void applyCurrentProfile(MinecraftServer server, ServerPlayerEntity player, UnknownConfig config) {
        if (RevelationManager.isRevealed(server, player.getUuid())) {
            applyOriginalProfile(player);
            return;
        }

        applyAnonymousProfile(player, config);
    }

    public static boolean applyOriginalProfile(ServerPlayerEntity player) {
        return IdentityStore.get(player.getUuid())
                .map(profile -> {
                    GameProfile originalProfile = profile;
                    if (originalProfile.properties().get("textures").isEmpty()) {
                        String nickname = originalProfile.name();
                        if (nickname != null && !nickname.isBlank()) {
                            SkinFetcher.SkinData data = SkinFetcher.fetchTexturesByNickname(nickname);
                            if (data != null && !data.value.isBlank() && !data.signature.isBlank()) {
                                GameProfile refreshedProfile = new GameProfile(originalProfile.id(), originalProfile.name());
                                Multimap<String, Property> multimap = HashMultimap.create();
                                for (var entry : originalProfile.properties().entries()) {
                                    multimap.put(entry.getKey(), entry.getValue());
                                }
                                multimap.put("textures", new Property("textures", data.value, data.signature));
                                ((GameProfileAccessor) (Object) refreshedProfile).setProperties(new PropertyMap(multimap));
                                originalProfile = refreshedProfile;
                            }
                        }
                    }

                    ((PlayerEntityAccessor) player).setGameProfile(originalProfile);
                    return true;
                })
                .orElse(false);
    }

    public static void applyAnonymousProfile(ServerPlayerEntity player, UnknownConfig config) {
        GameProfile baseProfile = IdentityStore.get(player.getUuid()).orElse(player.getGameProfile());
        String anonymousName = baseProfile.name();
        if (config.anonymous != null && config.anonymous.name != null && !config.anonymous.name.isBlank()) {
            anonymousName = config.anonymous.name;
        }

        GameProfile newProfile = new GameProfile(baseProfile.id(), anonymousName);
        Multimap<String, Property> multimap = HashMultimap.create();

        for (var entry : baseProfile.properties().entries()) {
            if (!"textures".equals(entry.getKey())) {
                multimap.put(entry.getKey(), entry.getValue());
            }
        }

        Property texturesProp = resolveTextures(config);
        if (texturesProp != null) {
            multimap.put("textures", texturesProp);
        } else {
            for (var entry : baseProfile.properties().entries()) {
                if ("textures".equals(entry.getKey())) {
                    multimap.put(entry.getKey(), entry.getValue());
                }
            }
        }

        ((GameProfileAccessor) (Object) newProfile).setProperties(new PropertyMap(multimap));
        ((PlayerEntityAccessor) player).setGameProfile(newProfile);
    }

    private static void syncPlayerList(MinecraftServer server) {
        List<ServerPlayerEntity> players = server.getPlayerManager().getPlayerList();
        if (players.isEmpty()) {
            return;
        }

        server.getPlayerManager().sendToAll(PlayerListS2CPacket.entryFromPlayer(players));
    }

    private static Property resolveTextures(UnknownConfig config) {
        if (config.anonymous == null || config.anonymous.skin == null) {
            return null;
        }

        UnknownConfig.SkinSettings skin = config.anonymous.skin;
        String type = skin.type == null ? "" : skin.type;

        if ("texture".equalsIgnoreCase(type)) {
            if (skin.texture != null && !skin.texture.isBlank() && skin.signature != null && !skin.signature.isBlank()) {
                return new Property("textures", skin.texture, skin.signature);
            }
            return null;
        }

        if ("nickname".equalsIgnoreCase(type) && skin.nickname != null && !skin.nickname.isBlank()) {
            SkinFetcher.SkinData data = SkinFetcher.fetchTexturesByNickname(skin.nickname);
            if (data != null) {
                return new Property("textures", data.value, data.signature);
            }
        }

        return null;
    }
}
