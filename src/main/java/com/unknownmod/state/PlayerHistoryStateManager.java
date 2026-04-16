package com.unknownmod.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class PlayerHistoryStateManager extends PersistentState {
    private final Map<UUID, LastSeenEntry> entries;

    public static final Codec<PlayerHistoryStateManager> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    LastSeenEntry.CODEC.listOf().optionalFieldOf("players", List.of()).forGetter(PlayerHistoryStateManager::getEntriesForCodec)
            ).apply(instance, PlayerHistoryStateManager::new)
    );

    public static final PersistentStateType<PlayerHistoryStateManager> TYPE = new PersistentStateType<>(
            "unknownmod_player_history",
            PlayerHistoryStateManager::new,
            CODEC,
            null
    );

    public PlayerHistoryStateManager() {
        this(List.of());
    }

    private PlayerHistoryStateManager(List<LastSeenEntry> storedEntries) {
        this.entries = new LinkedHashMap<>();
        if (storedEntries == null) {
            return;
        }

        for (LastSeenEntry entry : storedEntries) {
            if (entry == null) {
                continue;
            }

            entry.getUuid().ifPresent(uuid -> this.entries.put(uuid, entry));
        }
    }

    public static PlayerHistoryStateManager getServerState(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
    }

    public void recordJoin(UUID uuid, String originalName, long joinedAtMillis) {
        if (uuid == null || originalName == null || originalName.isBlank()) {
            return;
        }

        entries.put(uuid, new LastSeenEntry(uuid.toString(), originalName, joinedAtMillis));
        markDirty();
    }

    public List<String> getRecentPlayerNames(long cutoffMillis) {
        return entries.values().stream()
                .filter(entry -> entry.lastSeenAtMillis >= cutoffMillis)
                .sorted(Comparator.comparingLong(LastSeenEntry::getLastSeenAtMillis).reversed())
                .map(LastSeenEntry::getPlayerName)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }

    private List<LastSeenEntry> getEntriesForCodec() {
        return new ArrayList<>(entries.values());
    }

    public static final class LastSeenEntry {
        private final String playerUuid;
        private final String playerName;
        private final long lastSeenAtMillis;

        public static final Codec<LastSeenEntry> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.STRING.optionalFieldOf("player_uuid", "").forGetter(entry -> entry.playerUuid),
                        Codec.STRING.optionalFieldOf("player_name", "").forGetter(entry -> entry.playerName),
                        Codec.LONG.optionalFieldOf("last_seen_at_millis", 0L).forGetter(LastSeenEntry::getLastSeenAtMillis)
                ).apply(instance, LastSeenEntry::new)
        );

        public LastSeenEntry() {
            this("", "", 0L);
        }

        private LastSeenEntry(String playerUuid, String playerName, long lastSeenAtMillis) {
            this.playerUuid = playerUuid == null ? "" : playerUuid;
            this.playerName = playerName == null ? "" : playerName;
            this.lastSeenAtMillis = lastSeenAtMillis;
        }

        public Optional<UUID> getUuid() {
            if (playerUuid.isBlank()) {
                return Optional.empty();
            }

            try {
                return Optional.of(UUID.fromString(playerUuid));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }

        public String getPlayerName() {
            return playerName;
        }

        public long getLastSeenAtMillis() {
            return lastSeenAtMillis;
        }
    }
}
