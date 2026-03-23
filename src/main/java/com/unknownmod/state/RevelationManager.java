package com.unknownmod.state;

import com.unknownmod.config.ConfigManager;
import com.unknownmod.config.UnknownConfig;
import com.unknownmod.util.DebugMessenger;
import com.unknownmod.util.MessageFormatter;
import com.unknownmod.util.ProfileApplier;
import com.unknownmod.util.RevealGlowManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class RevelationManager {
    private static long tickCounter;

    private RevelationManager() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(RevelationManager::onServerTick);
    }

    public static boolean isRevealed(MinecraftServer server, UUID uuid) {
        return RevelationStateManager.getServerState(server).getRevealedPlayerUuid().map(uuid::equals).orElse(false);
    }

    public static boolean isRevealed(UUID uuid) {
        MinecraftServer server = ServerContextHolder.getServer();
        return server != null && isRevealed(server, uuid);
    }

    public static boolean startManualReveal(MinecraftServer server, ServerPlayerEntity target) {
        return startReveal(server, target);
    }

    public static boolean cancelReveal(MinecraftServer server) {
        RevelationStateManager state = RevelationStateManager.getServerState(server);
        if (!state.hasActiveReveal()) {
            return false;
        }

        UUID revealedUuid = state.getRevealedPlayerUuid().orElse(null);
        String playerName = state.getRevealedPlayerName();
        state.clearActiveReveal();
        state.setNextRevealAtMillis(System.currentTimeMillis() + intervalMillis(ConfigManager.getConfig()));
        state.setWarningSent(false);
        RevealGlowManager.clearReveal(server, revealedUuid, playerName);
        ProfileApplier.refreshAllOnline(server);
        broadcast(server, MessageFormatter.format(ConfigManager.getConfig().revelation.messages.cancelTitle));
        broadcast(server, MessageFormatter.format(ConfigManager.getConfig().revelation.messages.cancelSubtitle, "player", playerName));
        broadcast(server, MessageFormatter.format(ConfigManager.getConfig().revelation.messages.cancelChat, "player", playerName));
        DebugMessenger.debug(server, "Reveal cancelled for " + playerName + ".");
        return true;
    }

    public static boolean clearRevealIfMatches(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) {
            return false;
        }

        RevelationStateManager state = RevelationStateManager.getServerState(server);
        if (!state.getRevealedPlayerUuid().map(uuid::equals).orElse(false)) {
            return false;
        }

        String playerName = state.getRevealedPlayerName();
        state.clearActiveReveal();
        state.setNextRevealAtMillis(System.currentTimeMillis() + intervalMillis(ConfigManager.getConfig()));
        state.setWarningSent(false);
        RevealGlowManager.clearReveal(server, uuid, playerName);
        DebugMessenger.debug(server, "Reveal cleared for " + playerName + ".");
        return true;
    }

    public static boolean eliminateReveal(MinecraftServer server, ServerPlayerEntity victim, String killerName) {
        UnknownConfig config = ConfigManager.getConfig();
        RevelationStateManager state = RevelationStateManager.getServerState(server);
        if (!state.hasActiveReveal() || !state.getRevealedPlayerUuid().map(victim.getUuid()::equals).orElse(false)) {
            return false;
        }

        String playerName = victim.getName().getString();
        state.clearActiveReveal();
        state.setNextRevealAtMillis(System.currentTimeMillis() + intervalMillis(config));
        state.setWarningSent(false);
        RevealGlowManager.clearReveal(server, victim.getUuid(), playerName);
        ProfileApplier.refreshAllOnline(server);
        broadcast(server, MessageFormatter.format(
                config.revelation.messages.eliminated,
                "player", playerName,
                "killer", killerName
        ));
        DebugMessenger.debug(server, "Revealed player eliminated: " + playerName + " by " + killerName + ".");
        return true;
    }

    public static ServerPlayerEntity pickRandomEligiblePlayer(MinecraftServer server) {
        List<ServerPlayerEntity> eligible = getEligiblePlayers(server);
        if (eligible.isEmpty()) {
            return null;
        }

        return eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
    }

    public static String getStatusLine(MinecraftServer server) {
        UnknownConfig config = ConfigManager.getConfig();
        RevelationStateManager state = RevelationStateManager.getServerState(server);
        if (state.hasActiveReveal()) {
            String playerName = state.getRevealedPlayerName();
            long remaining = Math.max(0L, state.getRevealEndsAtMillis() - System.currentTimeMillis());
            return config.revelation.messages.statusRevealed
                    .replace("%player%", playerName)
                    .replace("%time%", formatDuration(remaining));
        }

        if (state.getNextRevealAtMillis() <= 0L) {
            return config.revelation.messages.noActive;
        }

        long remaining = Math.max(0L, state.getNextRevealAtMillis() - System.currentTimeMillis());
        return config.revelation.messages.statusTimer.replace("%time%", formatDuration(remaining));
    }

    public static void onConfigChanged(MinecraftServer server) {
        UnknownConfig config = ConfigManager.getConfig();
        RevelationStateManager state = RevelationStateManager.getServerState(server);
        long now = System.currentTimeMillis();

        if (config.revelation == null || !config.revelation.enabled) {
            state.clearSchedule();
            if (state.hasActiveReveal()) {
                UUID revealedUuid = state.getRevealedPlayerUuid().orElse(null);
                String playerName = state.getRevealedPlayerName();
                state.clearActiveReveal();
                RevealGlowManager.clearReveal(server, revealedUuid, playerName);
                ProfileApplier.refreshAllOnline(server);
                DebugMessenger.debug(server, "Revelation disabled in config; active reveal cleared.");
            }
            return;
        }

        if (state.hasActiveReveal()) {
            state.setRevealEndsAtMillis(now + durationMillis(config));
            DebugMessenger.debug(server, "Active revelation rescheduled after config change.");
            return;
        }

        state.setNextRevealAtMillis(now + intervalMillis(config));
        state.setWarningSent(false);
        DebugMessenger.debug(server, "Revelation schedule reset after config change.");
    }

    private static void onServerTick(MinecraftServer server) {
        ServerContextHolder.setServer(server);
        tickCounter++;
        if ((tickCounter % 20L) != 0L) {
            return;
        }

        UnknownConfig config = ConfigManager.getConfig();
        if (config.revelation == null || !config.revelation.enabled) {
            return;
        }

        RevelationStateManager state = RevelationStateManager.getServerState(server);
        long now = System.currentTimeMillis();

        if (state.hasActiveReveal()) {
            if (now >= state.getRevealEndsAtMillis()) {
                cancelReveal(server);
                return;
            }

            state.getRevealedPlayerUuid().ifPresent(uuid -> {
                ServerPlayerEntity victim = server.getPlayerManager().getPlayer(uuid);
                if (victim != null) {
                    String remaining = formatDuration(state.getRevealEndsAtMillis() - now);
                    victim.sendMessage(MessageFormatter.format(config.revelation.messages.victimCountdown, "time", remaining), true);
                }
            });
            return;
        }

        if (state.getNextRevealAtMillis() <= 0L) {
            state.setNextRevealAtMillis(now + intervalMillis(config));
            state.setWarningSent(false);
            DebugMessenger.debug(server, "Next revelation timestamp initialized.");
            return;
        }

        long warningMillis = warningMillis(config);
        long warningAt = state.getNextRevealAtMillis() - warningMillis;
        if (!state.isWarningSent() && config.revelation.warningMinutes > 0 && warningMillis > 0L && now >= warningAt && now < state.getNextRevealAtMillis()) {
            state.setWarningSent(true);
            broadcast(server, MessageFormatter.format(config.revelation.messages.warning, "minutes", config.revelation.warningMinutes));
            DebugMessenger.debug(server, "Revelation warning broadcast sent.");
        }

        if (now < state.getNextRevealAtMillis()) {
            return;
        }

        int onlinePlayers = server.getPlayerManager().getPlayerList().size();
        if (onlinePlayers <= config.revelation.minPlayers) {
            broadcast(server, MessageFormatter.format(config.revelation.messages.notEnoughPlayers));
            state.setNextRevealAtMillis(now + intervalMillis(config));
            state.setWarningSent(false);
            DebugMessenger.debug(server, "Revelation postponed because only " + onlinePlayers + " players are online.");
            return;
        }

        List<ServerPlayerEntity> eligible = getEligiblePlayers(server);
        if (eligible.isEmpty()) {
            broadcast(server, MessageFormatter.format(config.revelation.messages.notEnoughPlayers));
            state.setNextRevealAtMillis(now + intervalMillis(config));
            state.setWarningSent(false);
            DebugMessenger.debug(server, "Revelation postponed because no eligible players were found.");
            return;
        }

        ServerPlayerEntity target = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
        startReveal(server, target);
        state.setNextRevealAtMillis(now + intervalMillis(config));
        state.setWarningSent(false);
        DebugMessenger.debug(server, "Random revelation started for " + target.getName().getString() + ".");
    }

    private static boolean startReveal(MinecraftServer server, ServerPlayerEntity target) {
        UnknownConfig config = ConfigManager.getConfig();
        if (config.revelation == null || !config.revelation.enabled) {
            DebugMessenger.debug(server, "Reveal request rejected because feature is disabled.");
            return false;
        }

        RevelationStateManager state = RevelationStateManager.getServerState(server);
        if (state.hasActiveReveal() || target == null) {
            DebugMessenger.debug(server, "Reveal request rejected because another reveal is active or target is null.");
            return false;
        }

        if (GhostStateManager.getServerState(server).isGhost(target.getUuid())) {
            DebugMessenger.debug(server, "Reveal request rejected because target is a ghost: " + target.getName().getString() + ".");
            return false;
        }

        if (!ProfileApplier.applyOriginalProfile(target)) {
            DebugMessenger.debug(server, "Reveal request rejected because original profile could not be applied for " + target.getName().getString() + ".");
            return false;
        }

        long now = System.currentTimeMillis();
        state.setActiveReveal(target.getUuid(), target.getName().getString(), now + durationMillis(config));
        state.setNextRevealAtMillis(now + intervalMillis(config));
        ProfileApplier.refreshAllOnline(server);

        broadcast(server, MessageFormatter.format(config.revelation.messages.revealTitle));
        broadcast(server, MessageFormatter.format(config.revelation.messages.revealSubtitle, "player", target.getName().getString()));
        broadcast(server, MessageFormatter.format(config.revelation.messages.revealChat, "player", target.getName().getString()));
        DebugMessenger.debug(server, "Reveal started for " + target.getName().getString() + ".");
        return true;
    }

    private static List<ServerPlayerEntity> getEligiblePlayers(MinecraftServer server) {
        List<ServerPlayerEntity> players = new ArrayList<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!GhostStateManager.getServerState(server).isGhost(player.getUuid())) {
                players.add(player);
            }
        }
        return players;
    }

    private static long intervalMillis(UnknownConfig config) {
        return Math.max(1, config.revelation.intervalHours) * 60L * 60L * 1000L;
    }

    private static long durationMillis(UnknownConfig config) {
        return Math.max(1, config.revelation.durationMinutes) * 60L * 1000L;
    }

    private static long warningMillis(UnknownConfig config) {
        return Math.max(0, config.revelation.warningMinutes) * 60L * 1000L;
    }

    private static void broadcast(MinecraftServer server, Text text) {
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(text, false);
        }
    }

    private static String formatDuration(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder builder = new StringBuilder();
        if (hours > 0) {
            builder.append(hours).append("ч ");
        }
        if (minutes > 0 || hours > 0) {
            builder.append(minutes).append("м ");
        }
        builder.append(seconds).append("с");
        return builder.toString().trim();
    }
}
