package io.descoped.config;

import io.descoped.config.internal.Secrets;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecretsTest {

    @Test
    void recognisesSecretKeyNames() {
        for (String key : new String[]{"db.password", "DB_PASSWD", "oauth.client-secret", "github.token",
                "aws.credentials", "tls.private-key", "service.apiKey", "x.api_key"}) {
            assertTrue(Secrets.looksSecret(key), key);
        }
        for (String key : new String[]{"server.port", "password.policy.min-length", "tokenizer.enabled.x"}) {
            assertFalse(Secrets.looksSecret(key), key);
        }
    }

    @Test
    void describeAndToStringMaskSecrets() {
        DynamicConfiguration config = DynamicConfiguration.builder()
                .values(Map.of("db.password", "hunter2", "db.user", "app", "plain.but.secret", "x9"))
                .secret("plain.but.secret")
                .build();

        String described = config.describe();
        assertTrue(described.contains("db.password = ****  [values]"), described);
        assertTrue(described.contains("db.user = app  [values]"), described);
        assertFalse(described.contains("hunter2"));
        assertFalse(described.contains("x9"));
        assertEquals(described, config.toString());
        assertEquals("hunter2", config.get("db.password"), "masking never changes the value itself");
    }

    @Test
    void secretValuesNeverReachTheLog() throws IOException {
        String marker = "Never-Logged-" + System.nanoTime();
        DynamicConfiguration.builder()
                .values(Map.of("db.password", marker, "plain", marker))
                .environmentProvider(() -> Map.of("PLAIN", marker))
                .environment()
                .classpath("application.properties")
                .build()
                .reload();

        Path log = Path.of(System.getProperty("org.slf4j.simpleLogger.logFile", "target/test-slf4j.log"));
        assertTrue(Files.exists(log), "slf4j-simple writes to " + log);
        String content = Files.readString(log);
        assertTrue(content.contains("Loaded"), "debug logging is active");
        assertFalse(content.contains(marker), "no configuration value is ever logged");
    }
}
