package io.descoped.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverridesTest {

    private static DynamicConfiguration.Builder base(Map<String, String> env, Map<String, String> props) {
        return DynamicConfiguration.builder()
                .environmentProvider(() -> env)
                .systemPropertiesProvider(() -> props)
                .profiles();
    }

    @Test
    void environmentUsesMicroProfileMapping() {
        DynamicConfiguration config = base(Map.of(
                "services.jetty.port", "1",                          // exact
                "server_context_path", "/lower",                     // non-alphanumerics -> _
                "SERVER_CONTEXT_PATH", "/upper",                     // ignored: lower form found first
                "DB_URL", "jdbc:x",                                  // upper case
                "UNRELATED", "noise"), Map.of())
                .values(Map.of("services.jetty.port", "0", "server.context-path", "/", "db.url", "none"))
                .environment()
                .build();

        assertEquals("1", config.get("services.jetty.port"));
        assertEquals("/lower", config.get("server.context-path"));
        assertEquals("jdbc:x", config.get("db.url"));
        assertEquals("env DB_URL", config.sourceOf("db.url").orElseThrow());
        assertFalse(config.find("UNRELATED").isPresent(), "environment never adds keys");
    }

    @Test
    void environmentPrefix() {
        DynamicConfiguration config = base(Map.of("MYAPP_SERVER_PORT", "9", "SERVER_PORT", "7"), Map.of())
                .value("server.port", "0")
                .environment("MYAPP_")
                .build();

        assertEquals(9, config.getInt("server.port"));
    }

    @Test
    void environmentMatchingTwoKeysFails() {
        DynamicConfiguration.Builder builder = base(Map.of("A_B", "x"), Map.of())
                .values(Map.of("a.b", "1", "a-b", "2"))
                .environment();
        ConfigurationException e = assertThrows(ConfigurationException.class, builder::build);
        assertTrue(e.getMessage().contains("A_B"));
        assertTrue(e.getMessage().contains("a.b") && e.getMessage().contains("a-b"));
    }

    @Test
    void environmentCanOverrideKeysDefinedByLaterSourcesButLaterSourcesStillWin() {
        DynamicConfiguration config = base(Map.of("LATE", "env"), Map.of())
                .environment()
                .value("late", "values")
                .build();

        assertEquals("values", config.get("late"));
    }

    @Test
    void systemPropertiesOverrideKnownKeysOnly() {
        DynamicConfiguration config = base(Map.of(), Map.of("server.port", "5", "java.version", "99", "app.server.host", "h"))
                .values(Map.of("server.port", "0", "server.host", "localhost"))
                .systemProperties()
                .systemProperties("app.")
                .build();

        assertEquals(5, config.getInt("server.port"));
        assertEquals("h", config.get("server.host"));
        assertEquals("system property app.server.host", config.sourceOf("server.host").orElseThrow());
        assertFalse(config.find("java.version").isPresent(), "system properties never add keys");
    }

    @Test
    void overridingASecretKeepsItSecret() {
        DynamicConfiguration config = base(Map.of("DB_PASS", "from-env"), Map.of())
                .value("db.pass", "x")
                .secret("db.pass")
                .environment()
                .build();

        assertEquals("from-env", config.get("db.pass"));
        assertFalse(config.describe().contains("from-env"));
    }

    @Test
    void strictEnvironmentNamesEveryUnknownVariableButNoValue() {
        DynamicConfiguration.Builder builder = base(Map.of(
                "APP_SERVER_PORT", "9",
                "APP_SERVER_PROT", "value-one",
                "APP_DB_URLL", "value-two",
                "OTHER_THING", "x"), Map.of())
                .values(Map.of("server.port", "0", "db.url", "none"))
                .strictEnvironment("APP_");
        ConfigurationException e = assertThrows(ConfigurationException.class, builder::build);
        String message = e.getMessage();
        assertTrue(message.contains("APP_DB_URLL, APP_SERVER_PROT"), message);
        assertTrue(message.contains("APP_<KEY>") && message.contains("APP_SERVER_PORT for server.port"), message);
        assertFalse(message.contains("OTHER_THING"), message);
        assertFalse(message.contains("value-one") || message.contains("value-two"), message);
    }

    @Test
    void strictEnvironmentAcceptsEveryNameTheMappingReads() {
        DynamicConfiguration config = base(Map.of(
                "APP_server.context-path", "/exact",
                "APP_server_context_path", "/lower",                  // shadowed by the exact name, still known
                "APP_DB_URL", "jdbc:x",
                "SERVER_PORT", "7"), Map.of())                          // no prefix: not checked
                .values(Map.of("server.context-path", "/", "db.url", "none", "server.port", "0"))
                .strictEnvironment("APP_")
                .build();

        assertEquals("/exact", config.get("server.context-path"));
        assertEquals("jdbc:x", config.get("db.url"));
        assertEquals("0", config.get("server.port"));
    }

    @Test
    void strictEnvironmentKnowsKeysFromProfiles(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("app.properties"), "server.port=0\n");
        Files.writeString(dir.resolve("app-dev.properties"), "debug.port=5005\n");
        Map<String, String> env = Map.of("APP_DEBUG_PORT", "6006");

        DynamicConfiguration dev = base(env, Map.of())
                .profiles("dev")
                .file(dir.resolve("app.properties"))
                .strictEnvironment("APP_")
                .build();
        assertEquals(6006, dev.getInt("debug.port"));

        DynamicConfiguration.Builder plain = base(env, Map.of())
                .file(dir.resolve("app.properties"))
                .strictEnvironment("APP_");
        ConfigurationException e = assertThrows(ConfigurationException.class, plain::build);
        assertTrue(e.getMessage().contains("APP_DEBUG_PORT"), e.getMessage());
    }

    @Test
    void strictEnvironmentAppliesOnReload() {
        Map<String, String> env = new HashMap<>(Map.of("APP_SERVER_PORT", "9"));
        DynamicConfiguration config = base(env, Map.of())
                .value("server.port", "0")
                .strictEnvironment("APP_")
                .build();
        env.put("APP_SERVER_PROT", "1");
        ConfigurationException e = assertThrows(ConfigurationException.class, config::reload);
        assertTrue(e.getMessage().contains("APP_SERVER_PROT"), e.getMessage());
    }

    @Test
    void strictEnvironmentNeedsAPrefix() {
        DynamicConfiguration.Builder builder = DynamicConfiguration.builder();
        assertThrows(IllegalArgumentException.class, () -> builder.strictEnvironment(""));
    }
}
