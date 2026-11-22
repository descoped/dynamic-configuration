package io.descoped.config.internal;

import io.descoped.config.ConfigurationSource;

import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One declared source, in builder order. Base layers define keys; override layers only replace values of keys the
 * base layers define.
 */
public sealed interface Layer {

    /** A short description for "looked in" lists. */
    String describe();

    /** Layers that only override existing keys. */
    sealed interface Overriding extends Layer {
    }

    record Classpath(String name) implements Layer {
        @Override
        public String describe() {
            return "classpath " + name;
        }
    }

    record Resource(URL url) implements Layer {
        @Override
        public String describe() {
            return "resource " + url;
        }
    }

    record PropertiesFile(Path path, boolean required) implements Layer {
        @Override
        public String describe() {
            return "file " + path.toAbsolutePath().normalize();
        }
    }

    record Directory(Path path) implements Layer {
        @Override
        public String describe() {
            return "secret directory " + path.toAbsolutePath().normalize();
        }
    }

    record Values(Map<String, String> values) implements Layer {
        public Values {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        @Override
        public String describe() {
            return "values";
        }
    }

    record Custom(ConfigurationSource source) implements Layer {
        @Override
        public String describe() {
            return source.name();
        }
    }

    /** With {@code strict}, every variable starting with the (non-empty) prefix must match a known key. */
    record Environment(String prefix, boolean strict) implements Overriding {
        @Override
        public String describe() {
            return prefix.isEmpty() ? "environment" : "environment (prefix " + prefix + ")";
        }
    }

    record SystemProperties(String prefix) implements Overriding {
        @Override
        public String describe() {
            return prefix.isEmpty() ? "system properties" : "system properties (prefix " + prefix + ")";
        }
    }
}
