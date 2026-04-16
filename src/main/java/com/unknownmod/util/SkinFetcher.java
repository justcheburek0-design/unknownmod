package com.unknownmod.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class SkinFetcher {
    private static final ConcurrentMap<String, Optional<SkinData>> CACHE = new ConcurrentHashMap<>();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final String USER_PROFILE_URL = "https://api.mojang.com/users/profiles/minecraft/%s";
    private static final String SESSION_PROFILE_URL = "https://sessionserver.mojang.com/session/minecraft/profile/%s?unsigned=false";

    private SkinFetcher() {
    }

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
        CACHE.putIfAbsent(cacheKey, Optional.ofNullable(fetched));
        return fetched;
    }

    private static SkinData fetchTexturesByNicknameUncached(String nickname) {
        try {
            UUID uuid = fetchUuidByNickname(nickname);
            if (uuid == null) {
                return null;
            }

            return fetchTexturesByUuid(uuid);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static UUID fetchUuidByNickname(String nickname) throws IOException, InterruptedException {
        String encodedName = URLEncoder.encode(nickname, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(USER_PROFILE_URL.formatted(encodedName)))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
            return null;
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!json.has("id")) {
            return null;
        }

        String rawUuid = json.get("id").getAsString();
        if (rawUuid == null || rawUuid.isBlank() || rawUuid.length() != 32) {
            return null;
        }

        return parseUuid(rawUuid);
    }

    private static SkinData fetchTexturesByUuid(UUID uuid) throws IOException, InterruptedException {
        String rawUuid = uuid.toString().replace("-", "");
        HttpRequest request = HttpRequest.newBuilder(URI.create(SESSION_PROFILE_URL.formatted(rawUuid)))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
            return null;
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!json.has("properties")) {
            return null;
        }

        for (JsonElement element : json.getAsJsonArray("properties")) {
            if (!element.isJsonObject()) {
                continue;
            }

            JsonObject property = element.getAsJsonObject();
            if (!property.has("name") || !"textures".equals(property.get("name").getAsString())) {
                continue;
            }

            String value = property.has("value") ? property.get("value").getAsString() : "";
            String signature = property.has("signature") ? property.get("signature").getAsString() : "";
            if (value == null || value.isBlank() || signature == null || signature.isBlank()) {
                return null;
            }

            return new SkinData(value, signature);
        }

        return null;
    }

    private static UUID parseUuid(String rawUuid) {
        String normalized = rawUuid.replaceFirst(
                "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}+)",
                "$1-$2-$3-$4-$5"
        );
        return UUID.fromString(normalized);
    }
}
