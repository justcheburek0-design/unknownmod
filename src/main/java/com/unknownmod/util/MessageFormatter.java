package com.unknownmod.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.PlainTextContent;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Formatting;

import java.util.Locale;

public final class MessageFormatter {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer LEGACY_AMPERSAND = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.legacySection();

    private MessageFormatter() {
    }

    public static Text format(String template, Object... placeholders) {
        if (template == null || template.isBlank()) {
            return Text.literal("");
        }

        String resolved = replacePlaceholders(template, placeholders);
        Component component = deserializeComponent(resolved);
        String legacy = LEGACY_SECTION.serialize(component);
        return fromLegacySection(legacy);
    }

    public static Text formatWithTextPlaceholder(String template, String placeholderKey, Text placeholderValue, Object... placeholders) {
        if (template == null || template.isBlank()) {
            return Text.literal("");
        }

        if (placeholderKey == null || placeholderKey.isBlank() || placeholderValue == null) {
            return format(template, placeholders);
        }

        String token = "%" + placeholderKey + "%";
        if (!template.contains(token)) {
            return format(template, placeholders);
        }

        String marker = "\u0000unknownmod:" + placeholderKey + "\u0000";
        String resolved = template.replace(token, marker);
        Text formatted = format(resolved, placeholders);
        return replaceMarker(formatted, marker, placeholderValue);
    }

    private static String replacePlaceholders(String template, Object... placeholders) {
        if (placeholders == null || placeholders.length == 0) {
            return template;
        }

        String result = template;
        int limit = placeholders.length - (placeholders.length % 2);
        for (int i = 0; i < limit; i += 2) {
            Object keyObject = placeholders[i];
            Object valueObject = placeholders[i + 1];
            if (keyObject == null || valueObject == null) {
                continue;
            }

            String key = String.valueOf(keyObject);
            String value = MINI_MESSAGE.escapeTags(String.valueOf(valueObject));
            result = result.replace("%" + key + "%", value);
        }

        return result;
    }

    private static Component deserializeComponent(String input) {
        try {
            return MINI_MESSAGE.deserialize(input);
        } catch (RuntimeException ignored) {
            return LEGACY_AMPERSAND.deserialize(input);
        }
    }

    private static Text fromLegacySection(String input) {
        MutableText result = Text.literal("");
        StringBuilder buffer = new StringBuilder();
        StyleState state = new StyleState();

        int index = 0;
        while (index < input.length()) {
            char current = input.charAt(index);
            if (current == '\u00a7' && index + 1 < input.length()) {
                int consumed = applyLegacyCode(input, index + 1, buffer, state, result);
                if (consumed > 0) {
                    index += consumed + 1;
                    continue;
                }
            }

            buffer.append(current);
            index++;
        }

        appendBuffer(buffer, state, result);
        return result;
    }

    private static int applyLegacyCode(String input, int codeIndex, StringBuilder buffer, StyleState state, MutableText result) {
        char code = Character.toLowerCase(input.charAt(codeIndex));
        if (code == 'x' && codeIndex + 12 < input.length()) {
            Integer rgb = parseHexColor(input, codeIndex + 1);
            if (rgb != null) {
                appendBuffer(buffer, state, result);
                state.resetDecorations();
                state.color = TextColor.fromRgb(rgb);
                return 13;
            }
        }

        Formatting formatting = legacyFormatting(code);
        if (formatting == null) {
            return 0;
        }

        appendBuffer(buffer, state, result);
        if (formatting == Formatting.RESET) {
            state.reset();
            return 1;
        }

        if (formatting.isColor()) {
            state.resetDecorations();
            state.color = TextColor.fromFormatting(formatting);
            return 1;
        }

        switch (formatting) {
            case BOLD -> state.bold = true;
            case ITALIC -> state.italic = true;
            case UNDERLINE -> state.underlined = true;
            case STRIKETHROUGH -> state.strikethrough = true;
            case OBFUSCATED -> state.obfuscated = true;
            default -> {
                return 0;
            }
        }

        return 1;
    }

