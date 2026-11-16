package io.descoped.config;

import java.util.Map;

/**
 * A source of configuration values supplied by the application, for formats and systems the library does not read
 * itself (YAML, JSON, a vault, a remote store). Add it with {@link DynamicConfiguration.Builder#source}.
 *
 * <p>{@link #load()} is called once when the configuration is built and again on every
 * {@link DynamicConfiguration#reload()}.
 */
public interface ConfigurationSource {

    /**
     * A short description of where the values come from, used for provenance and diagnostics, for example
     * {@code "vault secret/app"}.
     *
     * @return the source description, never {@code null}
     */
    String name();

    /**
     * Reads the values of this source.
     *
     * @return the keys and values, in the order they should appear; neither keys nor values may be {@code null}
     */
    Map<String, String> load();

    /**
     * Whether every value of this source is a secret, to be masked in diagnostics.
     *
     * @return {@code true} if all values are secret; {@code false} by default
     */
    default boolean secret() {
        return false;
    }
}
