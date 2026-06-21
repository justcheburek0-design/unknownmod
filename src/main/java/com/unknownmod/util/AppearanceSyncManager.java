package com.unknownmod.util;

import com.unknownmod.mixin.PlayerManagerAccessor;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Team;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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
        refreshPlayerInfo(server, server.getPlayerList().getPlayers());
    }

    public static void syncPlayer(MinecraftServer server, ServerPlayer player) {
        if (server == null || player == null) {
            return;
        }

        syncScoreboard(server);
        refreshPlayerInfo(server, List.of(player));
    }

    public static void syncViewer(MinecraftServer server, ServerPlayer viewer) {
        if (server == null || viewer == null) {
            return;
        }

        ServerScoreboard scoreboard = server.getScoreboard();
        PlayerManagerAccessor playerManager = (PlayerManagerAccessor) server.getPlayerList();
        playerManager.unknownmod$sendScoreboard(scoreboard, viewer);
    }

    public static void queueViewerSync(ServerPlayer viewer) {
        if (viewer != null) {
            PENDING_VIEWER_SYNC.add(viewer.getUUID());
        }
    }

    private static void syncScoreboard(MinecraftServer server) {
        RevealGlowManager.syncPlayerVisibility(server);

        ServerScoreboard scoreboard = server.getScoreboard();
        PlayerTeam hiddenTeam = scoreboard.getPlayerTeam(HIDDEN_TEAM_NAME);
        PlayerTeam revealTeam = scoreboard.getPlayerTeam(REVEAL_TEAM_NAME);
        if (hiddenTeam != null) {
            server.getPlayerList().broadcastAll(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(hiddenTeam, false));
        }
        if (revealTeam != null) {
            server.getPlayerList().broadcastAll(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(revealTeam, false));
        }

        PlayerManagerAccessor playerManager = (PlayerManagerAccessor) server.getPlayerList();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            playerManager.unknownmod$sendScoreboard(scoreboard, player);
        }
    }

    private static void refreshPlayerInfo(MinecraftServer server, Collection<ServerPlayer> players) {
        if (players == null || players.isEmpty()) {
            return;
        }

        List<UUID> uuids = players.stream()
                .map(ServerPlayer::getUUID)
                .toList();

        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(uuids));
        server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(players));
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
            ServerPlayer viewer = server.getPlayerList().getPlayer(pendingUuid);
            if (viewer != null) {
                syncViewer(server, viewer);
            }
        }
    }
}
