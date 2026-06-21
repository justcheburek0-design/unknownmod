package com.unknownmod.util;

import com.unknownmod.state.RevelationStateManager;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;

import java.util.HashSet;
import java.util.UUID;

public final class RevealGlowManager {
    private static final String HIDDEN_TEAM_NAME = "unk_hidden";
    private static final String REVEAL_TEAM_NAME = "unk_revealed";

    private RevealGlowManager() {
    }

    public static void syncActiveReveal(MinecraftServer server) {
        syncPlayerVisibility(server);
    }

    public static void syncPlayerVisibility(MinecraftServer server) {
        if (server == null) {
            return;
        }

        RevelationStateManager state = RevelationStateManager.getServerState(server);
        HashSet<UUID> revealedUuids = new HashSet<>(state.getActiveRevealUuids());

        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam hiddenTeam = ensureTeam(scoreboard, HIDDEN_TEAM_NAME, Team.Visibility.NEVER, ChatFormatting.WHITE);
        PlayerTeam revealTeam = ensureTeam(scoreboard, REVEAL_TEAM_NAME, Team.Visibility.ALWAYS, ChatFormatting.DARK_RED);
        if (hiddenTeam == null || revealTeam == null) {
            return;
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String scoreHolder = player.getScoreboardName();
            if (revealedUuids.contains(player.getUUID())) {
                assignToTeam(scoreboard, revealTeam, scoreHolder);
                player.setGlowingTag(true);
            } else {
                assignToTeam(scoreboard, hiddenTeam, scoreHolder);
                player.setGlowingTag(false);
            }
        }
    }

    public static void revealPlayer(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null) {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam revealTeam = ensureTeam(scoreboard, REVEAL_TEAM_NAME, Team.Visibility.ALWAYS, ChatFormatting.DARK_RED);
        if (revealTeam == null) {
            return;
        }
        assignToTeam(scoreboard, revealTeam, player.getScoreboardName());
        player.setGlowingTag(true);
    }

    public static void clearReveal(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null) {
            return;
        }
        Scoreboard scoreboard = server.getScoreboard();
        PlayerTeam hiddenTeam = ensureTeam(scoreboard, HIDDEN_TEAM_NAME, Team.Visibility.NEVER, ChatFormatting.WHITE);
        if (hiddenTeam != null) {
            assignToTeam(scoreboard, hiddenTeam, player.getScoreboardName());
        }
        player.setGlowingTag(false);
    }

    private static void removeFromTeam(Scoreboard scoreboard, PlayerTeam targetTeam, String scoreHolder) {
        PlayerTeam currentTeam = scoreboard.getPlayersTeam(scoreHolder);
        if (currentTeam == targetTeam) {
            scoreboard.removePlayerFromTeam(scoreHolder, targetTeam);
        }
    }

    private static void assignToTeam(Scoreboard scoreboard, PlayerTeam targetTeam, String scoreHolder) {
        PlayerTeam currentTeam = scoreboard.getPlayersTeam(scoreHolder);
        if (currentTeam != null && currentTeam != targetTeam) {
            scoreboard.removePlayerFromTeam(scoreHolder, currentTeam);
        }
        if (currentTeam != targetTeam) {
            scoreboard.addPlayerToTeam(scoreHolder, targetTeam);
        }
    }

    private static PlayerTeam ensureTeam(Scoreboard scoreboard, String name, Team.Visibility nameTagVisibility, ChatFormatting color) {
        PlayerTeam team = scoreboard.getPlayerTeam(name);
        if (team == null) {
            team = scoreboard.addPlayerTeam(name);
            team.setNameTagVisibility(nameTagVisibility);
            team.setColor(color);
        }
        return team;
    }

    private static void broadcastTeamChange(MinecraftServer server, PlayerTeam team) {
        server.getPlayerList().broadcastAll(ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, false));
    }

    private static void broadcastTeamRemove(MinecraftServer server, PlayerTeam team) {
        server.getPlayerList().broadcastAll(ClientboundSetPlayerTeamPacket.createRemovePacket(team));
    }
}
