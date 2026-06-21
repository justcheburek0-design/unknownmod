package com.unknownmod.state;

import net.minecraft.resources.Identifier;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.unknownmod.util.DebugMessenger;
import com.unknownmod.util.ProfileApplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class GhostStateManager extends SavedData {
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

    public static final SavedDataType<GhostStateManager> TYPE = new SavedDataType<>(
            Identifier.parse("unknownmod_ghosts"),
            () -> new GhostStateManager(new HashSet<>()),
            CODEC,
            null
    );

    private GhostStateManager(Set<UUID> ghosts) {
        this.ghosts = ghosts;
    }

    public static GhostStateManager getServerState(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public void addGhost(UUID uuid) {
        ghosts.add(uuid);
        setDirty();
    }

    public boolean removeGhost(UUID uuid) {
        boolean removed = ghosts.remove(uuid);
        if (removed) {
            setDirty();
        }
        return removed;
    }

    public static boolean makeGhost(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null) {
            return false;
        }

        GhostStateManager state = getServerState(server);
        state.addGhost(player.getUUID());
        if (player.gameMode() != GameType.SPECTATOR) {
            player.setGameMode(GameType.SPECTATOR);
        }
        ProfileApplier.refreshPlayer(server, player);
        DebugMessenger.debug(server, "Player set to ghost: " + player.getName().getString() + ".");
        return true;
    }

    public static boolean restoreGhost(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null) {
            return false;
        }

        GhostStateManager state = getServerState(server);
        if (!state.removeGhost(player.getUUID())) {
            return false;
        }

        GameType restoreMode = player.gameMode.getPreviousGameModeForPlayer();
        if (restoreMode == null || restoreMode == GameType.SPECTATOR) {
            restoreMode = GameType.SURVIVAL;
        }
        if (player.gameMode() != restoreMode) {
            player.setGameMode(restoreMode);
        }

        ProfileApplier.refreshPlayer(server, player);
        DebugMessenger.debug(server, "Player restored from ghost: " + player.getName().getString() + ".");
        return true;
    }

    public boolean isGhost(UUID uuid) {
        return ghosts.contains(uuid);
    }
}
