package io.descoped.config.internal;

import java.util.List;
import java.util.Locale;

/** Recognises keys that hold secrets by the name of their last segment. */
public final class Secrets {

    private static final List<String> MARKERS =
            List.of("password", "passwd", "secret", "token", "credential", "privatekey", "apikey");

    private Secrets() {
    }

    /** Whether the last segment of {@code key}, ignoring case, {@code -} and {@code _}, names a secret. */
    public static boolean looksSecret(String key) {
        String last = key.substring(key.lastIndexOf('.') + 1)
                .toLowerCase(Locale.ROOT)
                .replace("-", "")
                .replace("_", "");
        for (String marker : MARKERS) {
            if (last.contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
