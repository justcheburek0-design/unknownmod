package com.unknownmod.state;

import com.unknownmod.config.ConfigManager;
import com.unknownmod.config.UnknownConfig;
import com.unknownmod.util.AppearanceSyncManager;
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
import java.util.Optional;
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
        return server != null && RevelationStateManager.getServerState(server).isRevealed(uuid);
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

        List<RevelationStateManager.RevealEntry> activeReveals = state.getActiveRevealEntries();
        state.clearActiveReveal();
        state.setNextRevealAtMillis(System.currentTimeMillis() + intervalMillis(ConfigManager.getConfig()));
        state.setWarningSent(false);

        for (RevelationStateManager.RevealEntry entry : activeReveals) {
            entry.getUuid().ifPresent(uuid -> RevealGlowManager.clearReveal(server, uuid));
        }

        ProfileApplier.refreshAllOnline(server);
        queueViewerSyncAll(server);

        String playerNames = joinRevealNames(activeReveals);
        broadcast(server, MessageFormatter.format(ConfigManager.getConfig().revelation.messages.cancelTitle));
        broadcast(server, MessageFormatter.format(ConfigManager.getConfig().revelation.messages.cancelSubtitle, "player", playerNames));
        broadcast(server, MessageFormatter.format(ConfigManager.getConfig().revelation.messages.cancelChat, "player", playerNames));
        DebugMessenger.debug(server, "Reveal cancelled for " + playerNames + ".");
        return true;
    }

    public static boolean pauseRevealIfMatches(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) {
            return false;
        }

        RevelationStateManager state = RevelationStateManager.getServerState(server);
        Optional<RevelationStateManager.RevealEntry> optionalEntry = state.getActiveReveal(uuid);
        if (optionalEntry.isEmpty()) {
            return false;
        }

        RevelationStateManager.RevealEntry entry = optionalEntry.get();
        if (entry.isPaused()) {
            return false;
        }

        long now = System.currentTimeMillis();
        long remaining = entry.getRemainingMillis(now);
        if (remaining <= 0L) {
            return removeReveal(server, state, uuid, entry.getPlayerName(), false);
        }

        if (!state.pauseReveal(uuid, remaining)) {
            return false;
        }

        RevealGlowManager.clearReveal(server, uuid);
        DebugMessenger.debug(server, "Reveal paused for " + entry.getPlayerName() + ".");
        return true;
    }

    public static boolean resumeRevealIfPaused(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) {
            return false;
        }

        RevelationStateManager state = RevelationStateManager.getServerState(server);
        RevelationStateManager.RevealEntry entry = state.getActiveReveal(uuid).orElse(null);
        if (entry == null || !entry.isPaused()) {
            return false;
        }

        long now = System.currentTimeMillis();
        if (!state.resumeReveal(uuid, now)) {
            return false;
        }

        DebugMessenger.debug(server, "Reveal resumed for " + entry.getPlayerName() + ".");
        return true;
    }

    public static boolean clearRevealIfMatches(MinecraftServer server, UUID uuid) {
        if (server == null || uuid == null) {
            return false;
        }

        RevelationStateManager state = RevelationStateManager.getServerState(server);
        Optional<RevelationStateManager.RevealEntry> optionalEntry = state.getActiveReveal(uuid);
        if (optionalEntry.isEmpty()) {
            return false;
        }

        return removeReveal(server, state, uuid, optionalEntry.get().getPlayerName(), false);
    }

    public static boolean eliminateReveal(MinecraftServer server, ServerPlayerEntity victim, String killerName) {
        UnknownConfig config = ConfigManager.getConfig();
        RevelationStateManager state = RevelationStateManager.getServerState(server);
        if (!state.isRevealed(victim.getUuid())) {
            return false;
        }

        String playerName = victim.getName().getString();
        removeReveal(server, state, victim.getUuid(), playerName, true);
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
        List<RevelationStateManager.RevealEntry> activeReveals = state.getActiveRevealEntries();
        if (!activeReveals.isEmpty()) {
            long now = System.currentTimeMillis();
            long remaining = Long.MAX_VALUE;
            List<String> names = new ArrayList<>(activeReveals.size());

            for (RevelationStateManager.RevealEntry entry : activeReveals) {
                String playerName = entry.getPlayerName();
                if (playerName != null && !playerName.isBlank()) {
                    names.add(playerName);
                }
                remaining = Math.min(remaining, entry.getRemainingMillis(now));
            }

            if (names.isEmpty()) {
                names.add("unknown");
            }

            return config.revelation.messages.statusRevealed
                    .replace("%player%", String.join(", ", names))
                    .replace("%time%", formatDuration(remaining == Long.MAX_VALUE ? 0L : remaining));
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
                List<RevelationStateManager.RevealEntry> activeReveals = state.getActiveRevealEntries();
                state.clearActiveReveal();
                for (RevelationStateManager.RevealEntry entry : activeReveals) {
                    entry.getUuid().ifPresent(uuid -> RevealGlowManager.clearReveal(server, uuid));
                }
                ProfileApplier.refreshAllOnline(server);
                queueViewerSyncAll(server);
                DebugMessenger.debug(server, "Revelation disabled in config; active reveals cleared.");
            }
            return;
        }

        if (state.hasActiveReveal()) {
            for (RevelationStateManager.RevealEntry entry : state.getActiveRevealEntries()) {
                if (!entry.isPaused()) {
                    entry.setRevealEndsAtMillis(now + durationMillis(config));
                }
            }
            DebugMessenger.debug(server, "Active revelations rescheduled after config change.");
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

        boolean stateChanged = false;
        List<UUID> expiredReveals = new ArrayList<>();

        for (RevelationStateManager.RevealEntry entry : state.getActiveRevealEntries()) {
            Optional<UUID> optionalUuid = entry.getUuid();
            if (optionalUuid.isEmpty()) {
                continue;
            }

            UUID uuid = optionalUuid.get();
            if (entry.isPaused()) {
                continue;
            }

            ServerPlayerEntity victim = server.getPlayerManager().getPlayer(uuid);
            if (victim == null) {
                if (pauseRevealIfMatches(server, uuid)) {
                    stateChanged = true;
                }
                continue;
            }

            long remaining = entry.getRemainingMillis(now);
            if (remaining <= 0L) {
                expiredReveals.add(uuid);
                continue;
            }

            victim.sendMessage(MessageFormatter.format(config.revelation.messages.victimCountdown, "time", formatDuration(remaining)), true);
        }

        if (!expiredReveals.isEmpty()) {
            for (UUID uuid : expiredReveals) {
                RevelationStateManager.RevealEntry entry = state.getActiveReveal(uuid).orElse(null);
                String playerName = entry == null ? "" : entry.getPlayerName();
                if (removeReveal(server, state, uuid, playerName, false)) {
                    stateChanged = true;
                }
            }
        }

        if (stateChanged) {
            ProfileApplier.refreshAllOnline(server);
            queueViewerSyncAll(server);
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

        List<ServerPlayerEntity> eligible = getEligiblePlayers(server);
        if (eligible.isEmpty()) {
            broadcast(server, MessageFormatter.format(config.revelation.messages.notEnoughPlayers));
            state.setNextRevealAtMillis(now + intervalMillis(config));
            state.setWarningSent(false);
            DebugMessenger.debug(server, "Revelation postponed because no eligible players were found.");
            return;
        }

        ServerPlayerEntity target = eligible.get(ThreadLocalRandom.current().nextInt(eligible.size()));
        if (startReveal(server, target)) {
            state.setNextRevealAtMillis(now + intervalMillis(config));
            state.setWarningSent(false);
            DebugMessenger.debug(server, "Random revelation started for " + target.getName().getString() + ".");
        }
    }

    private static boolean startReveal(MinecraftServer server, ServerPlayerEntity target) {
        UnknownConfig config = ConfigManager.getConfig();
        if (config.revelation == null || !config.revelation.enabled) {
            DebugMessenger.debug(server, "Reveal request rejected because feature is disabled.");
            return false;
        }

        RevelationStateManager state = RevelationStateManager.getServerState(server);
        if (target == null || state.isRevealed(target.getUuid())) {
            DebugMessenger.debug(server, "Reveal request rejected because target is null or already revealed.");
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
        ProfileApplier.refreshAllOnline(server);
        queueViewerSyncAll(server);

        broadcast(server, MessageFormatter.format(config.revelation.messages.revealTitle));
        broadcast(server, MessageFormatter.format(config.revelation.messages.revealSubtitle, "player", target.getName().getString()));
        broadcast(server, MessageFormatter.format(config.revelation.messages.revealChat, "player", target.getName().getString()));
        DebugMessenger.debug(server, "Reveal started for " + target.getName().getString() + ".");
        return true;
    }

    private static boolean removeReveal(
            MinecraftServer server,
            RevelationStateManager state,
            UUID uuid,
            String playerName,
            boolean refreshProfiles
    ) {
        if (!state.removeReveal(uuid)) {
            return false;
        }

        RevealGlowManager.clearReveal(server, uuid);
        if (refreshProfiles) {
            ProfileApplier.refreshAllOnline(server);
            queueViewerSyncAll(server);
        }
        DebugMessenger.debug(server, "Reveal cleared for " + playerName + ".");
        return true;
    }

    private static List<ServerPlayerEntity> getEligiblePlayers(MinecraftServer server) {
        List<ServerPlayerEntity> players = new ArrayList<>();
        RevelationStateManager state = RevelationStateManager.getServerState(server);
        GhostStateManager ghostState = GhostStateManager.getServerState(server);
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (!ghostState.isGhost(player.getUuid()) && !state.isRevealed(player.getUuid())) {
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

    private static void queueViewerSyncAll(MinecraftServer server) {
        if (server == null) {
            return;
        }

        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            AppearanceSyncManager.queueViewerSync(player);
        }
    }

    private static String joinRevealNames(List<RevelationStateManager.RevealEntry> entries) {
        List<String> names = new ArrayList<>();
        for (RevelationStateManager.RevealEntry entry : entries) {
            String name = entry.getPlayerName();
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }

        if (names.isEmpty()) {
            return "unknown";
        }

        return String.join(", ", names);
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
