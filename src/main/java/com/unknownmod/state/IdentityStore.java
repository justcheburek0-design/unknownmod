package com.unknownmod.state;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.ArrayList;
import java.util.List;

public final class IdentityStore {
    private static final int MAX_STORED_PROFILES = 500;
    private static final ConcurrentMap<UUID, GameProfile> ORIGINAL_PROFILES = new ConcurrentHashMap<>();

    private IdentityStore() {
    }

    public static void remember(GameProfile profile) {
        if (profile == null || profile.id() == null) {
            return;
        }

        // Evict oldest entries if capacity exceeded
        if (ORIGINAL_PROFILES.size() >= MAX_STORED_PROFILES && !ORIGINAL_PROFILES.containsKey(profile.id())) {
            // Remove one arbitrary entry to make room (ConcurrentHashMap doesn't maintain order,
            // but this prevents unbounded growth)
            ORIGINAL_PROFILES.keySet().stream().findFirst().ifPresent(ORIGINAL_PROFILES::remove);
        }

        ORIGINAL_PROFILES.putIfAbsent(profile.id(), copy(profile));
    }

    public static void forget(UUID uuid) {
        if (uuid != null) {
            ORIGINAL_PROFILES.remove(uuid);
        }
    }

    public static Optional<GameProfile> get(UUID uuid) {
        GameProfile profile = ORIGINAL_PROFILES.get(uuid);
        return profile == null ? Optional.empty() : Optional.of(copy(profile));
    }

    public static Optional<String> getOriginalName(UUID uuid) {
        GameProfile profile = ORIGINAL_PROFILES.get(uuid);
        if (profile == null || profile.name() == null || profile.name().isBlank()) {
            return Optional.empty();
        }

        return Optional.of(profile.name());
    }

    public static Optional<ServerPlayer> findOnlinePlayerByOriginalName(MinecraftServer server, String name) {
        if (server == null || name == null || name.isBlank()) {
            return Optional.empty();
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            GameProfile profile = ORIGINAL_PROFILES.get(player.getUUID());
            String originalName = profile == null ? player.getGameProfile().name() : profile.name();
            if (originalName != null && originalName.equalsIgnoreCase(name)) {
                return Optional.of(player);
            }
        }

        return Optional.empty();
    }

    public static List<String> getKnownPlayerNames(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        if (server == null) {
            return names;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            GameProfile profile = ORIGINAL_PROFILES.get(player.getUUID());
            String originalName = profile == null ? player.getGameProfile().name() : profile.name();
            if (originalName != null && !originalName.isBlank()) {
                names.add(originalName);
            }
        }

        return names;
    }

    private static GameProfile copy(GameProfile profile) {
        Multimap<String, Property> multimap = HashMultimap.create();
        for (var entry : profile.properties().entries()) {
            multimap.put(entry.getKey(), entry.getValue());
        }
        return new GameProfile(profile.id(), profile.name(), new PropertyMap(multimap));
    }
}
