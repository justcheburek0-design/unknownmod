package com.unknownmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.unknownmod.config.ConfigManager;
import com.unknownmod.config.UnknownConfig;
import com.unknownmod.util.DebugMessenger;
import com.unknownmod.state.GhostStateManager;
import com.unknownmod.state.IdentityStore;
import com.unknownmod.state.RevelationManager;
import com.unknownmod.util.MessageFormatter;
import com.unknownmod.util.ProfileApplier;
import com.unknownmod.util.SkinFetcher;
import com.unknownmod.worldgen.ChunkWorldgenManager;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public class UnknownCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("unknown")
                .requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                .then(Commands.literal("reload")
                        .executes(UnknownCommand::executeReload)
                )
                .then(Commands.literal("worldgen")
                        .then(Commands.literal("reload")
                                .executes(UnknownCommand::executeWorldgenReload)
                        )
                        .then(Commands.literal("status")
                                .executes(UnknownCommand::executeWorldgenStatus)
                        )
                )
                .then(Commands.literal("debug")
                        .executes(UnknownCommand::executeDebugToggle)
                )
                .then(Commands.literal("nickname")
                        .then(Commands.argument("value", StringArgumentType.string())
                                .executes(UnknownCommand::executeSetNickname)
                        )
                )
                .then(Commands.literal("ghost")
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests(UnknownCommand::suggestKnownPlayerNames)
                                .executes(UnknownCommand::executeGhostTarget)
                        )
                )
                .then(Commands.literal("skin")
                        .then(Commands.argument("value", StringArgumentType.string())
                                .executes(UnknownCommand::executeSetSkin)
                        )
                )
                .then(Commands.literal("reveal")
                        .then(Commands.literal("player")
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .suggests(UnknownCommand::suggestKnownPlayerNames)
                                        .executes(UnknownCommand::executeRevealTarget)
                                )
                                .executes(UnknownCommand::executeRevealRandom)
                        )
                        .then(Commands.literal("cancel")
                                .executes(UnknownCommand::executeRevealCancel)
                                .then(Commands.argument("target", StringArgumentType.word())
                                        .suggests(UnknownCommand::suggestRevealedPlayerNames)
                                        .executes(UnknownCommand::executeRevealCancelTarget)
                                )
                        )
                        .then(Commands.literal("interval")
                                .then(Commands.argument("hours", IntegerArgumentType.integer(1))
                                        .executes(UnknownCommand::executeRevealInterval)
                                )
                        )
                        .then(Commands.literal("duration")
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(1))
                                        .executes(UnknownCommand::executeRevealDuration)
                                )
                        )
                        .then(Commands.literal("minplayers")
                                .then(Commands.argument("n", IntegerArgumentType.integer(0))
                                        .executes(UnknownCommand::executeRevealMinPlayers)
                                )
                        )
                        .then(Commands.literal("status")
                                .executes(UnknownCommand::executeRevealStatus)
                        )
                )
        );
    }

    private static int executeReload(CommandContext<CommandSourceStack> context) {
        ConfigManager.load();
        ChunkWorldgenManager.load();
        RevelationManager.onConfigChanged(context.getSource().getServer());
        ProfileApplier.refreshAllOnline(context.getSource().getServer());
        context.getSource().sendSuccess(() -> MessageFormatter.format("[UnknownMod] Config and worldgen config reloaded."), false);
        DebugMessenger.debug(context.getSource().getServer(), "Config reloaded from /unknown reload.");
        return 1;
    }

    private static int executeWorldgenReload(CommandContext<CommandSourceStack> context) {
        ChunkWorldgenManager.load();
        context.getSource().sendSuccess(() -> MessageFormatter.format("[UnknownMod] Worldgen config reloaded."), false);
        DebugMessenger.debug(context.getSource().getServer(), "Worldgen config reloaded from /unknown worldgen reload.");
        return 1;
    }

    private static int executeWorldgenStatus(CommandContext<CommandSourceStack> context) {
        var config = ChunkWorldgenManager.getConfig();
        context.getSource().sendSuccess(() -> MessageFormatter.format(
                "[UnknownMod] Worldgen full chance: " + config.chance
                        + ", partial chance: " + config.partialChance
                        + ", overrides: " + config.overrides.size()
                        + ", partial excludes: " + (config.partialExclusions == null ? 0 : config.partialExclusions.size())
        ), false);
        DebugMessenger.debug(context.getSource().getServer(), "Worldgen config status requested.");
        return 1;
    }

    private static int executeDebugToggle(CommandContext<CommandSourceStack> context) {
        UnknownConfig config = ConfigManager.getConfig();
        config.debug.enabled = !config.debug.enabled;
        ConfigManager.save();

        MinecraftServer server = context.getSource().getServer();
        String state = config.debug.enabled ? "enabled" : "disabled";
        DebugMessenger.announce(server, "Debug mode " + state + ".");
        context.getSource().sendSuccess(() -> MessageFormatter.format("[UnknownMod] Debug mode " + state + "."), false);
        return 1;
    }

    private static int executeSetNickname(CommandContext<CommandSourceStack> context) {
        String value = StringArgumentType.getString(context, "value");
        UnknownConfig config = ConfigManager.getConfig();
        config.anonymous.name = value;
        ConfigManager.save();
        ProfileApplier.refreshAllOnline(context.getSource().getServer());
        context.getSource().sendSuccess(() -> MessageFormatter.format("[UnknownMod] Anonymous nickname set: " + value), false);
        DebugMessenger.debug(context.getSource().getServer(), "Anonymous nickname updated to " + value + ".");
        return 1;
    }

    private static int executeGhostTarget(CommandContext<CommandSourceStack> context) {
        ServerPlayer target = resolveTargetPlayer(context);
        if (target == null) {
            return 0;
        }

        if (GhostStateManager.getServerState(context.getSource().getServer()).isGhost(target.getUUID())) {
            if (!GhostStateManager.restoreGhost(context.getSource().getServer(), target)) {
                context.getSource().sendFailure(MessageFormatter.format("[UnknownMod] Failed to restore player: " + target.getName().getString()));
                return 0;
            }

            context.getSource().sendSuccess(() -> MessageFormatter.format("[UnknownMod] Player restored: " + target.getName().getString()), false);
            return 1;
        }

        if (!GhostStateManager.makeGhost(context.getSource().getServer(), target)) {
            context.getSource().sendFailure(MessageFormatter.format("[UnknownMod] Failed to make player a ghost: " + target.getName().getString()));
            return 0;
        }

        context.getSource().sendSuccess(() -> MessageFormatter.format("[UnknownMod] Player made ghost: " + target.getName().getString()), false);
        return 1;
    }

    private static int executeSetSkin(CommandContext<CommandSourceStack> context) {
        String value = StringArgumentType.getString(context, "value");
        UnknownConfig config = ConfigManager.getConfig();
        if (config.anonymous == null) {
            config.anonymous = new UnknownConfig.AnonymousSettings();
        }
        if (config.anonymous.skin == null) {
            config.anonymous.skin = new UnknownConfig.SkinSettings();
        }

        if ("texture".equalsIgnoreCase(value)) {
            DebugMessenger.debug(context.getSource().getServer(), "Anonymous skin command requested cached texture mode.");
            if (config.anonymous.skin.texture == null || config.anonymous.skin.texture.isBlank()
                    || config.anonymous.skin.signature == null || config.anonymous.skin.signature.isBlank()) {
                context.getSource().sendFailure(MessageFormatter.format("[UnknownMod] No cached texture/signature in config."));
                DebugMessenger.debug(context.getSource().getServer(), "Anonymous skin command failed: cached texture/signature is missing or blank.");
                return 0;
            }

            DebugMessenger.debug(context.getSource().getServer(),
                    "Anonymous skin kept from cached texture in config; valueLen=" + config.anonymous.skin.texture.length()
                            + ", signatureLen=" + config.anonymous.skin.signature.length() + ".");
        } else {
            DebugMessenger.debug(context.getSource().getServer(), "Anonymous skin command resolving nickname '" + value + "'.");
            SkinFetcher.SkinData textures = SkinFetcher.fetchTexturesByNickname(value);
            if (textures == null) {
                context.getSource().sendFailure(MessageFormatter.format("[UnknownMod] Failed to resolve skin for: " + value));
                DebugMessenger.debug(context.getSource().getServer(), "Failed to resolve skin texture for nickname " + value + ".");
                return 0;
            }

            config.anonymous.skin.texture = textures.value;
            config.anonymous.skin.signature = textures.signature;
            DebugMessenger.debug(context.getSource().getServer(),
                    "Anonymous skin resolved from nickname " + value + "; valueLen=" + textures.value.length()
                            + ", signatureLen=" + textures.signature.length() + ".");
        }

        ConfigManager.save();
        ProfileApplier.refreshAllOnline(context.getSource().getServer());
        context.getSource().sendSuccess(() -> MessageFormatter.format("[UnknownMod] Skin updated for all players."), false);
        DebugMessenger.debug(context.getSource().getServer(), "Anonymous skin config saved and online players refreshed.");
        return 1;
    }

    private static int executeRevealRandom(CommandContext<CommandSourceStack> context) {
        ServerPlayer target = RevelationManager.pickRandomEligiblePlayer(context.getSource().getServer());
        if (target == null) {
            context.getSource().sendFailure(MessageFormatter.format(ConfigManager.getConfig().revelation.messages.notEnoughPlayers));
            return 0;
        }

        return executeReveal(context, target);
    }

    private static int executeRevealTarget(CommandContext<CommandSourceStack> context) {
        ServerPlayer target = resolveTargetPlayer(context);
        if (target == null) {
            return 0;
        }

        return executeReveal(context, target);
    }

    private static ServerPlayer resolveTargetPlayer(CommandContext<CommandSourceStack> context) {
        String targetName = StringArgumentType.getString(context, "target");
        ServerPlayer target = IdentityStore.findOnlinePlayerByOriginalName(context.getSource().getServer(), targetName)
                .orElse(null);
        if (target != null) {
            return target;
        }

        context.getSource().sendFailure(MessageFormatter.format("[UnknownMod] Player not found: " + targetName));
        return null;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestKnownPlayerNames(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder
    ) {
        for (String name : IdentityStore.getKnownPlayerNames(context.getSource().getServer())) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    }

    private static int executeReveal(CommandContext<CommandSourceStack> context, ServerPlayer target) {
        if (!RevelationManager.startManualReveal(context.getSource().getServer(), target)) {
            context.getSource().sendFailure(MessageFormatter.format("[UnknownMod] Unable to start revelation."));
            return 0;
        }

        context.getSource().sendSuccess(() -> MessageFormatter.format("[UnknownMod] Revelation started for " + target.getName().getString()), false);
        return 1;
    }

    private static int executeRevealCancel(CommandContext<CommandSourceStack> context) {
        if (!RevelationManager.cancelReveal(context.getSource().getServer())) {
            context.getSource().sendFailure(MessageFormatter.format(ConfigManager.getConfig().revelation.messages.noActive));
            return 0;
        }

        context.getSource().sendSuccess(() -> MessageFormatter.format("[UnknownMod] Revelation cancelled."), false);
        return 1;
    }

    private static int executeRevealCancelTarget(CommandContext<CommandSourceStack> context) {
        String targetName = StringArgumentType.getString(context, "target");
        java.util.Optional<java.util.UUID> targetUuid = RevelationManager.findActiveRevealUuidByName(context.getSource().getServer(), targetName);
        if (targetUuid.isEmpty()) {
            context.getSource().sendFailure(MessageFormatter.format("[UnknownMod] Revealed player not found: " + targetName));
            return 0;
        }

        if (!RevelationManager.cancelReveal(context.getSource().getServer(), targetUuid.get())) {
            context.getSource().sendFailure(MessageFormatter.format(ConfigManager.getConfig().revelation.messages.noActive));
            return 0;
        }

        context.getSource().sendSuccess(() -> MessageFormatter.format("[UnknownMod] Revelation cancelled for " + targetName), false);
        return 1;
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestRevealedPlayerNames(
            CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder
    ) {
        for (String name : RevelationManager.getActiveRevealNames(context.getSource().getServer())) {
            builder.suggest(name);
        }
        return builder.buildFuture();
    }

    private static int executeRevealInterval(CommandContext<CommandSourceStack> context) {
        int hours = IntegerArgumentType.getInteger(context, "hours");
        UnknownConfig config = ConfigManager.getConfig();
        config.revelation.intervalHours = hours;
        ConfigManager.save();
        RevelationManager.onConfigChanged(context.getSource().getServer());
        context.getSource().sendSuccess(() -> MessageFormatter.format(config.revelation.messages.intervalSet, "hours", hours), false);
        DebugMessenger.debug(context.getSource().getServer(), "Revelation interval set to " + hours + " hours.");
        return 1;
    }

    private static int executeRevealDuration(CommandContext<CommandSourceStack> context) {
        int minutes = IntegerArgumentType.getInteger(context, "minutes");
        UnknownConfig config = ConfigManager.getConfig();
        config.revelation.durationMinutes = minutes;
        ConfigManager.save();
        RevelationManager.onConfigChanged(context.getSource().getServer());
        context.getSource().sendSuccess(() -> MessageFormatter.format(config.revelation.messages.durationSet, "minutes", minutes), false);
        DebugMessenger.debug(context.getSource().getServer(), "Revelation duration set to " + minutes + " minutes.");
        return 1;
    }

    private static int executeRevealMinPlayers(CommandContext<CommandSourceStack> context) {
        int n = IntegerArgumentType.getInteger(context, "n");
        UnknownConfig config = ConfigManager.getConfig();
        config.revelation.minPlayers = n;
        ConfigManager.save();
        RevelationManager.onConfigChanged(context.getSource().getServer());
        context.getSource().sendSuccess(() -> MessageFormatter.format(config.revelation.messages.minPlayersSet, "n", n), false);
        DebugMessenger.debug(context.getSource().getServer(), "Revelation minimum players set to " + n + ".");
        return 1;
    }

    private static int executeRevealStatus(CommandContext<CommandSourceStack> context) {
        String status = RevelationManager.getStatusLine(context.getSource().getServer());
        context.getSource().sendSuccess(() -> MessageFormatter.format(status), false);
        DebugMessenger.debug(context.getSource().getServer(), "Revelation status requested.");
        return 1;
    }
}
