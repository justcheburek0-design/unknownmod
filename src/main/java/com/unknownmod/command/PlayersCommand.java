package com.unknownmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.unknownmod.config.ConfigManager;
import com.unknownmod.config.UnknownConfig;
import com.unknownmod.state.PlayerHistoryStateManager;
import com.unknownmod.util.MessageFormatter;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

import java.util.List;

public final class PlayersCommand {
    private static final long MILLIS_PER_DAY = 24L * 60L * 60L * 1000L;

    private PlayersCommand() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("players")
                .executes(PlayersCommand::execute)
        );
    }

    private static int execute(CommandContext<ServerCommandSource> context) {
        int days = getConfiguredDays();
        long cutoffMillis = System.currentTimeMillis() - (days * MILLIS_PER_DAY);
        List<String> names = PlayerHistoryStateManager.getServerState(context.getSource().getServer()).getRecentPlayerNames(cutoffMillis);

        if (names.isEmpty()) {
            context.getSource().sendFeedback(() -> MessageFormatter.format(
                    "<gray>[UnknownMod]</gray> За последние <yellow>" + days + "</yellow> дн. никто не заходил."
            ), false);
            return 1;
        }

        context.getSource().sendFeedback(() -> MessageFormatter.format(
                "<gray>[UnknownMod]</gray> Игроки, заходившие за последние <yellow>" + days + "</yellow> дн.:"
        ), false);

        for (String name : names) {
            context.getSource().sendFeedback(() -> MessageFormatter.format("<white>- " + name + "</white>"), false);
        }

        return names.size();
    }

    private static int getConfiguredDays() {
        UnknownConfig config = ConfigManager.getConfig();
        if (config == null || config.playerList == null) {
            return 2;
        }

        return Math.max(1, config.playerList.days);
    }
}
