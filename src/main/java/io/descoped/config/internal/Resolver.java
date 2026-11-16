package io.descoped.config.internal;

import io.descoped.config.ConfigurationException;
import io.descoped.config.ConfigurationSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Turns a {@link Recipe} into resolved values: loads the sources, applies overrides, profiles and references. */
public final class Resolver {

    private static final Logger LOG = LoggerFactory.getLogger(Resolver.class);

    static final String PROFILES_PROPERTY = "config.profiles";
    static final String PROFILES_VARIABLE = "CONFIG_PROFILES";

    private final Recipe recipe;
    private final Map<String, String> environment;
    private final Map<String, String> systemProperties;

    private Resolver(Recipe recipe) {
        this.recipe = recipe;
        this.environment = Map.copyOf(recipe.environment().get());
        this.systemProperties = Map.copyOf(recipe.systemProperties().get());
    }

    public static Resolution resolve(Recipe recipe) {
        return new Resolver(recipe).resolve();
    }

    private Resolution resolve() {
        List<String> profiles = activeProfiles();
        if (!profiles.isEmpty()) {
            LOG.debug("Active profiles: {}", profiles);
        }

        // Load every base layer first: override layers need the full set of known keys.
        List<Map<String, Value>> loaded = new ArrayList<>();
        Set<String> knownKeys = new LinkedHashSet<>();
        for (Layer layer : recipe.layers()) {
            if (layer instanceof Layer.Overriding) {
                loaded.add(null);
            } else {
                Map<String, Value> values = load(layer, profiles);
                knownKeys.addAll(values.keySet());
                loaded.add(values);
            }
        }

        List<String> problems = new ArrayList<>();
        Map<String, Value> merged = new LinkedHashMap<>();
        for (int i = 0; i < recipe.layers().size(); i++) {
            Layer layer = recipe.layers().get(i);
            Map<String, Value> values = loaded.get(i) != null ? loaded.get(i) : override((Layer.Overriding) layer, knownKeys, problems);
            // A key that was secret in any layer stays secret when a later layer overrides it.
            values.forEach((key, value) -> merged.merge(key, value,
                    (previous, next) -> previous.secret() ? next.asSecret() : next));
        }
        if (!problems.isEmpty()) {
            throw new ConfigurationException(String.join("; ", problems));
        }

        merged.replaceAll((key, value) ->
                recipe.secretKeys().contains(key) || Secrets.looksSecret(key) ? value.asSecret() : value);

        Interpolator.Result result = Interpolator.resolve(merged);
        if (!result.errors().isEmpty()) {
            throw new ConfigurationException("Invalid configuration references: " + String.join("; ", result.errors()));
        }

        List<String> sources = recipe.layers().stream().map(Layer::describe).toList();
        return new Resolution(Collections.unmodifiableMap(result.values()), profiles, sources);
    }

    private List<String> activeProfiles() {
        if (recipe.profiles() != null) {
            return recipe.profiles();
        }
        String declared = systemProperties.get(PROFILES_PROPERTY);
        if (declared == null) {
            declared = environment.get(PROFILES_VARIABLE);
        }
        return declared == null ? List.of() : Parsers.parseList(declared);
    }

    // ---- base layers ------------------------------------------------------------------------------------------

    private Map<String, Value> load(Layer layer, List<String> profiles) {
        Map<String, Value> values = new LinkedHashMap<>();
        if (layer instanceof Layer.Classpath classpath) {
            values.putAll(loadClasspath(classpath.name()));
            for (String profile : profiles) {
                values.putAll(loadClasspath(profileName(classpath.name(), profile)));
            }
        } else if (layer instanceof Layer.Resource resource) {
            values.putAll(loadUrl(resource.url(), "resource "));
            for (String profile : profiles) {
                values.putAll(loadUrl(profileUrl(resource.url(), profile), "resource "));
            }
        } else if (layer instanceof Layer.PropertiesFile file) {
            values.putAll(loadFile(file.path(), file.required()));
            for (String profile : profiles) {
                Path sibling = file.path().resolveSibling(profileName(file.path().getFileName().toString(), profile));
                values.putAll(loadFile(sibling, false));
            }
        } else if (layer instanceof Layer.Directory directory) {
            values.putAll(loadDirectory(directory.path()));
        } else if (layer instanceof Layer.Values given) {
            given.values().forEach((key, text) -> values.put(key, new Value(text, "values", false)));
        } else if (layer instanceof Layer.Custom custom) {
            values.putAll(loadCustom(custom.source()));
        } else {
            throw new IllegalStateException("Unexpected layer " + layer);
        }
        return values;
    }

