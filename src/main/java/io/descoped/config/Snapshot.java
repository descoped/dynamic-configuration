package io.descoped.config;

import io.descoped.config.internal.Parsers;
import io.descoped.config.internal.Recipe;
import io.descoped.config.internal.Resolution;
import io.descoped.config.internal.Resolver;
import io.descoped.config.internal.Value;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** The one implementation of {@link DynamicConfiguration}: resolved values, frozen. */
final class Snapshot implements DynamicConfiguration {

    private final Map<String, Value> values;
    private final Map<String, String> plain;
    private final List<String> profiles;
    private final List<String> sources;
    private final Supplier<DynamicConfiguration> reloader;

    private Snapshot(Map<String, Value> values, List<String> profiles, List<String> sources,
                     Supplier<DynamicConfiguration> reloader) {
        this.values = values;
        Map<String, String> texts = new LinkedHashMap<>();
        values.forEach((key, value) -> texts.put(key, value.text()));
        this.plain = Collections.unmodifiableMap(texts);
        this.profiles = List.copyOf(profiles);
        this.sources = List.copyOf(sources);
        this.reloader = reloader;
    }

    static Snapshot load(Recipe recipe) {
        Resolution resolution = Resolver.resolve(recipe);
        return new Snapshot(resolution.values(), resolution.profiles(), resolution.sources(), () -> load(recipe));
    }

    // ---- strings ------------------------------------------------------------------------------------------------

    @Override
    public Optional<String> find(String key) {
        return Optional.ofNullable(plain.get(Objects.requireNonNull(key, "key")));
    }

    @Override
    public String get(String key) {
        return required(key).text();
    }

    @Override
    public String get(String key, String defaultValue) {
        return find(key).orElse(defaultValue);
    }

    @Override
    public <T> T get(String key, Function<String, ? extends T> converter) {
        return convert(required(key), key, "value", converter);
    }

    @Override
    public <T> T get(String key, Function<String, ? extends T> converter, T defaultValue) {
        Value value = values.get(Objects.requireNonNull(key, "key"));
        return value == null ? defaultValue : convert(value, key, "value", converter);
    }

    // ---- typed --------------------------------------------------------------------------------------------------

    @Override
    public int getInt(String key) {
        return get(key, "int", text -> Integer.parseInt(text.trim()));
    }

    @Override
    public int getInt(String key, int defaultValue) {
        return orDefault(key, "int", text -> Integer.parseInt(text.trim()), defaultValue);
    }

    @Override
    public long getLong(String key) {
        return get(key, "long", text -> Long.parseLong(text.trim()));
    }

    @Override
    public long getLong(String key, long defaultValue) {
        return orDefault(key, "long", text -> Long.parseLong(text.trim()), defaultValue);
    }

    @Override
    public double getDouble(String key) {
        return get(key, "double", text -> Double.parseDouble(text.trim()));
    }

    @Override
    public double getDouble(String key, double defaultValue) {
        return orDefault(key, "double", text -> Double.parseDouble(text.trim()), defaultValue);
    }

    @Override
    public boolean getBoolean(String key) {
        return get(key, "boolean", Parsers::parseBoolean);
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        return orDefault(key, "boolean", Parsers::parseBoolean, defaultValue);
    }

    @Override
    public Duration getDuration(String key) {
        return get(key, "duration", Parsers::parseDuration);
    }

    @Override
    public Duration getDuration(String key, Duration defaultValue) {
        return orDefault(key, "duration", Parsers::parseDuration, defaultValue);
    }

    @Override
    public <E extends Enum<E>> E getEnum(String key, Class<E> type) {
        Objects.requireNonNull(type, "type");
        return get(key, type.getSimpleName(), text -> Parsers.parseEnum(text, type));
    }

    @Override
    public <E extends Enum<E>> E getEnum(String key, Class<E> type, E defaultValue) {
        Objects.requireNonNull(type, "type");
        return orDefault(key, type.getSimpleName(), text -> Parsers.parseEnum(text, type), defaultValue);
    }

    @Override
    public List<String> getList(String key) {
        return Parsers.parseList(get(key));
    }

    @Override
    public List<String> getList(String key, List<String> defaultValue) {
        return find(key).map(Parsers::parseList).orElse(defaultValue);
    }

    private <T> T get(String key, String type, Function<String, T> parser) {
        return convert(required(key), key, type, parser);
    }

    private <T> T orDefault(String key, String type, Function<String, T> parser, T defaultValue) {
        Value value = values.get(Objects.requireNonNull(key, "key"));
        return value == null ? defaultValue : convert(value, key, type, parser);
    }

    private Value required(String key) {
        Value value = values.get(Objects.requireNonNull(key, "key"));
        if (value == null) {
            throw new ConfigurationException("Missing configuration key '" + key + "' (looked in: "
                    + (sources.isEmpty() ? "no sources" : String.join(", ", sources)) + ")");
        }
        return value;
    }

    private static <T> T convert(Value value, String key, String type, Function<String, ? extends T> parser) {
        try {
            return parser.apply(value.text());
        } catch (RuntimeException e) {
            throw new ConfigurationException("Configuration key '" + key + "' from " + value.source()
                    + " is not a valid " + type + ": '" + value.display() + "'"
                    + (e.getMessage() == null || value.secret() ? "" : " (" + e.getMessage() + ")"), e);
        }
    }

    // ---- structure ----------------------------------------------------------------------------------------------

    @Override
    public DynamicConfiguration subset(String prefix) {
        String normalized = normalizePrefix(prefix);
        if (normalized.isEmpty()) {
            return this;
        }
        String start = normalized + ".";
        Map<String, Value> selected = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key.startsWith(start) && key.length() > start.length()) {
                selected.put(key.substring(start.length()), value);
            }
        });
        return new Snapshot(Collections.unmodifiableMap(selected), profiles, sources,
                () -> reloader.get().subset(normalized));
    }

    @Override
    public <R extends Record> R bind(String prefix, Class<R> type) {
        return RecordBinder.bind(this, normalizePrefix(prefix), type);
    }

    static String normalizePrefix(String prefix) {
        String p = Objects.requireNonNull(prefix, "prefix");
        while (p.endsWith(".")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    /** The resolved value of a key, with provenance; {@code null} if missing. For {@link RecordBinder}. */
    Value value(String key) {
        return values.get(key);
    }

    boolean hasKeysUnder(String prefix) {
        String start = prefix.isEmpty() ? "" : prefix + ".";
        return values.keySet().stream().anyMatch(key -> key.startsWith(start));
    }

    // ---- diagnostics --------------------------------------------------------------------------------------------

    @Override
    public Map<String, String> asMap() {
        return plain;
    }

    @Override
    public Optional<String> sourceOf(String key) {
        return Optional.ofNullable(values.get(Objects.requireNonNull(key, "key"))).map(Value::source);
    }

    @Override
    public List<String> activeProfiles() {
        return profiles;
    }

    @Override
    public String describe() {
        StringBuilder out = new StringBuilder();
        values.forEach((key, value) -> out.append(key).append(" = ").append(value.display())
                .append("  [").append(value.source()).append("]\n"));
        return out.toString();
    }

    @Override
    public DynamicConfiguration reload() {
        return reloader.get();
    }

    @Override
    public boolean equals(Object o) {
        return this == o || o instanceof Snapshot other && plain.equals(other.plain);
    }

    @Override
    public int hashCode() {
        return plain.hashCode();
    }

    @Override
    public String toString() {
        return describe();
    }
}
