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
    private static final String LOG_PREFIX = "[profile]";
    private ProfileApplier() {
    }

    public static void rememberOriginalProfile(GameProfile profile) {
        IdentityStore.remember(profile);
    }

    public static GameProfile getOriginalProfile(UUID uuid) {
        return IdentityStore.get(uuid).orElse(null);
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
            DebugMessenger.debug(server, LOG_PREFIX + " applying original profile to revealed player " + player.getName().getString() + ".");
            applyOriginalProfile(player);
            return;
        }

        DebugMessenger.debug(server, LOG_PREFIX + " applying anonymous profile to player " + player.getName().getString() + ".");
        applyAnonymousProfile(server, player, config);
    }

    public static boolean applyOriginalProfile(ServerPlayerEntity player) {
        GameProfile originalProfile = getOriginalProfile(player.getUuid());
        if (originalProfile == null) {
            DebugMessenger.debug(null, LOG_PREFIX + " original profile not found for uuid " + player.getUuid() + ".");
            return false;
        }

        ((PlayerEntityAccessor) player).setGameProfile(originalProfile);
        DebugMessenger.debug(null, LOG_PREFIX + " original profile restored for uuid " + player.getUuid() + " (" + originalProfile.name() + ").");
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
        Property finalTextures = texturesProp != null ? texturesProp : getTextures(baseProfile);
        DebugMessenger.debug(server, LOG_PREFIX + " building anonymous profile for " + player.getName().getString()
                + "; baseName=" + baseProfile.name()
                + ", anonymousName=" + anonymousName
                + ", baseTextures=" + describeProfileTextures(baseProfile)
                + ", resolvedTextures=" + describeProperty(texturesProp)
                + ", finalTextures=" + describeProperty(finalTextures)
                + ".");

        for (var entry : baseProfile.properties().entries()) {
            if (!"textures".equals(entry.getKey())) {
                multimap.put(entry.getKey(), entry.getValue());
            }
        }

        if (finalTextures != null) {
            multimap.put("textures", finalTextures);
        }
        ((GameProfileAccessor) (Object) newProfile).setProperties(new PropertyMap(multimap));
        ((PlayerEntityAccessor) player).setGameProfile(newProfile);
        DebugMessenger.debug(server, LOG_PREFIX + " anonymous profile applied to " + player.getName().getString() + " with texturesCount=" + multimap.get("textures").size() + ".");
    }

    public static boolean hasTextures(GameProfile profile) {
        return getTextures(profile) != null;
    }

    public static Property getTextures(GameProfile profile) {
        if (profile == null) {
            return null;
        }

        return profile.properties().get("textures").stream().findFirst().orElse(null);
    }

    public static GameProfile copyWithTextures(GameProfile profile, Property textures) {
        if (profile == null) {
            return null;
        }

        GameProfile copy = new GameProfile(profile.id(), profile.name());
        Multimap<String, Property> multimap = HashMultimap.create();
        for (var entry : profile.properties().entries()) {
            if (!"textures".equals(entry.getKey())) {
                multimap.put(entry.getKey(), entry.getValue());
            }
        }

        if (textures != null) {
            multimap.put("textures", textures);
        }

        ((GameProfileAccessor) (Object) copy).setProperties(new PropertyMap(multimap));
        return copy;
    }

    public static Property resolveTextures(UnknownConfig config) {
        if (config.anonymous == null || config.anonymous.skin == null) {
            DebugMessenger.debug(null, LOG_PREFIX + " resolveTextures skipped: anonymous skin section missing.");
            return null;
        }

        UnknownConfig.SkinSettings skin = config.anonymous.skin;
        if (skin.texture != null && !skin.texture.isBlank()
                && skin.signature != null && !skin.signature.isBlank()) {
            DebugMessenger.debug(null, LOG_PREFIX + " resolveTextures succeeded; valueLen=" + skin.texture.length() + ", signatureLen=" + skin.signature.length() + ".");
            return new Property("textures", skin.texture, skin.signature);
        }

        DebugMessenger.debug(null, LOG_PREFIX + " resolveTextures returned null; texturePresent=" + (skin.texture != null && !skin.texture.isBlank())
                + ", signaturePresent=" + (skin.signature != null && !skin.signature.isBlank()) + ".");
        return null;
    }

    private static String describeProfileTextures(GameProfile profile) {
        return describeProperty(getTextures(profile));
    }

    private static String describeProperty(Property property) {
        if (property == null) {
            return "absent";
        }

        String value = property.value();
        String signature = property.signature();
        return "present(valueLen=" + (value == null ? 0 : value.length())
                + ", signatureLen=" + (signature == null ? 0 : signature.length()) + ")";
    }
}