    private Map<String, Value> loadClasspath(String name) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = Resolver.class.getClassLoader();
        }
        List<URL> matches = new ArrayList<>();
        try {
            Enumeration<URL> found = loader.getResources(name);
            while (found.hasMoreElements()) {
                matches.add(found.nextElement());
            }
        } catch (IOException e) {
            throw new ConfigurationException("Cannot look up classpath resource " + name, e);
        }
        if (matches.isEmpty()) {
            LOG.debug("Optional source not found: classpath {}", name);
            return Map.of();
        }
        if (matches.size() > 1) {
            LOG.debug("Classpath {}: using {}, ignoring {} later match(es)", name, matches.get(0), matches.size() - 1);
        }
        return loadUrl(matches.get(0), "classpath ");
    }

    private static Map<String, Value> loadUrl(URL url, String kind) {
        if (url == null) {
            LOG.debug("Optional source not found: {}null URL", kind);
            return Map.of();
        }
        String source = kind + url;
        try (InputStream in = url.openStream()) {
            return withSource(PropertiesReader.read(in), source, false);
        } catch (java.io.FileNotFoundException | NoSuchFileException e) {
            LOG.debug("Optional source not found: {}", source);
            return Map.of();
        } catch (IOException | IllegalArgumentException e) {
            throw cannotRead(source, e);
        }
    }

    private static Map<String, Value> loadFile(Path path, boolean required) {
        Path absolute = path.toAbsolutePath().normalize();
        String source = "file " + absolute;
        if (!Files.isRegularFile(absolute)) {
            if (required) {
                throw new ConfigurationException("Required configuration file not found: " + absolute);
            }
            LOG.debug("Optional source not found: {}", source);
            return Map.of();
        }
        try (InputStream in = Files.newInputStream(absolute)) {
            return withSource(PropertiesReader.read(in), source, false);
        } catch (IOException | IllegalArgumentException e) {
            throw cannotRead(source, e);
        }
    }

    private static Map<String, Value> loadDirectory(Path path) {
        Path directory = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            LOG.debug("Optional source not found: secret directory {}", directory);
            return Map.of();
        }
        Map<String, Value> values = new LinkedHashMap<>();
        List<Path> files;
        try (Stream<Path> entries = Files.list(directory)) {
            files = entries.sorted().toList();
        } catch (IOException e) {
            throw cannotRead("secret directory " + directory, e);
        }
        for (Path file : files) {
            String key = file.getFileName().toString();
            if (key.startsWith(".") || !Files.isRegularFile(file)) {
                continue; // hidden files, and Kubernetes' ..data / ..<timestamp> directories
            }
            String source = "secret file " + directory.resolve(key);
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                values.put(key, new Value(stripOneLineBreak(text), source, true, true));
            } catch (IOException e) {
                throw cannotRead(source, e);
            }
        }
        LOG.debug("Loaded {} key(s) from secret directory {}", values.size(), directory);
        return values;
    }

    private static Map<String, Value> loadCustom(ConfigurationSource source) {
        String name = source.name();
        Map<String, String> loaded = source.load();
        if (loaded == null) {
            throw new ConfigurationException("Source '" + name + "' returned null");
        }
        Map<String, Value> values = new LinkedHashMap<>();
        loaded.forEach((key, text) -> {
            if (key == null || text == null) {
                throw new ConfigurationException("Source '" + name + "' returned a null key or a null value for key '" + key + "'");
            }
            values.put(key, new Value(text, name, source.secret(), source.secret()));
        });
        LOG.debug("Loaded {} key(s) from {}", values.size(), name);
        return values;
    }

    /**
     * A failure to read a source, naming it. The cause is kept for I/O errors only: a parse error's message can quote
     * the content, which may be a secret.
     */
    private static ConfigurationException cannotRead(String source, Exception e) {
        if (e instanceof java.nio.charset.CharacterCodingException) {
            return new ConfigurationException("Cannot read " + source + ": not valid UTF-8");
        }
        if (e instanceof IllegalArgumentException) {
            return new ConfigurationException("Cannot read " + source + ": malformed properties (" + e.getMessage() + ")");
        }
        return new ConfigurationException("Cannot read " + source, e);
    }

    private static Map<String, Value> withSource(Map<String, String> entries, String source, boolean secret) {
        Map<String, Value> values = new LinkedHashMap<>();
        entries.forEach((key, text) -> values.put(key, new Value(text, source, secret)));
        LOG.debug("Loaded {} key(s) from {}", values.size(), source);
        return values;
    }

    static String stripOneLineBreak(String text) {
        if (text.endsWith("\r\n")) {
            return text.substring(0, text.length() - 2);
        }
        if (text.endsWith("\n")) {
            return text.substring(0, text.length() - 1);
        }
        return text;
    }

    /** {@code dir/application.properties} + {@code test} gives {@code dir/application-test.properties}. */
    static String profileName(String name, String profile) {
        int slash = name.lastIndexOf('/');
        int dot = name.lastIndexOf('.');
        if (dot <= slash + 1) {
            return name + "-" + profile;
        }
        return name.substring(0, dot) + "-" + profile + name.substring(dot);
    }

    private static URL profileUrl(URL url, String profile) {
        if (url == null) {
            return null;
        }
        String path = url.getPath();
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        try {
            return new URL(url, profileName(fileName, profile));
        } catch (MalformedURLException e) {
            throw new ConfigurationException("Cannot derive profile resource for " + url, e);
        }
    }

    // ---- override layers --------------------------------------------------------------------------------------

    private Map<String, Value> override(Layer.Overriding layer, Set<String> knownKeys, List<String> problems) {
        Map<String, Value> values = new LinkedHashMap<>();
        if (layer instanceof Layer.Environment env) {
            Map<String, String> claimedBy = new HashMap<>();
            for (String key : knownKeys) {
                for (String name : EnvironmentNames.candidates(env.prefix(), key)) {
                    String text = environment.get(name);
                    if (text == null) {
                        continue;
                    }
                    String other = claimedBy.putIfAbsent(name, key);
                    if (other != null) {
                        problems.add("environment variable " + name + " matches both '" + other + "' and '" + key + "'");
                    } else {
                        values.put(key, new Value(text, "env " + name, false));
                    }
                    break;
                }
            }
        } else if (layer instanceof Layer.SystemProperties props) {
            for (String key : knownKeys) {
                String name = props.prefix() + key;
                String text = systemProperties.get(name);
                if (text != null) {
                    values.put(key, new Value(text, "system property " + name, false));
                }
            }
        }
        if (!values.isEmpty()) {
            LOG.debug("{} override(s) of {}: {}", values.size(), layer.describe(), values.keySet());
        }
        return values;
    }
}
