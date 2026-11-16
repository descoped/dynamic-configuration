package io.descoped.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordBindingTest {

    public enum Level {LOW, HIGH}

    public record Pool(int size, Duration idleTimeout) {
    }

    public record Server(String host, int port, boolean secure, Duration idleTimeout, Optional<String> contextPath,
                         List<String> tags, Level level, Pool pool, Optional<Pool> backup, URI home, Path dir,
                         Long maxBytes) {
    }

    public record Required(String a, int b, Duration c) {
    }

    public record Unsupported(Object thing) {
    }

    @Test
    void bindsKebabAndCamelKeysNestedRecordsAndOptionals() {
        DynamicConfiguration config = DynamicConfiguration.builder()
                .values(Map.ofEntries(
                        Map.entry("server.host", "localhost"),
                        Map.entry("server.port", "8080"),
                        Map.entry("server.secure", "true"),
                        Map.entry("server.idle-timeout", "30s"),
                        Map.entry("server.tags", "a, b"),
                        Map.entry("server.level", "high"),
                        Map.entry("server.pool.size", "4"),
                        Map.entry("server.pool.idleTimeout", "PT1M"),
                        Map.entry("server.home", "https://descoped.io"),
                        Map.entry("server.dir", "/tmp"),
                        Map.entry("server.max-bytes", "1024")))
                .build();

        Server server = config.bind("server", Server.class);

        assertEquals(new Server("localhost", 8080, true, Duration.ofSeconds(30), Optional.empty(), List.of("a", "b"),
                Level.HIGH, new Pool(4, Duration.ofMinutes(1)), Optional.empty(), URI.create("https://descoped.io"),
                Path.of("/tmp"), 1024L), server);
        assertEquals(server, config.subset("server").bind("", Server.class));
    }

    @Test
    void presentOptionalsAreBound() {
        DynamicConfiguration config = DynamicConfiguration.builder()
                .values(Map.ofEntries(
                        Map.entry("host", "h"), Map.entry("port", "1"), Map.entry("secure", "false"),
                        Map.entry("idle-timeout", "1s"), Map.entry("context-path", "/api"), Map.entry("tags", ""),
                        Map.entry("level", "LOW"), Map.entry("pool.size", "1"), Map.entry("pool.idle-timeout", "1s"),
                        Map.entry("backup.size", "2"), Map.entry("backup.idle-timeout", "2s"),
                        Map.entry("home", "x:y"), Map.entry("dir", "d"), Map.entry("max-bytes", "3")))
                .build();

        Server server = config.bind("", Server.class);

        assertEquals(Optional.of("/api"), server.contextPath());
        assertEquals(Optional.of(new Pool(2, Duration.ofSeconds(2))), server.backup());
        assertEquals(List.of(), server.tags());
    }

    @Test
    void partlyConfiguredOptionalRecordIsReportedNotNullPointer() {
        DynamicConfiguration config = DynamicConfiguration.builder()
                .values(Map.of("size", "2", "idle-timeout", "1s", "backup.size", "2"))
                .build();

        ConfigurationException e = assertThrows(ConfigurationException.class, () -> config.bind("", Holder.class));
        assertTrue(e.getMessage().contains("missing key 'backup.idle-timeout'"), e.getMessage());
    }

    public record Holder(int size, Duration idleTimeout, Optional<Pool> backup) {
    }

    @Test
    void causeIsKeptForPlainValuesOnly() {
        ConfigurationException plain = assertThrows(ConfigurationException.class, () -> DynamicConfiguration.builder()
                .values(Map.of("size", "x", "idle-timeout", "1s")).build().bind("", Pool.class));
        assertInstanceOf(NumberFormatException.class, plain.getCause());

        ConfigurationException secret = assertThrows(ConfigurationException.class, () -> DynamicConfiguration.builder()
                .values(Map.of("size", "s3cr3t", "idle-timeout", "1s")).secret("size").build().bind("", Pool.class));
        assertNull(secret.getCause());
        assertFalse(ReadingTest.stackTrace(secret).contains("s3cr3t"));
    }

    public record Validated(String name) {
        public Validated {
            if (name.isBlank()) {
                throw new IllegalArgumentException("name is blank: '" + name + "'");
            }
        }
    }

    @Test
    void constructorFailureNamesTheTypeOnly() {
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> DynamicConfiguration.builder()
                .value("name", " ").build().bind("", Validated.class));
        assertTrue(e.getMessage().contains("threw java.lang.IllegalArgumentException"), e.getMessage());
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
    }

    @Test
    void reportsEveryMissingAndMalformedKeyAtOnce() {
        DynamicConfiguration config = DynamicConfiguration.builder().value("x.b", "not-a-number").build();

        ConfigurationException e = assertThrows(ConfigurationException.class, () -> config.bind("x", Required.class));

        assertTrue(e.getMessage().contains("missing key 'x.a'"), e.getMessage());
        assertTrue(e.getMessage().contains("'x.b' from values is not a valid int"), e.getMessage());
        assertTrue(e.getMessage().contains("missing key 'x.c'"), e.getMessage());
    }

    @Test
    void unsupportedComponentTypeIsReported() {
        DynamicConfiguration config = DynamicConfiguration.builder().value("thing", "x").build();
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> config.bind("", Unsupported.class));
        assertTrue(e.getMessage().contains("unsupported type java.lang.Object"), e.getMessage());
    }

    @Test
    void kebabCase() {
        assertEquals("idle-timeout", RecordBinder.kebab("idleTimeout"));
        assertEquals("url-path", RecordBinder.kebab("URLPath"));
        assertEquals("max2-x", RecordBinder.kebab("max2X"));
        assertEquals("host", RecordBinder.kebab("host"));
    }
}
