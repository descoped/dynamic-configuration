package io.descoped.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourcesTest {

    @TempDir
    Path dir;

    @Test
    void laterSourceWinsAndProvenanceIsKept() {
        DynamicConfiguration config = DynamicConfiguration.builder()
                .values(Map.of("a", "1", "b", "1"))
                .source(source("custom store", Map.of("b", "2", "c", "2")))
                .value("c", "3")
                .build();

        assertEquals("1", config.get("a"));
        assertEquals("2", config.get("b"));
        assertEquals("3", config.get("c"));
        assertEquals("values", config.sourceOf("a").orElseThrow());
        assertEquals("custom store", config.sourceOf("b").orElseThrow());
        assertTrue(config.sourceOf("missing").isEmpty());
    }

    @Test
    void keysKeepSourceOrderAndMapIsUnmodifiable() throws IOException {
        Path file = write("ordered.properties", "z=1\ny=2\nx=3\n");
        DynamicConfiguration config = DynamicConfiguration.builder().file(file).value("a", "4").build();

        assertEquals(List.of("z", "y", "x", "a"), List.copyOf(config.asMap().keySet()));
        assertThrows(UnsupportedOperationException.class, () -> config.asMap().put("k", "v"));
    }

    @Test
    void classpathReadsUtf8AndSkipsMissingResource() {
        DynamicConfiguration config = DynamicConfiguration.builder()
                .classpath("application.properties")
                .classpath("does-not-exist.properties")
                .build();

        assertEquals("Base æøå", config.get("app.name"));
        assertTrue(config.sourceOf("app.name").orElseThrow().startsWith("classpath "));
    }

    @Test
    void classpathUsesFirstMatchOnly() throws IOException {
        Path first = Files.createDirectories(dir.resolve("first"));
        Path second = Files.createDirectories(dir.resolve("second"));
        Files.writeString(first.resolve("dup.properties"), "who=first\n");
        Files.writeString(second.resolve("dup.properties"), "who=second\nonly.second=yes\n");

        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{first.toUri().toURL(), second.toUri().toURL()}, null)) {
            thread.setContextClassLoader(loader);
            DynamicConfiguration config = DynamicConfiguration.builder().classpath("dup.properties").build();

            assertEquals("first", config.get("who"));
            assertFalse(config.find("only.second").isPresent());
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    @Test
    void resourceUrlReadsFromEncapsulatedPackageAndNullIsMissing() {
        DynamicConfiguration config = DynamicConfiguration.builder()
                .resource(SourcesTest.class.getResource("packaged.properties"))
                .resource(null)
                .build();

        assertEquals("from encapsulated package", config.get("packaged"));
        assertTrue(config.sourceOf("packaged").orElseThrow().startsWith("resource "));
    }

    @Test
    void fileIsOptionalButRequiredFileMustExist() throws IOException {
        Path file = write("app.properties", "name=æøå\n");
        DynamicConfiguration config = DynamicConfiguration.builder()
                .file(file)
                .file(dir.resolve("missing.properties"))
                .build();

        assertEquals("æøå", config.get("name"));
        assertEquals("file " + file.toAbsolutePath().normalize(), config.sourceOf("name").orElseThrow());

        ConfigurationException e = assertThrows(ConfigurationException.class,
                () -> DynamicConfiguration.builder().requiredFile(dir.resolve("missing.properties")).build());
        assertTrue(e.getMessage().contains("missing.properties"));
    }

    @Test
    void malformedPropertiesNameTheFile() throws IOException {
        Path file = write("broken.properties", "k=\\u12zz\n");
        ConfigurationException e = assertThrows(ConfigurationException.class,
                () -> DynamicConfiguration.builder().file(file).build());
        assertTrue(e.getMessage().contains("broken.properties"), e.getMessage());
        assertTrue(e.getMessage().contains("malformed properties"), e.getMessage());
    }

    @Test
    void invalidUtf8FailsInsteadOfBeingReplaced() throws IOException {
        Path file = Files.write(dir.resolve("latin1.properties"), "name=\u00e6\u00f8\u00e5\n".getBytes(StandardCharsets.ISO_8859_1));
        ConfigurationException e = assertThrows(ConfigurationException.class,
                () -> DynamicConfiguration.builder().file(file).build());
        assertTrue(e.getMessage().contains("latin1.properties: not valid UTF-8"), e.getMessage());
    }

    @Test
    void directoryReadsOneKeyPerFileAsSecrets() throws IOException {
        Path secrets = Files.createDirectories(dir.resolve("secrets"));
        Files.writeString(secrets.resolve("db.password"), "s3cr3t\n");
        Files.writeString(secrets.resolve("api.user"), "svc\r\n");
        Files.writeString(secrets.resolve("multi"), "line1\nline2\n\n");
        Files.writeString(secrets.resolve(".hidden"), "ignored");

        DynamicConfiguration config = DynamicConfiguration.builder().directory(secrets).build();

        assertEquals("s3cr3t", config.get("db.password"));
        assertEquals("svc", config.get("api.user"));
        assertEquals("line1\nline2\n", config.get("multi"));
        assertFalse(config.find(".hidden").isPresent());
        assertEquals("secret file " + secrets.toAbsolutePath().normalize().resolve("db.password"),
                config.sourceOf("db.password").orElseThrow());
        assertFalse(config.describe().contains("svc"), "every directory value is secret");
    }

    @Test
    void directoryHandlesKubernetesSymlinkLayout() throws IOException {
        // Kubernetes: key -> ..data/key, ..data -> ..2026_11_16_12_00_00.123/
        Path volume = Files.createDirectories(dir.resolve("volume"));
        Path versioned = Files.createDirectories(volume.resolve("..2026_11_16_12_00_00.123"));
        Files.writeString(versioned.resolve("token"), "abc");
        Files.createSymbolicLink(volume.resolve("..data"), versioned.getFileName());
        Files.createSymbolicLink(volume.resolve("token"), Path.of("..data", "token"));

        DynamicConfiguration config = DynamicConfiguration.builder().directory(volume).build();

        assertEquals(Map.of("token", "abc"), config.asMap());
    }

    @Test
    void missingDirectoryIsSkipped() {
        assertTrue(DynamicConfiguration.builder().directory(dir.resolve("none")).build().asMap().isEmpty());
    }

    @Test
    void nullKeysAndValuesAreRejected() {
        Map<String, String> withNullValue = new LinkedHashMap<>();
        withNullValue.put("key", null);
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> DynamicConfiguration.builder().values(withNullValue));
        assertTrue(e.getMessage().contains("key"));

        Map<String, String> withNullKey = new LinkedHashMap<>();
        withNullKey.put(null, "v");
        assertThrows(NullPointerException.class, () -> DynamicConfiguration.builder().values(withNullKey));
    }

    @Test
    void customSourceReturningNullFails() {
        ConfigurationException e = assertThrows(ConfigurationException.class,
                () -> DynamicConfiguration.builder().source(source("broken", null)).build());
        assertTrue(e.getMessage().contains("broken"));
    }

    private Path write(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    static ConfigurationSource source(String name, Map<String, String> values) {
        return new ConfigurationSource() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Map<String, String> load() {
                return values == null ? null : new LinkedHashMap<>(values);
            }
        };
    }
}
