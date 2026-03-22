package com.unknownmod.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.unknownmod.config.ConfigManager;
import com.unknownmod.config.UnknownConfig;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

public class UnknownCommand {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("unknown")
            //.requires(source -> source.hasPermission(2))
            .then(CommandManager.literal("reload")
                .executes(UnknownCommand::executeReload)
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
        );
    }

    private static int executeReload(CommandContext<ServerCommandSource> context) {
        ConfigManager.load();
        context.getSource().sendFeedback(() -> Text.literal("§a[UnknownMod] Конфигурация успешно перезагружена!"), false);
        return 1;
    }

    private static int executeSetSkin(CommandContext<ServerCommandSource> context, String type) {
        String value = StringArgumentType.getString(context, "value");
        UnknownConfig config = ConfigManager.getConfig();

        switch (type) {
            case "texture":
                config.anonymous.skin.texture = value;
                break;
            case "nickname":
                config.anonymous.skin.nickname = value;
                break;
            case "signature":
                config.anonymous.skin.signature = value;
                break;
        }

        ConfigManager.save();
        context.getSource().sendFeedback(() -> Text.literal("§a[UnknownMod] Значение " + type + " успешно обновлено!"), false);
        return 1;
    }
}
