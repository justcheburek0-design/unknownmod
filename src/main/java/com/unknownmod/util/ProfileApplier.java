package com.unknownmod.util;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.unknownmod.config.ConfigManager;
import com.unknownmod.config.UnknownConfig;
import com.unknownmod.mixin.PlayerListS2CPacketAccessor;
import com.unknownmod.state.IdentityStore;
import com.unknownmod.state.RevelationManager;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

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

    public static String getDisplayName(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null) {
            return "";
        }

        return getDisplayName(server, player.getUUID(), player.getName().getString());
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

    public static boolean personalizePlayerListPacket(MinecraftServer server, ClientboundPlayerInfoUpdatePacket packet, UUID viewerUuid) {
        List<ClientboundPlayerInfoUpdatePacket.Entry> originalEntries = packet.entries();
        List<ClientboundPlayerInfoUpdatePacket.Entry> patchedEntries = new java.util.ArrayList<>(originalEntries.size());
        boolean changed = false;

        for (ClientboundPlayerInfoUpdatePacket.Entry entry : originalEntries) {
            Component displayName = entry.displayName();
            if (server != null && RevelationManager.isRevealed(server, entry.profileId())) {
                ServerPlayer revealedPlayer = server.getPlayerList().getPlayer(entry.profileId());
                String revealedName = revealedPlayer != null ? revealedPlayer.getName().getString() : entry.profile().name();
                if (revealedName != null && !revealedName.isBlank()) {
                    displayName = Component.literal(revealedName);
                    changed = true;
                }
            }

            if (viewerUuid.equals(entry.profileId())) {
                GameProfile originalProfile = getOriginalProfile(viewerUuid);
                if (originalProfile != null) {
                    patchedEntries.add(new ClientboundPlayerInfoUpdatePacket.Entry(
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
                patchedEntries.add(new ClientboundPlayerInfoUpdatePacket.Entry(
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

    /**
     * Server-side: anonymize all entries in a ClientboundPlayerInfoUpdatePacket.
     * Replaces each non-revealed player's GameProfile with anonymous name + configured skin.
     * This is the single source of truth for profile masking — works regardless of
     * whether the client has the mod installed.
     */
    public static boolean anonymizePlayerInfoPacket(MinecraftServer server, ClientboundPlayerInfoUpdatePacket packet, UnknownConfig config) {
        List<ClientboundPlayerInfoUpdatePacket.Entry> originalEntries = packet.entries();
        List<ClientboundPlayerInfoUpdatePacket.Entry> patchedEntries = null;

        for (int i = 0; i < originalEntries.size(); i++) {
            ClientboundPlayerInfoUpdatePacket.Entry entry = originalEntries.get(i);

            // Skip revealed players
            if (server != null && RevelationManager.isRevealed(server, entry.profileId())) {
                continue;
            }

            GameProfile originalProfile = entry.profile();

            // Determine anonymous name
            String anonymousName = (config.anonymous != null && config.anonymous.name != null && !config.anonymous.name.isBlank())
                    ? config.anonymous.name
                    : "JustPlayer";

            // Determine skin: config > original
            Property texturesProp = resolveTextures(config);
            if (texturesProp == null) {
                texturesProp = getTextures(originalProfile);
            }

            // Build new GameProfile with anonymous name + skin
            Multimap<String, Property> multimap = HashMultimap.create();
            if (texturesProp != null) {
                multimap.put("textures", texturesProp);
            }
            // Copy non-texture properties from original
            for (var props : originalProfile.properties().entries()) {
                if (!"textures".equals(props.getKey())) {
                    multimap.put(props.getKey(), props.getValue());
                }
            }

            GameProfile anonymousProfile = new GameProfile(
                    originalProfile.id(),
                    anonymousName,
                    new PropertyMap(multimap)
            );

            ClientboundPlayerInfoUpdatePacket.Entry newEntry = new ClientboundPlayerInfoUpdatePacket.Entry(
                    entry.profileId(),
                    anonymousProfile,
                    entry.listed(),
                    entry.latency(),
                    entry.gameMode(),
                    entry.displayName(),
                    entry.showHat(),
                    entry.listOrder(),
                    entry.chatSession()
            );

            if (patchedEntries == null) {
                patchedEntries = new java.util.ArrayList<>(originalEntries);
            }
            patchedEntries.set(i, newEntry);
        }

        if (patchedEntries != null) {
            ((PlayerListS2CPacketAccessor) (Object) packet).setEntries(patchedEntries);
            return true;
        }
        return false;
    }

    public static void refreshAllOnline(MinecraftServer server) {
        if (server == null) {
            return;
        }

        UnknownConfig config = ConfigManager.getConfig();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            applyCurrentProfile(server, player, config);
        }

        AppearanceSyncManager.syncAllOnline(server);
    }

    public static void refreshPlayer(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null) {
            return;
        }

        applyCurrentProfile(server, player, ConfigManager.getConfig());
        AppearanceSyncManager.syncPlayer(server, player);
    }

    /**
     * Apply current profile state for a player.
     * NOTE: We no longer mutate the server-side gameProfile directly.
     * All anonymity is handled via PacketS2CFilterMixin which intercepts
     * outgoing ClientboundPlayerInfoUpdatePacket packets and anonymizes them.
     * This method only triggers a packet resync so clients see the latest state.
     */
    public static void applyCurrentProfile(MinecraftServer server, ServerPlayer player, UnknownConfig config) {
        // Profile mutability removed — packet-level anonymization is the single source of truth.
        // Just trigger appearance sync to resend player info packets.
        DebugMessenger.debug(server, LOG_PREFIX + " applyCurrentProfile called for " + player.getName().getString()
                + "; revealed=" + RevelationManager.isRevealed(server, player.getUUID()) + ".");
    }

    /**
     * @deprecated Use packet-level anonymization via PacketS2CFilterMixin instead.
     * Restoring the original profile on the server side is no longer needed
     * because we never mutate it in the first place.
     */
    @Deprecated
    public static boolean applyOriginalProfile(ServerPlayer player) {
        // No-op: server profile is never mutated, so no need to restore.
        DebugMessenger.debug(null, LOG_PREFIX + " applyOriginalProfile is a no-op (server profile unchanged) for uuid " + player.getUUID() + ".");
        return true;
    }

    /**
     * @deprecated Use packet-level anonymization via PacketS2CFilterMixin instead.
     */
    @Deprecated
    public static void applyAnonymousProfile(MinecraftServer server, ServerPlayer player, UnknownConfig config) {
        // No-op: packet-level anonymization handles everything.
        DebugMessenger.debug(server, LOG_PREFIX + " applyAnonymousProfile is a no-op (packet-level anonymization) for " + player.getName().getString() + ".");
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

        Multimap<String, Property> multimap = HashMultimap.create();
        for (var entry : profile.properties().entries()) {
            if (!"textures".equals(entry.getKey())) {
                multimap.put(entry.getKey(), entry.getValue());
            }
        }

        if (textures != null) {
            multimap.put("textures", textures);
        }

        return new GameProfile(profile.id(), profile.name(), new PropertyMap(multimap));
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
