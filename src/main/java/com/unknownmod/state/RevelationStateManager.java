package com.unknownmod.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.Optional;
import java.util.UUID;

public class RevelationStateManager extends PersistentState {
    private String revealedPlayerUuid;
    private String revealedPlayerName;
    private long nextRevealAtMillis;
    private long revealEndsAtMillis;
    private boolean warningSent;

    public static final Codec<RevelationStateManager> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.optionalFieldOf("revealed_player_uuid", "").forGetter(state -> state.revealedPlayerUuid),
                    Codec.STRING.optionalFieldOf("revealed_player_name", "").forGetter(state -> state.revealedPlayerName),
                    Codec.LONG.optionalFieldOf("next_reveal_at_millis", 0L).forGetter(state -> state.nextRevealAtMillis),
                    Codec.LONG.optionalFieldOf("reveal_ends_at_millis", 0L).forGetter(state -> state.revealEndsAtMillis),
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
        this("", "", 0L, 0L, false);
    }

    private RevelationStateManager(String revealedPlayerUuid, String revealedPlayerName, long nextRevealAtMillis, long revealEndsAtMillis, boolean warningSent) {
        this.revealedPlayerUuid = revealedPlayerUuid;
        this.revealedPlayerName = revealedPlayerName;
        this.nextRevealAtMillis = nextRevealAtMillis;
        this.revealEndsAtMillis = revealEndsAtMillis;
        this.warningSent = warningSent;
    }

    public static RevelationStateManager getServerState(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
    }

    public boolean hasActiveReveal() {
        return revealedPlayerUuid != null && !revealedPlayerUuid.isBlank();
    }

    public Optional<UUID> getRevealedPlayerUuid() {
        if (!hasActiveReveal()) {
            return Optional.empty();
        }

        try {
            return Optional.of(UUID.fromString(revealedPlayerUuid));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    public String getRevealedPlayerName() {
        return revealedPlayerName == null ? "" : revealedPlayerName;
    }

    public long getNextRevealAtMillis() {
        return nextRevealAtMillis;
    }

    public long getRevealEndsAtMillis() {
        return revealEndsAtMillis;
    }

    public boolean isWarningSent() {
        return warningSent;
    }

    public void setNextRevealAtMillis(long nextRevealAtMillis) {
        this.nextRevealAtMillis = nextRevealAtMillis;
        markDirty();
    }

    public void setRevealEndsAtMillis(long revealEndsAtMillis) {
        this.revealEndsAtMillis = revealEndsAtMillis;
        markDirty();
    }

    public void setWarningSent(boolean warningSent) {
        this.warningSent = warningSent;
        markDirty();
    }

    public void setActiveReveal(UUID uuid, String playerName, long revealEndsAtMillis) {
        this.revealedPlayerUuid = uuid == null ? "" : uuid.toString();
        this.revealedPlayerName = playerName == null ? "" : playerName;
        this.revealEndsAtMillis = revealEndsAtMillis;
        this.warningSent = false;
        markDirty();
    }

    public void clearActiveReveal() {
        this.revealedPlayerUuid = "";
        this.revealedPlayerName = "";
        this.revealEndsAtMillis = 0L;
        this.warningSent = false;
        markDirty();
    }

    public void clearSchedule() {
        this.nextRevealAtMillis = 0L;
        this.warningSent = false;
        markDirty();
    }
}
