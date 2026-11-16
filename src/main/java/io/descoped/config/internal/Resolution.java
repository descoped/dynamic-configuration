package io.descoped.config.internal;

import java.util.List;
import java.util.Map;

/**
 * The outcome of resolving a {@link Recipe}.
 *
 * @param values   the resolved values, unmodifiable, in source order
 * @param profiles the active profiles
 * @param sources  descriptions of the declared sources, for "looked in" messages
 */
public record Resolution(Map<String, Value> values, List<String> profiles, List<String> sources) {
}
