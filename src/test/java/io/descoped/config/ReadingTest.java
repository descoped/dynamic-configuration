package io.descoped.config;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadingTest {

    enum Mode {READ_ONLY, READ_WRITE}

    private final DynamicConfiguration config = DynamicConfiguration.builder()
            .values(Map.of(
                    "int", " 42 ",
                    "long", "9000000000",
                    "double", "1.5",
                    "yes", "TRUE",
                    "bad.bool", "yes",
                    "iso", "PT30S",
                    "short", "500ms",
                    "mode", "read-only",
                    "list", " a, b ,,c ",
                    "uri", "https://descoped.io"))
            .build();

    @Test
    void stringsAndOptional() {
        assertEquals("1.5", config.get("double"));
        assertEquals("x", config.get("missing", "x"));
        assertTrue(config.find("missing").isEmpty());
    }

    @Test
    void typedValues() {
        assertEquals(42, config.getInt("int"));
        assertEquals(9_000_000_000L, config.getLong("long"));
        assertEquals(1.5, config.getDouble("double"));
        assertTrue(config.getBoolean("yes"));
        assertEquals(Duration.ofSeconds(30), config.getDuration("iso"));
        assertEquals(Duration.ofMillis(500), config.getDuration("short"));
        assertEquals(Mode.READ_ONLY, config.getEnum("mode", Mode.class));
        assertEquals(List.of("a", "b", "c"), config.getList("list"));
        assertEquals(URI.create("https://descoped.io"), config.get("uri", URI::create));
    }

    @Test
    void durationUnits() {
        DynamicConfiguration d = DynamicConfiguration.builder()
                .values(Map.of("s", "30s", "m", "5m", "h", "2h", "d", "1d", "neg", "-PT1S"))
                .build();
        assertEquals(Duration.ofSeconds(30), d.getDuration("s"));
        assertEquals(Duration.ofMinutes(5), d.getDuration("m"));
        assertEquals(Duration.ofHours(2), d.getDuration("h"));
        assertEquals(Duration.ofDays(1), d.getDuration("d"));
        assertEquals(Duration.ofSeconds(-1), d.getDuration("neg"));
    }

    @Test
    void defaultsCoverMissingOnly() {
        assertEquals(7, config.getInt("missing", 7));
        assertEquals(7L, config.getLong("missing", 7L));
        assertEquals(0.5, config.getDouble("missing", 0.5));
        assertTrue(config.getBoolean("missing", true));
        assertEquals(Duration.ZERO, config.getDuration("missing", Duration.ZERO));
        assertEquals(Mode.READ_WRITE, config.getEnum("missing", Mode.class, Mode.READ_WRITE));
        assertEquals(List.of("z"), config.getList("missing", List.of("z")));
        assertEquals(URI.create("x:y"), config.get("missing", URI::create, URI.create("x:y")));

        assertThrows(ConfigurationException.class, () -> config.getBoolean("bad.bool", true));
        assertThrows(ConfigurationException.class, () -> config.getInt("double", 1));
    }

    @Test
    void errorsNameKeyAndSource() {
        ConfigurationException missing = assertThrows(ConfigurationException.class, () -> config.getInt("nope"));
        assertTrue(missing.getMessage().contains("'nope'"), missing.getMessage());
        assertTrue(missing.getMessage().contains("looked in: values"), missing.getMessage());

        ConfigurationException malformed = assertThrows(ConfigurationException.class, () -> config.getBoolean("bad.bool"));
        assertTrue(malformed.getMessage().contains("'bad.bool' from values is not a valid boolean: 'yes'"), malformed.getMessage());

        ConfigurationException converter = assertThrows(ConfigurationException.class,
                () -> config.get("mode", Integer::valueOf));
        assertTrue(converter.getMessage().contains("'mode'"));
        assertThrows(ConfigurationException.class, () -> config.getEnum("double", Mode.class));
        assertThrows(ConfigurationException.class, () -> config.getDuration("int"));
    }

    @Test
    void malformedSecretIsNotShownInMessageOrCause() {
        DynamicConfiguration secret = DynamicConfiguration.builder().value("api.token", "not-a-number").build();
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> secret.getInt("api.token"));
        assertFalse(stackTrace(e).contains("not-a-number"), stackTrace(e));
        assertNull(e.getCause(), "a parser's exception quotes its input, so a secret gets no cause");
        assertTrue(e.getMessage().contains("NumberFormatException"), e.getMessage());

        ConfigurationException uri = assertThrows(ConfigurationException.class,
                () -> secret.get("api.token", text -> URI.create("bad uri " + text)));
        assertFalse(stackTrace(uri).contains("not-a-number"));
    }

    @Test
    void malformedPlainValueKeepsItsCause() {
        ConfigurationException e = assertThrows(ConfigurationException.class, () -> config.getInt("double"));
        assertInstanceOf(NumberFormatException.class, e.getCause());
    }

    static String stackTrace(Throwable t) {
        StringWriter out = new StringWriter();
        t.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    @Test
    void subsetStripsPrefixAndKeepsProvenance() {
        DynamicConfiguration all = DynamicConfiguration.builder()
                .values(Map.of("db.url", "u", "db.pool.size", "4", "dbx", "no", "other", "no"))
                .build();
        DynamicConfiguration db = all.subset("db.");

        assertEquals(Map.of("url", "u", "pool.size", "4"), db.asMap());
        assertEquals(4, db.subset("pool").getInt("size"));
        assertEquals("values", db.sourceOf("url").orElseThrow());
        assertSame(all, all.subset(""));
    }

    @Test
    void reloadReturnsANewSnapshot() {
        List<String> versions = new ArrayList<>(List.of("1", "2", "3"));
        DynamicConfiguration first = DynamicConfiguration.builder()
                .source(new ConfigurationSource() {
                    @Override
                    public String name() {
                        return "counter";
                    }

                    @Override
                    public Map<String, String> load() {
                        return Map.of("version", versions.remove(0), "nested.v", "n");
                    }
                })
                .build();

        DynamicConfiguration second = first.reload();
        assertEquals("1", first.get("version"));
        assertEquals("2", second.get("version"));
        assertNotSame(first, second);
        assertNotEquals(first, second);

        DynamicConfiguration nested = first.subset("nested").reload();
        assertEquals(Map.of("v", "n"), nested.asMap());
        assertTrue(versions.isEmpty(), "reloading a subset re-reads the sources");
    }

    @Test
    void equalityIsByResolvedValues() {
        DynamicConfiguration a = DynamicConfiguration.builder().value("k", "v").build();
        DynamicConfiguration b = DynamicConfiguration.builder().values(Map.of("k", "v")).build();
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void concurrentReadsSeeTheSameSnapshot() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Integer>> readers = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                readers.add(() -> config.getInt("int") + config.getList("list").size());
            }
            for (Future<Integer> result : pool.invokeAll(readers)) {
                assertEquals(45, result.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
