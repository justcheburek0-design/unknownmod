package com.unknownmod.util;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.ChatFormatting;

import java.util.Locale;

public final class MessageFormatter {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();

    private MessageFormatter() {
    }

    public static MutableComponent format(String template, Object... placeholders) {
        if (template == null || template.isBlank()) {
            return net.minecraft.network.chat.Component.literal("");
        }

        String resolved = replacePlaceholders(template, placeholders);
        net.kyori.adventure.text.Component component = deserializeComponent(resolved);
        String legacy = LEGACY_SECTION.serialize(component);
        return fromLegacySection(legacy);
    }

    public static MutableComponent formatWithTextPlaceholder(String template, String placeholderKey, MutableComponent placeholderValue, Object... placeholders) {
        if (template == null || template.isBlank()) {
            return net.minecraft.network.chat.Component.literal("");
        }

        if (placeholderKey == null || placeholderKey.isBlank() || placeholderValue == null) {
            return format(template, placeholders);
        }

        String token = "%" + placeholderKey + "%";
        String resolved = template.replace(token, placeholderValue.getString());
        resolved = replacePlaceholders(resolved, placeholders);
        net.kyori.adventure.text.Component component = deserializeComponent(resolved);
        String legacy = LEGACY_SECTION.serialize(component);
        return fromLegacySection(legacy);
    }

    private static String replacePlaceholders(String template, Object... placeholders) {
        String result = template;
        for (int i = 0; i < placeholders.length; i++) {
            result = result.replace("{" + i + "}", String.valueOf(placeholders[i]));
        }
        return result;
    }

    private static net.kyori.adventure.text.Component deserializeComponent(String input) {
        if (input == null || input.isBlank()) {
            return net.kyori.adventure.text.Component.empty();
        }
        if (input.contains("<") && input.contains(">")) {
            return MINI_MESSAGE.deserialize(input);
        }
        return LEGACY_AMPERSAND.deserialize(input);
    }

    private static MutableComponent fromLegacySection(String legacy) {
        if (legacy == null || legacy.isBlank()) {
            return net.minecraft.network.chat.Component.literal("");
        }

        MutableComponent result = net.minecraft.network.chat.Component.literal("");
        StringBuilder current = new StringBuilder();
        ChatFormatting currentFormat = null;

        for (int i = 0; i < legacy.length(); i++) {
            char c = legacy.charAt(i);
            if (c == '\u00A7' && i + 1 < legacy.length()) {
                if (!current.isEmpty()) {
                    appendWithStyle(result, current.toString(), currentFormat);
                    current.setLength(0);
                }
                char code = legacy.charAt(i + 1);
                currentFormat = getByCode(code);
                i++;
            } else {
                current.append(c);
            }
        }

        if (!current.isEmpty()) {
            appendWithStyle(result, current.toString(), currentFormat);
        }

        return result;
    }

    private static void appendWithStyle(MutableComponent parent, String text, ChatFormatting format) {
        MutableComponent child = net.minecraft.network.chat.Component.literal(text);
        if (format != null) {
            child.setStyle(Style.EMPTY.withColor(format));
        }
        parent.append(child);
    }

    private static ChatFormatting getByCode(char code) {
        for (ChatFormatting formatting : ChatFormatting.values()) {
            if (formatting.getId() == code) {
                return formatting;
            }
        }
        return null;
    }
}
