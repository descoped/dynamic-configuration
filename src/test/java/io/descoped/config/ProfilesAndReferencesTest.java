package io.descoped.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfilesAndReferencesTest {

    @TempDir
    Path dir;

    // ---- profiles ---------------------------------------------------------------------------------------------

    @Test
    void explicitProfileAddsClasspathSibling() {
        DynamicConfiguration config = DynamicConfiguration.builder()
                .classpath("application.properties")
                .profiles("test")
                .build();

        assertEquals(9090, config.getInt("server.port"));
        assertEquals("test", config.get("app.mode"));
        assertEquals("localhost", config.get("server.host"));
        assertEquals(List.of("test"), config.activeProfiles());
    }

    @Test
    void profilesApplyToFilesInOrder() throws IOException {
        Files.writeString(dir.resolve("app.properties"), "k=base\nonly.base=1\n");
        Files.writeString(dir.resolve("app-dev.properties"), "k=dev\n");
        Files.writeString(dir.resolve("app-local.properties"), "k=local\n");

        DynamicConfiguration config = DynamicConfiguration.builder()
                .file(dir.resolve("app.properties"))
                .profiles("dev", "local", "absent")
                .build();

        assertEquals("local", config.get("k"));
        assertEquals("1", config.get("only.base"));
    }

    @Test
    void profilesComeFromSystemPropertyThenEnvironment() {
        DynamicConfiguration fromProperty = DynamicConfiguration.builder()
                .systemPropertiesProvider(() -> Map.of("config.profiles", "test, extra"))
                .environmentProvider(() -> Map.of("CONFIG_PROFILES", "ignored"))
                .classpath("application.properties")
                .build();
        assertEquals(List.of("test", "extra"), fromProperty.activeProfiles());
        assertEquals("test", fromProperty.get("app.mode"));

        DynamicConfiguration fromEnv = DynamicConfiguration.builder()
                .systemPropertiesProvider(Map::of)
                .environmentProvider(() -> Map.of("CONFIG_PROFILES", "test"))
                .classpath("application.properties")
                .build();
        assertEquals(List.of("test"), fromEnv.activeProfiles());

        DynamicConfiguration explicitNone = DynamicConfiguration.builder()
                .systemPropertiesProvider(() -> Map.of("config.profiles", "test"))
                .classpath("application.properties")
                .profiles()
                .build();
        assertTrue(explicitNone.activeProfiles().isEmpty());
        assertFalse(explicitNone.find("app.mode").isPresent());
    }

    @Test
    void profileOfResourceUrl() {
        DynamicConfiguration config = DynamicConfiguration.builder()
                .resource(ProfilesAndReferencesTest.class.getResource("/application.properties"))
                .profiles("test")
                .build();

        assertEquals("test", config.get("app.mode"));
    }

    // ---- references -------------------------------------------------------------------------------------------

    @Test
    void referencesResolveAcrossSources() {
        DynamicConfiguration config = DynamicConfiguration.builder()
                .classpath("application.properties")
                .profiles()
                .value("url", "http://${server.host}:${server.port}/${path:api}")
                .build();

        assertEquals("http://localhost:8080/api", config.get("url"));
        assertEquals("Hello Base æøå", config.get("app.greeting"));
    }

    @Test
    void referencesSeeTheFinalLayeredValue() {
        DynamicConfiguration config = DynamicConfiguration.builder()
                .values(Map.of("host", "a", "url", "http://${host}"))
                .value("host", "b")
                .build();

        assertEquals("http://b", config.get("url"));
    }

    @Test
    void nestedDefaultsAndEscape() {
        DynamicConfiguration config = DynamicConfiguration.builder()
                .values(Map.of(
                        "fallback", "fb",
                        "a", "${missing:${fallback}}",
                        "b", "${missing:${also.missing:last}}",
                        "c", "literal $${not.a.ref} and ${fallback}",
                        "d", "${missing:}",
                        "e", "cost: $5"))
                .build();

        assertEquals("fb", config.get("a"));
        assertEquals("last", config.get("b"));
        assertEquals("literal ${not.a.ref} and fb", config.get("c"));
        assertEquals("", config.get("d"));
        assertEquals("cost: $5", config.get("e"));
    }

    @Test
    void missingReferencesAreAllReported() {
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> DynamicConfiguration.builder()
                .values(Map.of("a", "${x}", "b", "${y}"))
                .build());

        assertTrue(e.getMessage().contains("'a' from values references missing key 'x'"), e.getMessage());
        assertTrue(e.getMessage().contains("'b' from values references missing key 'y'"), e.getMessage());
    }

    @Test
    void cyclesFail() {
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> DynamicConfiguration.builder()
                .values(Map.of("a", "${b}", "b", "${c}", "c", "${a}"))
                .build());

        assertTrue(e.getMessage().contains("reference cycle"), e.getMessage());
        assertTrue(e.getMessage().contains("a from values"), e.getMessage());
    }

    @Test
    void unterminatedReferenceFails() {
        ConfigurationException e = assertThrows(ConfigurationException.class,
                () -> DynamicConfiguration.builder().value("a", "${b").build());
        assertTrue(e.getMessage().contains("'a' from values has an unterminated reference"), e.getMessage());
    }

    @Test
    void secretSourcesAreTakenLiterally() throws IOException {
        Path secrets = Files.createDirectories(dir.resolve("secrets"));
        Files.writeString(secrets.resolve("db.password"), "p${w}d");
        ConfigurationSource vault = new ConfigurationSource() {
            @Override
            public String name() {
                return "vault";
            }

            @Override
            public Map<String, String> load() {
                return Map.of("api.key", "k${");
            }

            @Override
            public boolean secret() {
                return true;
            }
        };

        DynamicConfiguration config = DynamicConfiguration.builder()
                .directory(secrets)
                .source(vault)
                .value("dsn", "user:${db.password}")
                .build();

        assertEquals("p${w}d", config.get("db.password"));
        assertEquals("k${", config.get("api.key"));
        assertEquals("user:p${w}d", config.get("dsn"), "a reference to a literal value inserts it as is");
    }

    @Test
    void referencingASecretMakesTheValueSecret() {
        DynamicConfiguration config = DynamicConfiguration.builder()
                .values(Map.of("db.password", "pw", "db.url", "jdbc://user:${db.password}@host"))
                .build();

        assertEquals("jdbc://user:pw@host", config.get("db.url"));
        assertFalse(config.describe().contains("pw@host"));
        assertTrue(config.describe().contains("db.url = ****"));
    }
}
