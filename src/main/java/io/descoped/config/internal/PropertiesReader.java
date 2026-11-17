package io.descoped.config.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Reads {@code .properties} content as UTF-8, keeping the order of the keys in the file. Malformed UTF-8 fails with a
 * {@link java.nio.charset.CharacterCodingException} instead of being replaced silently.
 */
final class PropertiesReader {

    private PropertiesReader() {
    }

    static Map<String, String> read(InputStream in) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT))) {
            new OrderRecordingProperties(entries).load(reader);
        }
        return entries;
    }

    /** {@link Properties#load} reports every entry through {@code put}; record them in file order. */
    private static final class OrderRecordingProperties extends Properties {
        private static final long serialVersionUID = 1L;
        private final transient Map<String, String> entries;

        OrderRecordingProperties(Map<String, String> entries) {
            this.entries = entries;
        }

        @Override
        public synchronized Object put(Object key, Object value) {
            return entries.put((String) key, (String) value);
        }

        // A load sink that holds nothing itself: only identity makes sense, Properties' content equality does not.
        @Override
        public synchronized boolean equals(Object other) {
            return this == other;
        }

        @Override
        public synchronized int hashCode() {
            return System.identityHashCode(this);
        }
    }
}