    private static Integer parseHexColor(String input, int startIndex) {
        if (startIndex + 11 >= input.length()) {
            return null;
        }

        StringBuilder hex = new StringBuilder(6);
        for (int i = 0; i < 6; i++) {
            int sectionIndex = startIndex + (i * 2);
            if (input.charAt(sectionIndex) != '\u00a7') {
                return null;
            }
            hex.append(input.charAt(sectionIndex + 1));
        }

        try {
            return Integer.parseInt(hex.toString(), 16);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Formatting legacyFormatting(char code) {
        return switch (code) {
            case '0' -> Formatting.BLACK;
            case '1' -> Formatting.DARK_BLUE;
            case '2' -> Formatting.DARK_GREEN;
            case '3' -> Formatting.DARK_AQUA;
            case '4' -> Formatting.DARK_RED;
            case '5' -> Formatting.DARK_PURPLE;
            case '6' -> Formatting.GOLD;
            case '7' -> Formatting.GRAY;
            case '8' -> Formatting.DARK_GRAY;
            case '9' -> Formatting.BLUE;
            case 'a' -> Formatting.GREEN;
            case 'b' -> Formatting.AQUA;
            case 'c' -> Formatting.RED;
            case 'd' -> Formatting.LIGHT_PURPLE;
            case 'e' -> Formatting.YELLOW;
            case 'f' -> Formatting.WHITE;
            case 'k' -> Formatting.OBFUSCATED;
            case 'l' -> Formatting.BOLD;
            case 'm' -> Formatting.STRIKETHROUGH;
            case 'n' -> Formatting.UNDERLINE;
            case 'o' -> Formatting.ITALIC;
            case 'r' -> Formatting.RESET;
            default -> null;
        };
    }

    private static void appendBuffer(StringBuilder buffer, StyleState state, MutableText result) {
        if (buffer.isEmpty()) {
            return;
        }

        MutableText segment = Text.literal(buffer.toString());
        segment.setStyle(state.toStyle());
        result.append(segment);
        buffer.setLength(0);
    }

    private static Text replaceMarker(Text text, String marker, Text replacement) {
        MutableText result = Text.literal("");
        result.setStyle(text.getStyle());
        appendReplacedText(result, text, marker, replacement);
        return result;
    }

    private static void appendReplacedText(MutableText result, Text text, String marker, Text replacement) {
        if (text.getContent() instanceof PlainTextContent plainTextContent) {
            appendPlainText(result, plainTextContent.string(), text.getStyle(), marker, replacement);
        } else {
            result.append(text.copyContentOnly().setStyle(text.getStyle()));
        }

        for (Text sibling : text.getSiblings()) {
            result.append(replaceMarker(sibling, marker, replacement));
        }
    }

    private static void appendPlainText(MutableText result, String string, Style style, String marker, Text replacement) {
        int start = 0;
        int markerIndex;
        while ((markerIndex = string.indexOf(marker, start)) >= 0) {
            if (markerIndex > start) {
                MutableText segment = Text.literal(string.substring(start, markerIndex));
                segment.setStyle(style);
                result.append(segment);
            }

            result.append(replacement.copy());
            start = markerIndex + marker.length();
        }

        if (start < string.length()) {
            MutableText segment = Text.literal(string.substring(start));
            segment.setStyle(style);
            result.append(segment);
        }
    }

    private static final class StyleState {
        private TextColor color;
        private boolean bold;
        private boolean italic;
        private boolean underlined;
        private boolean strikethrough;
        private boolean obfuscated;

        private void reset() {
            color = null;
            resetDecorations();
        }

        private void resetDecorations() {
            bold = false;
            italic = false;
            underlined = false;
            strikethrough = false;
            obfuscated = false;
        }

        private Style toStyle() {
            Style style = Style.EMPTY;
            if (color != null) {
                style = style.withColor(color);
            }
            if (bold) {
                style = style.withBold(true);
            }
            if (italic) {
                style = style.withItalic(true);
            }
            if (underlined) {
                style = style.withUnderline(true);
            }
            if (strikethrough) {
                style = style.withStrikethrough(true);
            }
            if (obfuscated) {
                style = style.withObfuscated(true);
            }
            return style;
        }
    }
}
