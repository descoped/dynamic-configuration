package consumer.bound;

import java.time.Duration;

/**
 * Bound from keys under {@code settings}.
 *
 * @param name       the name
 * @param retryDelay read from {@code settings.retry-delay}
 */
public record Settings(String name, Duration retryDelay) {
}
