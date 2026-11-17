package io.descoped.config;

import org.junit.jupiter.api.Test;

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
}
