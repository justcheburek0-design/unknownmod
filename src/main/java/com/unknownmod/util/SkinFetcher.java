package com.unknownmod.util;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.ProfileResult;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.authlib.yggdrasil.response.NameAndId;

import java.net.Proxy;
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
            YggdrasilAuthenticationService authService = new YggdrasilAuthenticationService(Proxy.NO_PROXY);
            GameProfileRepository profileRepository = authService.createProfileRepository();
            Optional<NameAndId> profileOptional = profileRepository.findProfileByName(nickname);
            if (profileOptional.isEmpty()) {
                return null;
            }

            NameAndId nameAndId = profileOptional.get();
            GameProfile profile = new GameProfile(nameAndId.id(), nameAndId.name());
            MinecraftSessionService sessionService = authService.createMinecraftSessionService();
            ProfileResult result = sessionService.fetchProfile(profile.id(), true);
            if (result == null || result.profile() == null) {
                return null;
            }

            GameProfile resolvedProfile = result.profile();
            Property textures = resolvedProfile.properties().get("textures").stream().findFirst().orElse(null);
            if (textures == null) {
                textures = sessionService.getPackedTextures(resolvedProfile);
            }
            if (textures == null) {
                return null;
            }

            return new SkinData(textures.value(), textures.signature());
        } catch (Exception ignored) {
            return null;
        }
    }
}
