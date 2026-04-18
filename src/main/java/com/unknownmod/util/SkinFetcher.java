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
    private static final String LOG_PREFIX = "[skin]";
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
            DebugMessenger.debug(null, LOG_PREFIX + " cache hit for nickname '" + nickname + "': " + (cached.isPresent() ? "hit" : "miss"));
            return cached.orElse(null);
        }

        DebugMessenger.debug(null, LOG_PREFIX + " resolving nickname '" + nickname + "'.");
        SkinData fetched = fetchTexturesByNicknameUncached(nickname);
        CACHE.putIfAbsent(cacheKey, Optional.ofNullable(fetched));
        if (fetched == null) {
            DebugMessenger.debug(null, LOG_PREFIX + " resolution failed for nickname '" + nickname + "'.");
        } else {
            DebugMessenger.debug(null, LOG_PREFIX + " resolution succeeded for nickname '" + nickname + "'; valueLen=" + fetched.value.length() + ", signatureLen=" + fetched.signature.length() + ".");
        }
        return fetched;
    }

    private static SkinData fetchTexturesByNicknameUncached(String nickname) {
        try {
            UUID uuid = fetchUuidByNickname(nickname);
            if (uuid == null) {
                DebugMessenger.debug(null, LOG_PREFIX + " mojang profile lookup returned no UUID for nickname '" + nickname + "'.");
                return null;
            }

            DebugMessenger.debug(null, LOG_PREFIX + " nickname '" + nickname + "' resolved to uuid " + uuid + ".");
            return fetchTexturesByUuid(uuid);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            DebugMessenger.debug(null, LOG_PREFIX + " nickname lookup interrupted for '" + nickname + "'.");
            return null;
        } catch (IOException | RuntimeException ignored) {
            DebugMessenger.debug(null, LOG_PREFIX + " nickname lookup failed with exception for '" + nickname + "'.");
            return null;
        }
    }

    private static UUID fetchUuidByNickname(String nickname) throws IOException, InterruptedException {
        String encodedName = URLEncoder.encode(nickname, StandardCharsets.UTF_8);
        DebugMessenger.debug(null, LOG_PREFIX + " requesting user profile for '" + nickname + "' as '" + encodedName + "'.");
        HttpRequest request = HttpRequest.newBuilder(URI.create(USER_PROFILE_URL.formatted(encodedName)))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
            DebugMessenger.debug(null, LOG_PREFIX + " user profile request returned status " + response.statusCode() + " for nickname '" + nickname + "'.");
            return null;
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!json.has("id")) {
            DebugMessenger.debug(null, LOG_PREFIX + " user profile response has no id for nickname '" + nickname + "'.");
            return null;
        }

        String rawUuid = json.get("id").getAsString();
        if (rawUuid == null || rawUuid.isBlank() || rawUuid.length() != 32) {
            DebugMessenger.debug(null, LOG_PREFIX + " user profile response contains invalid id '" + rawUuid + "' for nickname '" + nickname + "'.");
            return null;
        }

        return parseUuid(rawUuid);
    }

    private static SkinData fetchTexturesByUuid(UUID uuid) throws IOException, InterruptedException {
        String rawUuid = uuid.toString().replace("-", "");
        DebugMessenger.debug(null, LOG_PREFIX + " requesting session profile for uuid " + uuid + ".");
        HttpRequest request = HttpRequest.newBuilder(URI.create(SESSION_PROFILE_URL.formatted(rawUuid)))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200 || response.body() == null || response.body().isBlank()) {
            DebugMessenger.debug(null, LOG_PREFIX + " session profile request returned status " + response.statusCode() + " for uuid " + uuid + ".");
            return null;
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!json.has("properties")) {
            DebugMessenger.debug(null, LOG_PREFIX + " session profile response has no properties for uuid " + uuid + ".");
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
                DebugMessenger.debug(null, LOG_PREFIX + " textures property is incomplete for uuid " + uuid + " (valuePresent=" + (value != null && !value.isBlank()) + ", signaturePresent=" + (signature != null && !signature.isBlank()) + ").");
                return null;
            }

            DebugMessenger.debug(null, LOG_PREFIX + " textures property extracted for uuid " + uuid + "; valueLen=" + value.length() + ", signatureLen=" + signature.length() + ".");
            return new SkinData(value, signature);
        }

        DebugMessenger.debug(null, LOG_PREFIX + " textures property not found for uuid " + uuid + ".");
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
