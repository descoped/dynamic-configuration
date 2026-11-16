package io.descoped.config.internal;

import java.util.Objects;

/**
 * A resolved configuration value with its provenance.
 *
 * @param text   the value
 * @param source where it came from, for example {@code env SERVER_PORT}
 * @param secret whether it must be masked in diagnostics
 */
public record Value(String text, String source, boolean secret) {

    public Value {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(source, "source");
    }

    public Value asSecret() {
        return secret ? this : new Value(text, source, true);
    }

    /** The value as it may be shown in logs and messages. */
    public String display() {
        return secret ? "****" : text;
    }
}
