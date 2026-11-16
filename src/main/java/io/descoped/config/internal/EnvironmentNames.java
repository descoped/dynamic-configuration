package io.descoped.config.internal;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The MicroProfile Config mapping from a configuration key to environment variable names. */
public final class EnvironmentNames {

    private EnvironmentNames() {
    }

    /**
     * The names to look for, in order: the key as is; every non-alphanumeric character replaced by {@code _}; the
     * same in upper case. Duplicates are removed.
     */
    public static List<String> candidates(String prefix, String key) {
        String sanitized = key.replaceAll("[^A-Za-z0-9]", "_");
        Set<String> names = new LinkedHashSet<>();
        names.add(prefix + key);
        names.add(prefix + sanitized);
        names.add(prefix + sanitized.toUpperCase(Locale.ROOT));
        return List.copyOf(names);
    }
}
