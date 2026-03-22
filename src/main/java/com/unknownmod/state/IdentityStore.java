package com.unknownmod.state;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class IdentityStore {
    private static final ConcurrentMap<UUID, GameProfile> ORIGINAL_PROFILES = new ConcurrentHashMap<>();

    private IdentityStore() {
    }

    public static void remember(GameProfile profile) {
        if (profile == null || profile.id() == null) {
            return;
        }

        ORIGINAL_PROFILES.putIfAbsent(profile.id(), copy(profile));
    }

    public static Optional<GameProfile> get(UUID uuid) {
        GameProfile profile = ORIGINAL_PROFILES.get(uuid);
        return profile == null ? Optional.empty() : Optional.of(copy(profile));
    }

    private static GameProfile copy(GameProfile profile) {
        GameProfile copy = new GameProfile(profile.id(), profile.name());

        Multimap<String, Property> multimap = HashMultimap.create();
        for (var entry : profile.properties().entries()) {
            multimap.put(entry.getKey(), entry.getValue());
        }

        ((com.unknownmod.mixin.GameProfileAccessor) (Object) copy).setProperties(new PropertyMap(multimap));
        return copy;
    }
}
