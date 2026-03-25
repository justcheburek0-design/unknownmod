package com.unknownmod.util;

import com.unknownmod.state.RevelationStateManager;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Formatting;

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
        UUID revealedUuid = state.getRevealedPlayerUuid().orElse(null);

        Scoreboard scoreboard = server.getScoreboard();
        Team hiddenTeam = ensureTeam(scoreboard, HIDDEN_TEAM_NAME, AbstractTeam.VisibilityRule.NEVER, Formatting.WHITE);
        Team revealTeam = ensureTeam(scoreboard, REVEAL_TEAM_NAME, AbstractTeam.VisibilityRule.ALWAYS, Formatting.DARK_RED);
        if (hiddenTeam == null || revealTeam == null) {
            return;
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            String scoreHolder = player.getNameForScoreboard();
            if (revealedUuid != null && revealedUuid.equals(player.getUuid())) {
                assignToTeam(scoreboard, revealTeam, scoreHolder);
                player.setGlowing(true);
            } else {
                assignToTeam(scoreboard, hiddenTeam, scoreHolder);
                player.setGlowing(false);
            }
        }
    }

    public static void applyIfRevealed(MinecraftServer server, ServerPlayerEntity player) {
        if (server == null || player == null) {
            return;
        }

        RevelationStateManager state = RevelationStateManager.getServerState(server);
        if (!state.getRevealedPlayerUuid().map(player.getUuid()::equals).orElse(false)) {
            return;
        }

        Scoreboard scoreboard = server.getScoreboard();
        Team revealTeam = ensureTeam(scoreboard, REVEAL_TEAM_NAME, AbstractTeam.VisibilityRule.ALWAYS, Formatting.DARK_RED);
        if (revealTeam == null) {
            return;
        }

        assignToTeam(scoreboard, revealTeam, player.getNameForScoreboard());
        player.setGlowing(true);
    }

    public static void clearReveal(MinecraftServer server, UUID uuid) {
        if (server == null) {
            return;
        }

        if (uuid != null) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
            if (player != null) {
                Scoreboard scoreboard = server.getScoreboard();
                Team revealTeam = scoreboard.getTeam(REVEAL_TEAM_NAME);
                if (revealTeam != null) {
                    removeFromTeam(scoreboard, revealTeam, player.getNameForScoreboard());
                }
                player.setGlowing(false);
            }
        }
    }

    private static void removeFromTeam(Scoreboard scoreboard, Team targetTeam, String scoreHolder) {
        Team currentTeam = scoreboard.getScoreHolderTeam(scoreHolder);
        if (currentTeam == targetTeam) {
            scoreboard.removeScoreHolderFromTeam(scoreHolder, targetTeam);
        }
    }

    private static void assignToTeam(Scoreboard scoreboard, Team targetTeam, String scoreHolder) {
        Team currentTeam = scoreboard.getScoreHolderTeam(scoreHolder);
        if (currentTeam != null && currentTeam != targetTeam) {
            scoreboard.removeScoreHolderFromTeam(scoreHolder, currentTeam);
        }

        if (currentTeam != targetTeam) {
            scoreboard.addScoreHolderToTeam(scoreHolder, targetTeam);
        }
    }

    private static Team ensureTeam(Scoreboard scoreboard, String name, AbstractTeam.VisibilityRule nameTagVisibility, Formatting color) {
        Team team = scoreboard.getTeam(name);
        if (team == null) {
            team = scoreboard.addTeam(name);
            if (team == null) {
                return null;
            }
        }

        team.setNameTagVisibilityRule(nameTagVisibility);
        team.setCollisionRule(AbstractTeam.CollisionRule.NEVER);
        team.setColor(color);
        return team;
    }
}
