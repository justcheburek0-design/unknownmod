package com.unknownmod.util;

import com.unknownmod.mixin.PlayerManagerAccessor;
import com.unknownmod.state.RevelationStateManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class AppearanceSyncManager {
    private static final String HIDDEN_TEAM_NAME = "unk_hidden";
    private static final String REVEAL_TEAM_NAME = "unk_revealed";
    private static final ConcurrentLinkedQueue<UUID> PENDING_VIEWER_SYNC = new ConcurrentLinkedQueue<>();

    private AppearanceSyncManager() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(AppearanceSyncManager::onServerTick);
    }

    public static void syncAllOnline(MinecraftServer server) {
        if (server == null) {
            return;
        }

        syncScoreboard(server);
        refreshPlayerInfo(server, server.getPlayerManager().getPlayerList());
    }

    public static void syncPlayer(MinecraftServer server, ServerPlayerEntity player) {
        if (server == null || player == null) {
            return;
        }

        syncScoreboard(server);
        refreshPlayerInfo(server, List.of(player));
    }

    public static void syncViewer(MinecraftServer server, ServerPlayerEntity viewer) {
        if (server == null || viewer == null) {
            return;
        }

        ServerScoreboard scoreboard = server.getScoreboard();
        PlayerManagerAccessor playerManager = (PlayerManagerAccessor) server.getPlayerManager();
        playerManager.unknownmod$sendScoreboard(scoreboard, viewer);
    }

    public static void queueViewerSync(ServerPlayerEntity viewer) {
        if (viewer != null) {
            PENDING_VIEWER_SYNC.add(viewer.getUuid());
        }
    }

    private static void syncScoreboard(MinecraftServer server) {
        RevealGlowManager.syncPlayerVisibility(server);

        ServerScoreboard scoreboard = server.getScoreboard();
        Team hiddenTeam = scoreboard.getTeam(HIDDEN_TEAM_NAME);
        Team revealTeam = scoreboard.getTeam(REVEAL_TEAM_NAME);
        if (hiddenTeam != null) {
            scoreboard.updateScoreboardTeamAndPlayers(hiddenTeam);
        }
        if (revealTeam != null) {
            scoreboard.updateScoreboardTeamAndPlayers(revealTeam);
        }

        PlayerManagerAccessor playerManager = (PlayerManagerAccessor) server.getPlayerManager();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            playerManager.unknownmod$sendScoreboard(scoreboard, player);
        }
    }

    private static void refreshPlayerInfo(MinecraftServer server, Collection<ServerPlayerEntity> players) {
        if (players == null || players.isEmpty()) {
            return;
        }

        List<UUID> uuids = players.stream()
                .map(ServerPlayerEntity::getUuid)
                .toList();

        server.getPlayerManager().sendToAll(new PlayerRemoveS2CPacket(uuids));
        server.getPlayerManager().sendToAll(PlayerListS2CPacket.entryFromPlayer(players));
    }

    private static void onServerTick(MinecraftServer server) {
        List<UUID> drained = new ArrayList<>();
        UUID uuid;
        while ((uuid = PENDING_VIEWER_SYNC.poll()) != null) {
            drained.add(uuid);
        }

        if (drained.isEmpty()) {
            return;
        }

        for (UUID pendingUuid : drained) {
            ServerPlayerEntity viewer = server.getPlayerManager().getPlayer(pendingUuid);
            if (viewer != null) {
                syncViewer(server, viewer);
            }
        }
    }
}
