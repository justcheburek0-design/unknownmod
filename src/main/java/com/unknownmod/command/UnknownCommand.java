package com.unknownmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.unknownmod.config.ConfigManager;
import com.unknownmod.config.UnknownConfig;
import com.unknownmod.state.GhostStateManager;
import com.unknownmod.state.IdentityStore;
import com.unknownmod.state.RevelationManager;
import com.unknownmod.util.MessageFormatter;
import com.unknownmod.util.ProfileApplier;
import com.unknownmod.util.SkinFetcher;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;

public class UnknownCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("unknown")
                .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                .then(CommandManager.literal("reload")
                        .executes(UnknownCommand::executeReload)
                )
                .then(CommandManager.literal("nickname")
                        .then(CommandManager.argument("value", StringArgumentType.string())
                                .executes(UnknownCommand::executeSetNickname)
                        )
                )
                .then(CommandManager.literal("ghost")
                        .then(CommandManager.argument("target", StringArgumentType.word())
                                .suggests(UnknownCommand::suggestKnownPlayerNames)
                                .executes(UnknownCommand::executeGhostTarget)
                        )
                )
                .then(CommandManager.literal("skin")
                        .then(CommandManager.literal("texture")
                                .then(CommandManager.argument("value", StringArgumentType.string())
                                        .executes(context -> executeSetSkin(context, "texture"))
                                )
                        )
                        .then(CommandManager.literal("nickname")
                                .then(CommandManager.argument("value", StringArgumentType.string())
                                        .executes(context -> executeSetSkin(context, "nickname"))
                                )
                        )
                        .then(CommandManager.literal("signature")
                                .then(CommandManager.argument("value", StringArgumentType.string())
                                        .executes(context -> executeSetSkin(context, "signature"))
                                )
                        )
                )
                .then(CommandManager.literal("reveal")
                        .then(CommandManager.literal("player")
                                .then(CommandManager.argument("target", StringArgumentType.word())
                                        .suggests(UnknownCommand::suggestKnownPlayerNames)
                                        .executes(UnknownCommand::executeRevealTarget)
                                )
                                .executes(UnknownCommand::executeRevealRandom)
                        )
                        .then(CommandManager.literal("cancel")
                                .executes(UnknownCommand::executeRevealCancel)
                        )
                        .then(CommandManager.literal("interval")
                                .then(CommandManager.argument("hours", IntegerArgumentType.integer(1))
                                        .executes(UnknownCommand::executeRevealInterval)
                                )
                        )
                        .then(CommandManager.literal("duration")
                                .then(CommandManager.argument("minutes", IntegerArgumentType.integer(1))
                                        .executes(UnknownCommand::executeRevealDuration)
                                )
                        )
                        .then(CommandManager.literal("minplayers")
                                .then(CommandManager.argument("n", IntegerArgumentType.integer(0))
                                        .executes(UnknownCommand::executeRevealMinPlayers)
                                )
                        )
                        .then(CommandManager.literal("status")
                                .executes(UnknownCommand::executeRevealStatus)
                        )
                )
        );
    }

    private static int executeReload(CommandContext<ServerCommandSource> context) {
        ConfigManager.load();
        RevelationManager.onConfigChanged(context.getSource().getServer());
        ProfileApplier.refreshAllOnline(context.getSource().getServer());
        context.getSource().sendFeedback(() -> MessageFormatter.format("[UnknownMod] Config reloaded."), false);
        return 1;
    }

    private static int executeSetNickname(CommandContext<ServerCommandSource> context) {
        String value = StringArgumentType.getString(context, "value");
        UnknownConfig config = ConfigManager.getConfig();
        config.anonymous.name = value;
        ConfigManager.save();
        ProfileApplier.refreshAllOnline(context.getSource().getServer());
        context.getSource().sendFeedback(() -> MessageFormatter.format("[UnknownMod] Anonymous nickname set: " + value), false);
        return 1;
    }

    private static int executeGhostTarget(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity target = resolveTargetPlayer(context);
        if (target == null) {
            return 0;
        }

        if (GhostStateManager.getServerState(context.getSource().getServer()).isGhost(target.getUuid())) {
            if (!GhostStateManager.restoreGhost(context.getSource().getServer(), target)) {
                context.getSource().sendError(MessageFormatter.format("[UnknownMod] Failed to restore player: " + target.getName().getString()));
                return 0;
            }

            context.getSource().sendFeedback(() -> MessageFormatter.format("[UnknownMod] Player restored: " + target.getName().getString()), false);
            return 1;
        }

        if (!GhostStateManager.makeGhost(context.getSource().getServer(), target)) {
            context.getSource().sendError(MessageFormatter.format("[UnknownMod] Failed to make player a ghost: " + target.getName().getString()));
            return 0;
        }

        context.getSource().sendFeedback(() -> MessageFormatter.format("[UnknownMod] Player made ghost: " + target.getName().getString()), false);
        return 1;
    }

    private static int executeSetSkin(CommandContext<ServerCommandSource> context, String type) {
        String value = StringArgumentType.getString(context, "value");
        UnknownConfig config = ConfigManager.getConfig();

        switch (type) {
            case "texture" -> {
                config.anonymous.skin.texture = value;
                config.anonymous.skin.type = "texture";
            }
            case "nickname" -> {
                SkinFetcher.SkinData textures = SkinFetcher.fetchTexturesByNickname(value);
                if (textures == null) {
                    context.getSource().sendError(MessageFormatter.format("[UnknownMod] Failed to resolve skin for: " + value));
                    return 0;
                }
                config.anonymous.skin.nickname = value;
                config.anonymous.skin.texture = textures.value;
                config.anonymous.skin.signature = textures.signature;
                config.anonymous.skin.type = "texture";
            }
            case "signature" -> {
                config.anonymous.skin.signature = value;
                config.anonymous.skin.type = "texture";
            }
            default -> {
                return 0;
            }
        }

        ConfigManager.save();
        ProfileApplier.refreshAllOnline(context.getSource().getServer());
        context.getSource().sendFeedback(() -> MessageFormatter.format("[UnknownMod] Skin updated for all players."), false);
        return 1;
    }

    private static int executeRevealRandom(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity target = RevelationManager.pickRandomEligiblePlayer(context.getSource().getServer());
        if (target == null) {
            context.getSource().sendError(MessageFormatter.format(ConfigManager.getConfig().revelation.messages.notEnoughPlayers));
            return 0;
        }

        return executeReveal(context, target);
    }

    private static int executeRevealTarget(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity target = resolveTargetPlayer(context);
        if (target == null) {
            return 0;
        }

        return executeReveal(context, target);
    }

    private static ServerPlayerEntity resolveTargetPlayer(CommandContext<ServerCommandSource> context) {
        String targetName = StringArgumentType.getString(context, "target");
        ServerPlayerEntity target = IdentityStore.findOnlinePlayerByOriginalName(context.getSource().getServer(), targetName)
                .orElse(null);
        if (target != null) {
            return target;
        }

        context.getSource().sendError(MessageFormatter.format("[UnknownMod] Player not found: " + targetName));
        return null;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestKnownPlayerNames(
            CommandContext<ServerCommandSource> context,
            SuggestionsBuilder builder
    ) {
        for (String name : IdentityStore.getKnownPlayerNames(context.getSource().getServer())) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    }

    private static int executeReveal(CommandContext<ServerCommandSource> context, ServerPlayerEntity target) {
        if (!RevelationManager.startManualReveal(context.getSource().getServer(), target)) {
            context.getSource().sendError(MessageFormatter.format("[UnknownMod] Unable to start revelation."));
            return 0;
        }

        context.getSource().sendFeedback(() -> MessageFormatter.format("[UnknownMod] Revelation started for " + target.getName().getString()), false);
        return 1;
    }

    private static int executeRevealCancel(CommandContext<ServerCommandSource> context) {
        if (!RevelationManager.cancelReveal(context.getSource().getServer())) {
            context.getSource().sendError(MessageFormatter.format(ConfigManager.getConfig().revelation.messages.noActive));
            return 0;
        }

        context.getSource().sendFeedback(() -> MessageFormatter.format("[UnknownMod] Revelation cancelled."), false);
        return 1;
    }

    private static int executeRevealInterval(CommandContext<ServerCommandSource> context) {
        int hours = IntegerArgumentType.getInteger(context, "hours");
        UnknownConfig config = ConfigManager.getConfig();
        config.revelation.intervalHours = hours;
        ConfigManager.save();
        RevelationManager.onConfigChanged(context.getSource().getServer());
        context.getSource().sendFeedback(() -> MessageFormatter.format(config.revelation.messages.intervalSet, "hours", hours), false);
        return 1;
    }

    private static int executeRevealDuration(CommandContext<ServerCommandSource> context) {
        int minutes = IntegerArgumentType.getInteger(context, "minutes");
        UnknownConfig config = ConfigManager.getConfig();
        config.revelation.durationMinutes = minutes;
        ConfigManager.save();
        RevelationManager.onConfigChanged(context.getSource().getServer());
        context.getSource().sendFeedback(() -> MessageFormatter.format(config.revelation.messages.durationSet, "minutes", minutes), false);
        return 1;
    }

    private static int executeRevealMinPlayers(CommandContext<ServerCommandSource> context) {
        int n = IntegerArgumentType.getInteger(context, "n");
        UnknownConfig config = ConfigManager.getConfig();
        config.revelation.minPlayers = n;
        ConfigManager.save();
        RevelationManager.onConfigChanged(context.getSource().getServer());
        context.getSource().sendFeedback(() -> MessageFormatter.format(config.revelation.messages.minPlayersSet, "n", n), false);
        return 1;
    }

    private static int executeRevealStatus(CommandContext<ServerCommandSource> context) {
        String status = RevelationManager.getStatusLine(context.getSource().getServer());
        context.getSource().sendFeedback(() -> MessageFormatter.format(status), false);
        return 1;
    }
}
