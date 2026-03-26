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
import com.unknownmod.mixin.PlayerListS2CPacketAccessor;
import com.unknownmod.state.IdentityStore;
import com.unknownmod.state.RevelationManager;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;
import java.util.UUID;

public final class ProfileApplier {
    private ProfileApplier() {
    }

    public static void rememberOriginalProfile(GameProfile profile) {
        IdentityStore.remember(profile);
    }

    public static GameProfile getOriginalProfile(UUID uuid) {
        return IdentityStore.get(uuid).map(ProfileApplier::enrichOriginalProfile).orElse(null);
    }

    public static String getDisplayName(MinecraftServer server, ServerPlayerEntity player) {
        if (server == null || player == null) {
            return "";
        }

        return getDisplayName(server, player.getUuid(), player.getName().getString());
    }

    public static String getDisplayName(MinecraftServer server, UUID uuid, String fallbackName) {
        if (server != null && RevelationManager.isRevealed(server, uuid)) {
            if (fallbackName != null && !fallbackName.isBlank()) {
                return fallbackName;
            }
        }

        UnknownConfig config = ConfigManager.getConfig();
        if (config.anonymous != null && config.anonymous.name != null && !config.anonymous.name.isBlank()) {
            return config.anonymous.name;
        }

        return fallbackName == null ? "" : fallbackName;
    }

    public static boolean personalizePlayerListPacket(MinecraftServer server, PlayerListS2CPacket packet, UUID viewerUuid) {
        List<PlayerListS2CPacket.Entry> originalEntries = packet.getEntries();
        List<PlayerListS2CPacket.Entry> patchedEntries = new java.util.ArrayList<>(originalEntries.size());
        boolean changed = false;

        for (PlayerListS2CPacket.Entry entry : originalEntries) {
            Text displayName = entry.displayName();
            if (server != null && RevelationManager.isRevealed(server, entry.profileId())) {
                ServerPlayerEntity revealedPlayer = server.getPlayerManager().getPlayer(entry.profileId());
                String revealedName = revealedPlayer != null ? revealedPlayer.getName().getString() : entry.profile().name();
                if (revealedName != null && !revealedName.isBlank()) {
                    displayName = Text.literal(revealedName).formatted(Formatting.RED);
                    changed = true;
                }
            }

            if (viewerUuid.equals(entry.profileId())) {
                GameProfile originalProfile = getOriginalProfile(viewerUuid);
                if (originalProfile != null) {
                    patchedEntries.add(new PlayerListS2CPacket.Entry(
                            entry.profileId(),
                            originalProfile,
                            entry.listed(),
                            entry.latency(),
                            entry.gameMode(),
                            displayName,
                            entry.showHat(),
                            entry.listOrder(),
                            entry.chatSession()
                    ));
                    changed = true;
                    continue;
                }
            }

            if (displayName != entry.displayName()) {
                patchedEntries.add(new PlayerListS2CPacket.Entry(
                        entry.profileId(),
                        entry.profile(),
                        entry.listed(),
                        entry.latency(),
                        entry.gameMode(),
                        displayName,
                        entry.showHat(),
                        entry.listOrder(),
                        entry.chatSession()
                ));
                changed = true;
                continue;
            }

            patchedEntries.add(entry);
        }

        if (changed) {
            ((PlayerListS2CPacketAccessor) (Object) packet).setEntries(patchedEntries);
        }

        return changed;
    }

    public static void refreshAllOnline(MinecraftServer server) {
        if (server == null) {
            return;
        }

        UnknownConfig config = ConfigManager.getConfig();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            applyCurrentProfile(server, player, config);
        }

        AppearanceSyncManager.syncAllOnline(server);
    }

    public static void refreshPlayer(MinecraftServer server, ServerPlayerEntity player) {
        if (server == null || player == null) {
            return;
        }

        applyCurrentProfile(server, player, ConfigManager.getConfig());
        AppearanceSyncManager.syncPlayer(server, player);
    }

    public static void applyCurrentProfile(MinecraftServer server, ServerPlayerEntity player, UnknownConfig config) {
        if (RevelationManager.isRevealed(server, player.getUuid())) {
            applyOriginalProfile(player);
            return;
        }

        applyAnonymousProfile(server, player, config);
    }

    public static boolean applyOriginalProfile(ServerPlayerEntity player) {
        GameProfile originalProfile = getOriginalProfile(player.getUuid());
        if (originalProfile == null) {
            return false;
        }

        ((PlayerEntityAccessor) player).setGameProfile(originalProfile);
        return true;
    }

    public static void applyAnonymousProfile(MinecraftServer server, ServerPlayerEntity player, UnknownConfig config) {
        GameProfile baseProfile = getOriginalProfile(player.getUuid());
        if (baseProfile == null) {
            baseProfile = IdentityStore.get(player.getUuid()).orElse(player.getGameProfile());
        }
        String anonymousName = baseProfile.name();
        if (config.anonymous != null && config.anonymous.name != null && !config.anonymous.name.isBlank()) {
            anonymousName = config.anonymous.name;
        }

        GameProfile newProfile = new GameProfile(baseProfile.id(), anonymousName);
        Multimap<String, Property> multimap = HashMultimap.create();
        Property texturesProp = resolveTextures(config);

        for (var entry : baseProfile.properties().entries()) {
            if (!"textures".equals(entry.getKey())) {
                multimap.put(entry.getKey(), entry.getValue());
            }
        }

        if (texturesProp == null) {
            for (var entry : baseProfile.properties().entries()) {
                if ("textures".equals(entry.getKey())) {
                    multimap.put(entry.getKey(), entry.getValue());
                }
            }
        }

        ((GameProfileAccessor) (Object) newProfile).setProperties(new PropertyMap(multimap));
        ((PlayerEntityAccessor) player).setGameProfile(newProfile);
    }

    public static Property resolveTextures(UnknownConfig config) {
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

        if (("skin".equalsIgnoreCase(type) || "nickname".equalsIgnoreCase(type)) && skin.nickname != null && !skin.nickname.isBlank()) {
            SkinFetcher.SkinData data = SkinFetcher.fetchTexturesByNickname(skin.nickname);
            if (data != null) {
                return new Property("textures", data.value, data.signature);
            }
            if (skin.texture != null && !skin.texture.isBlank() && skin.signature != null && !skin.signature.isBlank()) {
                return new Property("textures", skin.texture, skin.signature);
            }
        }

        return null;
    }

    private static GameProfile enrichOriginalProfile(GameProfile originalProfile) {
        if (originalProfile == null) {
            return null;
        }

        if (!originalProfile.properties().get("textures").isEmpty()) {
            return originalProfile;
        }

        String nickname = originalProfile.name();
        if (nickname == null || nickname.isBlank()) {
            return originalProfile;
        }

        SkinFetcher.SkinData data = SkinFetcher.fetchTexturesByNickname(nickname);
        if (data == null || data.value.isBlank() || data.signature.isBlank()) {
            return originalProfile;
        }

        GameProfile refreshedProfile = new GameProfile(originalProfile.id(), originalProfile.name());
        Multimap<String, Property> multimap = HashMultimap.create();
        for (var entry : originalProfile.properties().entries()) {
            multimap.put(entry.getKey(), entry.getValue());
        }
        multimap.put("textures", new Property("textures", data.value, data.signature));
        ((GameProfileAccessor) (Object) refreshedProfile).setProperties(new PropertyMap(multimap));
        return refreshedProfile;
    }
}
