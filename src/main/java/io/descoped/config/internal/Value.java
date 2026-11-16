package io.descoped.config.internal;

import java.util.Objects;

/**
 * A resolved configuration value with its provenance.
 *
 * @param text    the value
 * @param source  where it came from, for example {@code env SERVER_PORT}
 * @param secret  whether it must be masked in diagnostics
 * @param literal whether the text is taken as is, without resolving {@code ${..}} references: true for values from
 *                secret sources, whose content (a generated password, a key) is not configuration syntax
 */
public record Value(String text, String source, boolean secret, boolean literal) {

    public Value {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(source, "source");
    }

    /** A value whose references are resolved. */
    public Value(String text, String source, boolean secret) {
        this(text, source, secret, false);
    }

    public Value asSecret() {
        return secret ? this : new Value(text, source, true, literal);
    }

    /** The value as it may be shown in logs and messages. */
    public String display() {
        return secret ? "****" : text;
    }
}
