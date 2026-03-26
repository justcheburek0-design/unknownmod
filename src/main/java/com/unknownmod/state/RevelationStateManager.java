package com.unknownmod.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class RevelationStateManager extends PersistentState {
    private final Map<UUID, RevealEntry> activeReveals;
    private long nextRevealAtMillis;
    private boolean warningSent;

    public static final Codec<RevelationStateManager> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    RevealEntry.CODEC.listOf().optionalFieldOf("active_reveals", List.of()).forGetter(RevelationStateManager::getActiveRevealEntries),
                    Codec.STRING.optionalFieldOf("revealed_player_uuid", "").forGetter(RevelationStateManager::getLegacyRevealedPlayerUuid),
                    Codec.STRING.optionalFieldOf("revealed_player_name", "").forGetter(RevelationStateManager::getLegacyRevealedPlayerName),
                    Codec.LONG.optionalFieldOf("next_reveal_at_millis", 0L).forGetter(state -> state.nextRevealAtMillis),
                    Codec.LONG.optionalFieldOf("reveal_ends_at_millis", 0L).forGetter(RevelationStateManager::getLegacyRevealEndsAtMillis),
                    Codec.BOOL.optionalFieldOf("warning_sent", false).forGetter(state -> state.warningSent)
            ).apply(instance, RevelationStateManager::new)
    );

    public static final PersistentStateType<RevelationStateManager> TYPE = new PersistentStateType<>(
            "unknownmod_revelation",
            RevelationStateManager::new,
            CODEC,
            null
    );

    public RevelationStateManager() {
        this(List.of(), "", "", 0L, 0L, false);
    }

    private RevelationStateManager(
            List<RevealEntry> activeReveals,
            String revealedPlayerUuid,
            String revealedPlayerName,
            long nextRevealAtMillis,
            long revealEndsAtMillis,
            boolean warningSent
    ) {
        this.activeReveals = new LinkedHashMap<>();
        if (activeReveals != null) {
            for (RevealEntry entry : activeReveals) {
                if (entry == null) {
                    continue;
                }

                entry.getUuid().ifPresent(uuid -> this.activeReveals.put(uuid, entry));
            }
        }

        if (this.activeReveals.isEmpty() && revealedPlayerUuid != null && !revealedPlayerUuid.isBlank()) {
            try {
                UUID uuid = UUID.fromString(revealedPlayerUuid);
                this.activeReveals.put(uuid, new RevealEntry(revealedPlayerUuid, revealedPlayerName, revealEndsAtMillis, 0L, false));
            } catch (IllegalArgumentException ignored) {
                // Ignore broken legacy data.
            }
        }

        this.nextRevealAtMillis = nextRevealAtMillis;
        this.warningSent = warningSent;
    }

    public static RevelationStateManager getServerState(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
    }

    public boolean hasActiveReveal() {
        return !activeReveals.isEmpty();
    }

    public boolean isRevealed(UUID uuid) {
        return uuid != null && activeReveals.containsKey(uuid);
    }

    public boolean isRevealPaused(UUID uuid) {
        RevealEntry entry = activeReveals.get(uuid);
        return entry != null && entry.isPaused();
    }

    public Optional<UUID> getRevealedPlayerUuid() {
        return activeReveals.keySet().stream().findFirst();
    }

    public String getRevealedPlayerName() {
        return activeReveals.values().stream()
                .findFirst()
                .map(RevealEntry::getPlayerName)
                .orElse("");
    }

    public long getNextRevealAtMillis() {
        return nextRevealAtMillis;
    }

    public long getRevealEndsAtMillis() {
        return activeReveals.values().stream()
                .findFirst()
                .map(entry -> entry.getRevealEndsAtMillis() > 0L ? entry.getRevealEndsAtMillis() : entry.getPausedRemainingMillis())
                .orElse(0L);
    }

    public boolean isWarningSent() {
        return warningSent;
    }

    public List<RevealEntry> getActiveRevealEntries() {
        return List.copyOf(activeReveals.values());
    }

    public List<UUID> getActiveRevealUuids() {
        return List.copyOf(activeReveals.keySet());
    }

    public Optional<RevealEntry> getActiveReveal(UUID uuid) {
        return Optional.ofNullable(activeReveals.get(uuid));
    }

    public void setNextRevealAtMillis(long nextRevealAtMillis) {
        this.nextRevealAtMillis = nextRevealAtMillis;
        markDirty();
    }

    public void setRevealEndsAtMillis(long revealEndsAtMillis) {
        for (RevealEntry entry : activeReveals.values()) {
            if (!entry.isPaused()) {
                entry.setRevealEndsAtMillis(revealEndsAtMillis);
            }
        }
        markDirty();
    }

    public void setWarningSent(boolean warningSent) {
        this.warningSent = warningSent;
        markDirty();
    }

    public void setActiveReveal(UUID uuid, String playerName, long revealEndsAtMillis) {
        if (uuid == null) {
            return;
        }

        RevealEntry entry = new RevealEntry(uuid.toString(), playerName, revealEndsAtMillis, 0L, false);
        activeReveals.put(uuid, entry);
        markDirty();
    }

    public boolean pauseReveal(UUID uuid, long remainingMillis) {
        RevealEntry entry = activeReveals.get(uuid);
        if (entry == null || entry.isPaused()) {
            return false;
        }

        entry.pause(remainingMillis);
        markDirty();
        return true;
    }

    public boolean resumeReveal(UUID uuid, long now) {
        RevealEntry entry = activeReveals.get(uuid);
        if (entry == null || !entry.isPaused()) {
            return false;
        }

        if (!entry.resume(now)) {
            activeReveals.remove(uuid);
            markDirty();
            return false;
        }

        markDirty();
        return true;
    }

    public boolean removeReveal(UUID uuid) {
        if (uuid == null) {
            return false;
        }

        RevealEntry removed = activeReveals.remove(uuid);
        if (removed != null) {
            markDirty();
            return true;
        }
        return false;
    }

    public void clearActiveReveal() {
        if (activeReveals.isEmpty()) {
            return;
        }

        activeReveals.clear();
        markDirty();
    }

    public void clearSchedule() {
        this.nextRevealAtMillis = 0L;
        this.warningSent = false;
        markDirty();
    }

    private String getLegacyRevealedPlayerUuid() {
        return "";
    }

    private String getLegacyRevealedPlayerName() {
        return "";
    }

    private long getLegacyRevealEndsAtMillis() {
        return 0L;
    }

    public static final class RevealEntry {
        private final String playerUuid;
        private final String playerName;
        private long revealEndsAtMillis;
        private long pausedRemainingMillis;
        private boolean paused;

        public static final Codec<RevealEntry> CODEC = RecordCodecBuilder.create(instance ->
                instance.group(
                        Codec.STRING.optionalFieldOf("player_uuid", "").forGetter(entry -> entry.playerUuid),
                        Codec.STRING.optionalFieldOf("player_name", "").forGetter(entry -> entry.playerName),
                        Codec.LONG.optionalFieldOf("reveal_ends_at_millis", 0L).forGetter(entry -> entry.revealEndsAtMillis),
                        Codec.LONG.optionalFieldOf("paused_remaining_millis", 0L).forGetter(entry -> entry.pausedRemainingMillis),
                        Codec.BOOL.optionalFieldOf("paused", false).forGetter(entry -> entry.paused)
                ).apply(instance, RevealEntry::new)
        );

        public RevealEntry() {
            this("", "", 0L, 0L, false);
        }

        private RevealEntry(String playerUuid, String playerName, long revealEndsAtMillis, long pausedRemainingMillis, boolean paused) {
            this.playerUuid = playerUuid == null ? "" : playerUuid;
            this.playerName = playerName == null ? "" : playerName;
            this.revealEndsAtMillis = revealEndsAtMillis;
            this.pausedRemainingMillis = pausedRemainingMillis;
            this.paused = paused;
        }

        public Optional<UUID> getUuid() {
            if (playerUuid == null || playerUuid.isBlank()) {
                return Optional.empty();
            }

            try {
                return Optional.of(UUID.fromString(playerUuid));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }

        public String getPlayerName() {
            return playerName == null ? "" : playerName;
        }

        public long getRevealEndsAtMillis() {
            return revealEndsAtMillis;
        }

        public long getPausedRemainingMillis() {
            return pausedRemainingMillis;
        }

        public boolean isPaused() {
            return paused;
        }

        public long getRemainingMillis(long now) {
            if (paused) {
                return Math.max(0L, pausedRemainingMillis);
            }

            return Math.max(0L, revealEndsAtMillis - now);
        }

        public void setRevealEndsAtMillis(long revealEndsAtMillis) {
            this.revealEndsAtMillis = revealEndsAtMillis;
            this.pausedRemainingMillis = 0L;
            this.paused = false;
        }

        public void pause(long remainingMillis) {
            this.pausedRemainingMillis = Math.max(0L, remainingMillis);
            this.revealEndsAtMillis = 0L;
            this.paused = true;
        }

        public boolean resume(long now) {
            if (!paused) {
                return true;
            }

            if (pausedRemainingMillis <= 0L) {
                return false;
            }

            this.revealEndsAtMillis = now + pausedRemainingMillis;
            this.pausedRemainingMillis = 0L;
            this.paused = false;
            return true;
        }
    }
}
