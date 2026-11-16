package io.descoped.config;

import io.descoped.config.internal.Layer;
import io.descoped.config.internal.Recipe;

import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * An immutable, thread-safe snapshot of application configuration.
 *
 * <p>Build one with {@link #builder()}. Every value is resolved once, when the configuration is built; reading never
 * touches files, the environment or system properties again. {@link #reload()} re-reads all sources and returns a new
 * snapshot, leaving this one unchanged.
 *
 * <p>Getters without a default throw {@link ConfigurationException} when the key is missing. Every typed getter
 * throws when the value is malformed, also when a default is given: a default covers a <em>missing</em> value, never
 * a wrong one.
 *
 * <p>This interface is sealed: the library's snapshot is the only implementation. Tests build real configurations
 * with {@code builder().values(..)}.
 */
public sealed interface DynamicConfiguration permits Snapshot {

    /**
     * Starts declaring a configuration.
     *
     * @return a new builder
     */
    static Builder builder() {
        return new Builder();
    }

    /**
     * Returns the value of a key.
     *
     * @param key the key
     * @return the value, or empty if no source defines the key
     */
    Optional<String> find(String key);

    /**
     * Returns the value of a key.
     *
     * @param key the key
     * @return the value
     * @throws ConfigurationException if no source defines the key
     */
    String get(String key);

    /**
     * Returns the value of a key, or a default when it is missing.
     *
     * @param key          the key
     * @param defaultValue returned when no source defines the key
     * @return the value or the default
     */
    String get(String key, String defaultValue);

    /**
     * Returns a value converted by the given function, for types without a dedicated getter.
     *
     * @param key       the key
     * @param converter converts the text, for example {@code URI::create}
     * @param <T>       the result type
     * @return the converted value
     * @throws ConfigurationException if the key is missing or the converter fails
     */
    <T> T get(String key, Function<String, ? extends T> converter);

    /**
     * Returns a value converted by the given function, or a default when the key is missing.
     *
     * @param key          the key
     * @param converter    converts the text, for example {@code URI::create}
     * @param defaultValue returned when no source defines the key
     * @param <T>          the result type
     * @return the converted value or the default
     * @throws ConfigurationException if the converter fails
     */
    <T> T get(String key, Function<String, ? extends T> converter, T defaultValue);

    /**
     * Returns an {@code int} value.
     *
     * @param key the key
     * @return the value
     * @throws ConfigurationException if the key is missing or the value is not an {@code int}
     */
    int getInt(String key);

    /**
     * Returns an {@code int} value, or a default when the key is missing.
     *
     * @param key          the key
     * @param defaultValue returned when no source defines the key
     * @return the value or the default
     * @throws ConfigurationException if the value is not an {@code int}
     */
    int getInt(String key, int defaultValue);

    /**
     * Returns a {@code long} value.
     *
     * @param key the key
     * @return the value
     * @throws ConfigurationException if the key is missing or the value is not a {@code long}
     */
    long getLong(String key);

    /**
     * Returns a {@code long} value, or a default when the key is missing.
     *
     * @param key          the key
     * @param defaultValue returned when no source defines the key
     * @return the value or the default
     * @throws ConfigurationException if the value is not a {@code long}
     */
    long getLong(String key, long defaultValue);

    /**
     * Returns a {@code double} value.
     *
     * @param key the key
     * @return the value
     * @throws ConfigurationException if the key is missing or the value is not a {@code double}
     */
    double getDouble(String key);

    /**
     * Returns a {@code double} value, or a default when the key is missing.
     *
     * @param key          the key
     * @param defaultValue returned when no source defines the key
     * @return the value or the default
     * @throws ConfigurationException if the value is not a {@code double}
     */
    double getDouble(String key, double defaultValue);

    /**
     * Returns a {@code boolean} value. Only {@code true} and {@code false} are accepted, in any case.
     *
     * @param key the key
     * @return the value
     * @throws ConfigurationException if the key is missing or the value is neither {@code true} nor {@code false}
     */
    boolean getBoolean(String key);

    /**
     * Returns a {@code boolean} value, or a default when the key is missing.
     *
     * @param key          the key
     * @param defaultValue returned when no source defines the key
     * @return the value or the default
     * @throws ConfigurationException if the value is neither {@code true} nor {@code false}
     */
    boolean getBoolean(String key, boolean defaultValue);

    /**
     * Returns a duration, written either in ISO-8601 ({@code PT30S}) or as a number with one of the units
     * {@code ms}, {@code s}, {@code m}, {@code h} or {@code d} ({@code 500ms}, {@code 30s}, {@code 5m}).
     *
     * @param key the key
     * @return the value
     * @throws ConfigurationException if the key is missing or the value is not a duration
     */
    Duration getDuration(String key);

    /**
     * Returns a duration, or a default when the key is missing. See {@link #getDuration(String)} for the format.
     *
     * @param key          the key
     * @param defaultValue returned when no source defines the key
     * @return the value or the default
     * @throws ConfigurationException if the value is not a duration
     */
    Duration getDuration(String key, Duration defaultValue);

    /**
     * Returns an enum constant, matched by name ignoring case, with {@code -} read as {@code _}.
     *
     * @param key  the key
     * @param type the enum type
     * @param <E>  the enum type
     * @return the constant
     * @throws ConfigurationException if the key is missing or no constant matches
     */
    <E extends Enum<E>> E getEnum(String key, Class<E> type);

    /**
     * Returns an enum constant, or a default when the key is missing.
     *
     * @param key          the key
     * @param type         the enum type
     * @param defaultValue returned when no source defines the key
     * @param <E>          the enum type
     * @return the constant or the default
     * @throws ConfigurationException if no constant matches
     */
    <E extends Enum<E>> E getEnum(String key, Class<E> type, E defaultValue);

    /**
     * Returns a comma-separated list, each element trimmed, empty elements dropped.
     *
     * @param key the key
     * @return an unmodifiable list
     * @throws ConfigurationException if the key is missing
     */
    List<String> getList(String key);

    /**
     * Returns a comma-separated list, or a default when the key is missing.
     *
     * @param key          the key
     * @param defaultValue returned when no source defines the key
     * @return an unmodifiable list, or the default
     */
    List<String> getList(String key, List<String> defaultValue);

    /**
     * Returns the keys under a prefix, with the prefix removed: {@code subset("db")} turns {@code db.url} into
     * {@code url}. Order, provenance and secrecy are kept.
     *
     * @param prefix the prefix, with or without a trailing {@code .}
     * @return a configuration holding the matching keys
     */
    DynamicConfiguration subset(String prefix);

    /**
     * Builds a record from the keys under a prefix. Component {@code idleTimeout} reads key {@code idle-timeout},
     * falling back to {@code idleTimeout}. Supported component types: {@code String}, primitives and their wrappers,
     * {@link Duration}, enums, {@code List<String>}, {@link Path}, {@link java.net.URI}, nested records and
     * {@link Optional} of any of these. Every missing or malformed key is reported in one exception.
     *
     * <p>The record and its canonical constructor must be public. On the module path, the record's package must be
     * exported to this library: {@code exports com.acme.config to io.descoped.dynamic.config;}.
     *
     * @param prefix the prefix of the record's keys; empty for the top level
     * @param type   the record type
     * @param <R>    the record type
     * @return the record
     * @throws ConfigurationException if keys are missing or malformed, or the record cannot be constructed
     */
    <R extends Record> R bind(String prefix, Class<R> type);

    /**
     * Returns all keys and values.
     *
     * @return an unmodifiable map in source order
     */
    Map<String, String> asMap();

    /**
     * Tells where the value of a key came from, for example {@code env SERVICES_PORT} or
     * {@code file /app/local.properties}.
     *
     * @param key the key
     * @return the source, or empty if the key is missing
     */
    Optional<String> sourceOf(String key);

    /**
     * Returns the active profiles, in the order their files were applied.
     *
     * @return an unmodifiable list, empty when no profile is active
     */
    List<String> activeProfiles();

    /**
     * Lists every key with its value and source, one per line. Secret values are masked.
     *
     * @return a human-readable description, safe to log
     */
    String describe();

    /**
     * Reads every source again with the same declarations and returns a new snapshot. This snapshot is not changed.
     *
     * @return a new configuration
     * @throws ConfigurationException if the sources no longer form a valid configuration
     */
    DynamicConfiguration reload();

    /**
     * Declares the sources of a configuration and their order. A source added later wins over one added earlier.
     * A builder is not thread-safe; the configurations it builds are.
     */
    final class Builder {

        private final List<Layer> layers = new ArrayList<>();
        private final Set<String> secretKeys = new LinkedHashSet<>();
        private List<String> profiles;
        private Supplier<Map<String, String>> environment = System::getenv;
        private Supplier<Map<String, String>> systemProperties = Builder::currentSystemProperties;

        Builder() {
        }

        /**
         * Adds a properties resource from the class path, read as UTF-8. Only the first match on the class path is
         * read. The thread context class loader is used, falling back to the library's own loader. A missing
         * resource is skipped.
         *
         * <p>On the module path this finds resources outside packages (such as {@code application.properties} at
         * the root of a jar) and in open packages. For a resource in an encapsulated package of the application's
         * own module, use {@link #resource(URL)}.
         *
         * @param name the resource name, for example {@code application.properties}
         * @return this builder
         */
        public Builder classpath(String name) {
            layers.add(new Layer.Classpath(requireText(name, "name")));
            return this;
        }

        /**
         * Adds a properties resource the application located itself, read as UTF-8. Resource lookup in named
         * modules is caller-sensitive, so this is how a module hands over a resource from one of its own
         * encapsulated packages: {@code resource(App.class.getResource("app.properties"))}. A {@code null} URL is
         * treated as a missing resource and skipped.
         *
         * @param url the resource, or {@code null}
         * @return this builder
         */
        public Builder resource(URL url) {
            layers.add(new Layer.Resource(url));
            return this;
        }

        /**
         * Adds a properties file, read as UTF-8. A relative path is resolved against the working directory. A
         * missing file is skipped.
         *
         * @param path the file
         * @return this builder
         */
        public Builder file(Path path) {
            layers.add(new Layer.PropertiesFile(Objects.requireNonNull(path, "path"), false));
            return this;
        }

        /**
         * Adds a properties file that must exist; {@link #build()} fails if it does not.
         *
         * @param path the file
         * @return this builder
         */
        public Builder requiredFile(Path path) {
            layers.add(new Layer.PropertiesFile(Objects.requireNonNull(path, "path"), true));
            return this;
        }

        /**
         * Adds a directory of secret files, as mounted by Kubernetes secret volumes and Docker secrets: each regular,
         * non-hidden file is one key (its name) and its content is the value, with one trailing line break removed.
         * Every value from this source is secret. A missing directory is skipped.
         *
         * @param path the directory
         * @return this builder
         */
        public Builder directory(Path path) {
            layers.add(new Layer.Directory(Objects.requireNonNull(path, "path")));
            return this;
        }

        /**
         * Lets environment variables override keys that other sources define. Environment variables never add keys.
         * For key {@code k} the first variable that exists is used of: {@code k}; {@code k} with every
         * non-alphanumeric character replaced by {@code _}; the same in upper case. So
         * {@code server.context-path} is read from {@code SERVER_CONTEXT_PATH}.
         *
         * @return this builder
         */
        public Builder environment() {
            return environment("");
        }

        /**
         * Like {@link #environment()}, with a prefix in front of every variable name: with prefix {@code MYAPP_},
         * {@code server.port} is read from {@code MYAPP_SERVER_PORT}.
         *
         * @param prefix the variable name prefix
         * @return this builder
         */
        public Builder environment(String prefix) {
            layers.add(new Layer.Environment(Objects.requireNonNull(prefix, "prefix")));
            return this;
        }

        /**
         * Lets system properties with the same name override keys that other sources define. System properties
         * never add keys.
         *
         * @return this builder
         */
        public Builder systemProperties() {
            return systemProperties("");
        }

        /**
         * Like {@link #systemProperties()}, with a prefix: with prefix {@code myapp.}, {@code server.port} is read
         * from system property {@code myapp.server.port}.
         *
         * @param prefix the property name prefix
         * @return this builder
         */
        public Builder systemProperties(String prefix) {
            layers.add(new Layer.SystemProperties(Objects.requireNonNull(prefix, "prefix")));
            return this;
        }

        /**
         * Adds values given by the application. The map is copied.
         *
         * @param values the keys and values; {@code null} keys or values are rejected
         * @return this builder
         * @throws NullPointerException if a key or value is {@code null}
         */
        public Builder values(Map<String, String> values) {
            Objects.requireNonNull(values, "values");
            Map<String, String> copy = new LinkedHashMap<>();
            values.forEach((key, value) -> {
                Objects.requireNonNull(key, "values contains a null key");
                Objects.requireNonNull(value, () -> "values contains a null value for key '" + key + "'");
                copy.put(key, value);
            });
            layers.add(new Layer.Values(copy));
            return this;
        }

        /**
         * Adds one value given by the application.
         *
         * @param key   the key
         * @param value the value
         * @return this builder
         */
        public Builder value(String key, String value) {
            Map<String, String> one = new LinkedHashMap<>();
            one.put(Objects.requireNonNull(key, "key"),
                    Objects.requireNonNull(value, () -> "null value for key '" + key + "'"));
            layers.add(new Layer.Values(one));
            return this;
        }

        /**
         * Adds a source supplied by the application.
         *
         * @param source the source
         * @return this builder
         */
        public Builder source(ConfigurationSource source) {
            layers.add(new Layer.Custom(Objects.requireNonNull(source, "source")));
            return this;
        }

        /**
         * Sets the active profiles. For every class path resource, URL resource and file, a sibling per profile is
         * read right after it, in profile order: {@code application.properties} is followed by
         * {@code application-test.properties}. Missing profile files are skipped.
         *
         * <p>Without this call, the profiles come from system property {@code config.profiles}, else from
         * environment variable {@code CONFIG_PROFILES} (both comma-separated), else none are active.
         *
         * @param names the profile names; none means no profile, ignoring the system property and environment
         * @return this builder
         */
        public Builder profiles(String... names) {
            List<String> list = new ArrayList<>();
            for (String name : Objects.requireNonNull(names, "names")) {
                list.add(requireText(name, "profile"));
            }
            this.profiles = List.copyOf(list);
            return this;
        }

        /**
         * Marks keys as secret, in addition to those detected automatically: values from {@link #directory(Path)},
         * keys whose last segment contains {@code password}, {@code passwd}, {@code secret}, {@code token},
         * {@code credential}, {@code private-key} or {@code api-key}, and values that reference a secret.
         *
         * @param keys the keys
         * @return this builder
         */
        public Builder secret(String... keys) {
            secretKeys.addAll(Arrays.asList(Objects.requireNonNull(keys, "keys")));
            return this;
        }

        /**
         * Reads every source and returns the resolved configuration.
         *
         * @return an immutable configuration
         * @throws ConfigurationException if a required file is missing, a source cannot be read, an environment
         *                                variable matches two keys, or a reference is missing or cyclic
         */
        public DynamicConfiguration build() {
            return Snapshot.load(new Recipe(List.copyOf(layers), profiles, Set.copyOf(secretKeys),
                    environment, systemProperties));
        }

        // Test seams: the environment cannot be changed in-process.
        Builder environmentProvider(Supplier<Map<String, String>> provider) {
            this.environment = Objects.requireNonNull(provider);
            return this;
        }

        Builder systemPropertiesProvider(Supplier<Map<String, String>> provider) {
            this.systemProperties = Objects.requireNonNull(provider);
            return this;
        }

        private static Map<String, String> currentSystemProperties() {
            Properties properties = System.getProperties();
            Map<String, String> map = new LinkedHashMap<>();
            for (String name : properties.stringPropertyNames()) {
                map.put(name, properties.getProperty(name));
            }
            return map;
        }

        private static String requireText(String value, String what) {
            Objects.requireNonNull(value, what);
            if (value.isBlank()) {
                throw new IllegalArgumentException(what + " must not be blank");
            }
            return value;
        }
    }
}
