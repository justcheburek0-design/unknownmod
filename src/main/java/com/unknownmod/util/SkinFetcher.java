package com.unknownmod.util;

import com.mojang.authlib.properties.Property;

import java.lang.reflect.Method;
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
        Optional<SkinData> cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached.orElse(null);
        }

        SkinData fetched = fetchTexturesByNicknameUncached(nickname);
        if (fetched == null) {
            return null;
        }

        CACHE.putIfAbsent(cacheKey, Optional.of(fetched));
        return fetched;
    }

    private static SkinData fetchTexturesByNicknameUncached(String nickname) {
        try {
            Class<?> skinRestorerClass = Class.forName("net.lionarius.skinrestorer.SkinRestorer");
            Method getProvider = skinRestorerClass.getMethod("getProvider", String.class);
            Optional<?> provider = (Optional<?>) getProvider.invoke(null, "mojang");
            if (provider == null || provider.isEmpty()) {
                return null;
            }

            Object providerInstance = provider.get();
            Class<?> skinVariantClass = Class.forName("net.lionarius.skinrestorer.skin.SkinVariant");
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object classicVariant = Enum.valueOf((Class) skinVariantClass.asSubclass(Enum.class), "CLASSIC");

            Method fetchSkin = providerInstance.getClass().getMethod("fetchSkin", String.class, skinVariantClass);
            Object result = fetchSkin.invoke(providerInstance, nickname, classicVariant);
            if (result == null) {
                return null;
            }

            Method isError = result.getClass().getMethod("isError");
            if (Boolean.TRUE.equals(isError.invoke(result))) {
                return null;
            }

            Method getSuccessValue = result.getClass().getMethod("getSuccessValue");
            Optional<Property> textures = (Optional<Property>) getSuccessValue.invoke(result);
            if (textures == null || textures.isEmpty()) {
                return null;
            }

            Property property = textures.get();
            if (property.value() == null || property.value().isBlank()) {
                return null;
            }

            if (property.signature() == null || property.signature().isBlank()) {
                return null;
            }

            return new SkinData(property.value(), property.signature());
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
