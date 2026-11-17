package io.descoped.config;

import io.descoped.config.internal.Parsers;
import io.descoped.config.internal.Value;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Builds records from configuration keys, using only public reflection. */
final class RecordBinder {

    private static final String LIBRARY_MODULE = "io.descoped.dynamic.config";

    private final Snapshot config;
    private final List<String> problems = new ArrayList<>();
    private Throwable firstCause;
    private boolean secretInvolved;

    private RecordBinder(Snapshot config) {
        this.config = config;
    }

    static <R extends Record> R bind(Snapshot config, String prefix, Class<R> type) {
        Objects.requireNonNull(type, "type");
        RecordBinder binder = new RecordBinder(config);
        Object result = binder.bindRecord(prefix, type);
        if (!binder.problems.isEmpty()) {
            String message = "Cannot bind " + type.getName()
                    + (prefix.isEmpty() ? "" : " from '" + prefix + "'") + ": " + String.join("; ", binder.problems);
            // The first underlying failure is kept as the cause, unless a secret is involved: its message could
            // quote the value.
            throw binder.firstCause == null || binder.secretInvolved
                    ? new ConfigurationException(message)
                    : new ConfigurationException(message, binder.firstCause);
        }
        return type.cast(result);
    }

    private Object bindRecord(String prefix, Class<?> type) {
        if (!type.isRecord()) {
            throw new IllegalArgumentException(type.getName() + " is not a record");
        }
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        int problemsBefore = problems.size();
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            parameterTypes[i] = component.getType();
            arguments[i] = bindComponent(prefix, component);
        }
        if (problems.size() > problemsBefore) {
            return null;
        }
        return construct(type, parameterTypes, arguments);
    }

    private Object bindComponent(String prefix, RecordComponent component) {
        String name = component.getName();
        Class<?> raw = component.getType();
        Type generic = component.getGenericType();
        if (raw == Optional.class) {
            Type inner = typeArgument(generic);
            Class<?> innerRaw = rawClass(inner);
            if (innerRaw != null && innerRaw.isRecord()) {
                String sub = childPrefix(prefix, name);
                // bindRecord returns null when it recorded problems; bind() then throws with all of them.
                return config.hasKeysUnder(sub) ? Optional.ofNullable(bindRecord(sub, innerRaw)) : Optional.empty();
            }
            String key = childKey(prefix, name);
            Value value = config.value(key);
            return value == null ? Optional.empty() : Optional.ofNullable(convert(key, value, innerRaw, inner));
        }
        if (raw.isRecord()) {
            return bindRecord(childPrefix(prefix, name), raw);
        }
        String key = childKey(prefix, name);
        Value value = config.value(key);
        if (value == null) {
            problems.add("missing key '" + key + "'");
            return primitiveDefault(raw);
        }
        Object converted = convert(key, value, raw, generic);
        return converted == null ? primitiveDefault(raw) : converted;
    }

    private Object convert(String key, Value value, Class<?> raw, Type generic) {
        Function<String, ?> parser = parser(raw, generic);
        if (parser == null) {
            problems.add("unsupported type " + generic.getTypeName() + " for key '" + key + "'");
            return null;
        }
        try {
            return parser.apply(value.text());
        } catch (RuntimeException e) {
            problems.add("key '" + key + "' from " + value.source() + " is not a valid "
                    + raw.getSimpleName() + ": '" + value.display() + "'");
            keepCause(e, value.secret());
            return null;
        }
    }

    /** Parsers for the supported scalar component types; enums and {@code List<String>} are handled separately. */
    private static final Map<Class<?>, Function<String, ?>> SCALARS = Map.ofEntries(
            Map.entry(String.class, text -> text),
            Map.entry(int.class, text -> Integer.parseInt(text.trim())),
            Map.entry(Integer.class, text -> Integer.parseInt(text.trim())),
            Map.entry(long.class, text -> Long.parseLong(text.trim())),
            Map.entry(Long.class, text -> Long.parseLong(text.trim())),
            Map.entry(double.class, text -> Double.parseDouble(text.trim())),
            Map.entry(Double.class, text -> Double.parseDouble(text.trim())),
            Map.entry(boolean.class, Parsers::parseBoolean),
            Map.entry(Boolean.class, Parsers::parseBoolean),
            Map.entry(Duration.class, Parsers::parseDuration),
            Map.entry(Path.class, Path::of),
            Map.entry(URI.class, URI::create));

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Function<String, ?> parser(Class<?> raw, Type generic) {
        if (raw == null) {
            return null;
        }
        Function<String, ?> scalar = SCALARS.get(raw);
        if (scalar != null) {
            return scalar;
        }
        if (raw.isEnum()) {
            return text -> Parsers.parseEnum(text, (Class) raw);
        }
        if (raw == List.class && typeArgument(generic) == String.class) {
            return Parsers::parseList;
        }
        return null;
    }

    private Object construct(Class<?> type, Class<?>[] parameterTypes, Object[] arguments) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
            return constructor.newInstance(arguments);
        } catch (IllegalAccessException e) {
            problems.add(accessHint(type));
        } catch (InvocationTargetException e) {
            // A constructor's own exception can quote the arguments it was given, so only its type goes in the
            // message; the exception itself becomes the cause when no secret is bound.
            problems.add("the constructor of " + type.getName() + " threw " + e.getCause().getClass().getName());
            // Conservative: any configured secret may have been passed to the constructor.
            keepCause(e.getCause(), config.anySecret());
        } catch (NoSuchMethodException | InstantiationException e) {
            problems.add("cannot construct " + type.getName() + ": " + e);
        }
        return null;
    }

    private void keepCause(Throwable cause, boolean secret) {
        secretInvolved |= secret;
        if (firstCause == null) {
            firstCause = cause;
        }
    }

    private static String accessHint(Class<?> type) {
        Module module = type.getModule();
        String packageName = type.getPackageName();
        String hint = "cannot construct " + type.getName() + ": the record and its canonical constructor must be public";
        if (module.isNamed() && !module.isExported(packageName, RecordBinder.class.getModule())) {
            hint += ", and its package must be exported to " + LIBRARY_MODULE
                    + " (add 'exports " + packageName + " to " + LIBRARY_MODULE + ";' to module " + module.getName() + ")";
        }
        return hint;
    }

    private String childKey(String prefix, String component) {
        String kebab = join(prefix, kebab(component));
        if (config.value(kebab) != null) {
            return kebab;
        }
        String camel = join(prefix, component);
        return config.value(camel) != null ? camel : kebab;
    }

    private String childPrefix(String prefix, String component) {
        String kebab = join(prefix, kebab(component));
        if (config.hasKeysUnder(kebab)) {
            return kebab;
        }
        String camel = join(prefix, component);
        return config.hasKeysUnder(camel) ? camel : kebab;
    }

    private static String join(String prefix, String name) {
        return prefix.isEmpty() ? name : prefix + "." + name;
    }

    static String kebab(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2")
                .replaceAll("([A-Z])([A-Z][a-z])", "$1-$2")
                .toLowerCase(Locale.ROOT);
    }

    private static Type typeArgument(Type type) {
        return type instanceof ParameterizedType parameterized ? parameterized.getActualTypeArguments()[0] : Object.class;
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) {
            return c;
        }
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() instanceof Class<?> c) {
            return c;
        }
        return null;
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == long.class) {
            return 0L;
        }
        return 0;
    }
}
