package io.descoped.config.internal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Everything a builder declared, frozen, so that a configuration can be reloaded with the same declarations.
 *
 * @param layers           the sources in order
 * @param profiles         explicitly set profiles, or {@code null} to read them from system properties/environment
 * @param secretKeys       keys explicitly marked secret
 * @param environment      supplies the environment variables
 * @param systemProperties supplies the system properties
 */
public record Recipe(List<Layer> layers,
                     List<String> profiles,
                     Set<String> secretKeys,
                     Supplier<Map<String, String>> environment,
                     Supplier<Map<String, String>> systemProperties) {
}
