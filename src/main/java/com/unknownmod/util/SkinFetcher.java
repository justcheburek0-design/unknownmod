package com.unknownmod.util;

import com.mojang.authlib.properties.Property;
import net.lionarius.skinrestorer.SkinRestorer;
import net.lionarius.skinrestorer.skin.SkinVariant;
import net.lionarius.skinrestorer.skin.provider.SkinProvider;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class SkinFetcher {
    private static final ConcurrentMap<String, Optional<SkinData>> CACHE = new ConcurrentHashMap<>();

    private SkinFetcher() {}

    public static class SkinData {
        public final String value;
        public final String signature;

        public SkinData(String value, String signature) {
            this.value = value;
            this.signature = signature;
        }
    }

    public static SkinData fetchTexturesByNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return null;
        }

        String cacheKey = nickname.toLowerCase(Locale.ROOT);
        Optional<SkinData> cached = CACHE.computeIfAbsent(cacheKey, key -> Optional.ofNullable(fetchTexturesByNicknameUncached(nickname)));
        return cached.orElse(null);
    }

    private static SkinData fetchTexturesByNicknameUncached(String nickname) {
        try {
            Optional<SkinProvider> provider = SkinRestorer.getProvider("mojang");
            if (provider.isEmpty()) {
                return null;
            }

            var result = provider.get().fetchSkin(nickname, SkinVariant.CLASSIC);
            if (result == null || result.isError()) {
                return null;
            }

            Optional<Property> textures = result.getSuccessValue();
            if (textures == null || textures.isEmpty()) {
                return null;
            }

            Property property = textures.get();
            if (property.value() == null || property.value().isBlank()) {
                return null;
            }

            return new SkinData(property.value(), property.signature());
        } catch (Exception ignored) {
            return null;
        }
    }
}
