package io.descoped.config;

/**
 * Thrown when a configuration cannot be built or a value cannot be read. The message always names the key(s) and,
 * where known, the source(s) involved. Values of secret keys never appear in the message.
 */
public class ConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a message.
     *
     * @param message what went wrong, naming the key(s) and source(s)
     */
    public ConfigurationException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a message and a cause.
     *
     * @param message what went wrong, naming the key(s) and source(s)
     * @param cause   the underlying failure
     */
    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
