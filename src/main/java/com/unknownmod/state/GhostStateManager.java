package com.unknownmod.state;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.unknownmod.util.ProfileApplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class GhostStateManager extends PersistentState {
    private final Set<UUID> ghosts;

    public static final Codec<GhostStateManager> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.listOf().fieldOf("ghosts").forGetter(state ->
                            state.ghosts.stream().map(UUID::toString).toList()
                    )
            ).apply(instance, uuidStrings -> {
                Set<UUID> set = new HashSet<>();
                for (String s : uuidStrings) {
                    try {
                        set.add(UUID.fromString(s));
                    } catch (IllegalArgumentException ignored) {}
                }
                return new GhostStateManager(set);
            })
    );

    public static final PersistentStateType<GhostStateManager> TYPE = new PersistentStateType<>(
            "unknownmod_ghosts",
            () -> new GhostStateManager(new HashSet<>()),
            CODEC,
            null
    );

    private GhostStateManager(Set<UUID> ghosts) {
        this.ghosts = ghosts;
    }

    public static GhostStateManager getServerState(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
    }

    public void addGhost(UUID uuid) {
        ghosts.add(uuid);
        markDirty();
    }

    public boolean removeGhost(UUID uuid) {
        boolean removed = ghosts.remove(uuid);
        if (removed) {
            markDirty();
        }
        return removed;
    }

    public static boolean makeGhost(MinecraftServer server, ServerPlayerEntity player) {
        if (server == null || player == null) {
            return false;
        }

        GhostStateManager state = getServerState(server);
        state.addGhost(player.getUuid());
        RevelationManager.clearRevealIfMatches(server, player.getUuid());
        if (player.getGameMode() != GameMode.SPECTATOR) {
            player.changeGameMode(GameMode.SPECTATOR);
        }
        ProfileApplier.refreshPlayer(server, player);
        return true;
    }

    public static boolean restoreGhost(MinecraftServer server, ServerPlayerEntity player) {
        if (server == null || player == null) {
            return false;
        }

        GhostStateManager state = getServerState(server);
        if (!state.removeGhost(player.getUuid())) {
            return false;
        }

        RevelationManager.clearRevealIfMatches(server, player.getUuid());

        GameMode restoreMode = player.interactionManager.getPreviousGameMode();
        if (restoreMode == null || restoreMode == GameMode.SPECTATOR) {
            restoreMode = GameMode.SURVIVAL;
        }
        if (player.getGameMode() != restoreMode) {
            player.changeGameMode(restoreMode);
        }

        ProfileApplier.refreshPlayer(server, player);
        return true;
    }

    public boolean isGhost(UUID uuid) {
        return ghosts.contains(uuid);
    }
}
