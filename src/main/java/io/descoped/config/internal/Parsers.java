package io.descoped.config.internal;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict text-to-type conversions. Each throws {@link IllegalArgumentException} on malformed input. */
public final class Parsers {

    private static final Pattern SHORT_DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)", Pattern.CASE_INSENSITIVE);

    private Parsers() {
    }

    public static boolean parseBoolean(String text) {
        String t = text.trim();
        if (t.equalsIgnoreCase("true")) {
            return true;
        }
        if (t.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("expected true or false");
    }

    public static Duration parseDuration(String text) {
        String t = text.trim();
        if (t.matches("[-+]?[Pp].*")) {
            return Duration.parse(t);
        }
        Matcher m = SHORT_DURATION.matcher(t);
        if (!m.matches()) {
            throw new IllegalArgumentException("expected ISO-8601 (PT30S) or a number with ms, s, m, h or d");
        }
        long amount = Long.parseLong(m.group(1));
        return switch (m.group(2).toLowerCase(Locale.ROOT)) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            case "d" -> Duration.ofDays(amount);
            default -> throw new IllegalStateException(m.group(2));
        };
    }

    public static <E extends Enum<E>> E parseEnum(String text, Class<E> type) {
        String t = text.trim().replace('-', '_');
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(t)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("expected one of " + List.of(type.getEnumConstants()));
    }

    public static List<String> parseList(String text) {
        List<String> items = new ArrayList<>();
        for (String part : text.split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) {
                items.add(item);
            }
        }
        return List.copyOf(items);
    }
}
