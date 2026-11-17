package io.descoped.config.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves {@code ${key}} and {@code ${key:default}} references between values. {@code $${} is a literal
 * {@code ${}. Defaults may contain references. A value that uses a secret becomes secret. Literal values (from secret
 * sources) are taken as they are. Missing references without a default and cycles are collected and reported
 * together, each naming the key and its source.
 */
final class Interpolator {

    private final Map<String, Value> raw;
    private final Map<String, Value> resolved = new HashMap<>();
    private final Set<String> errors = new LinkedHashSet<>();

    private Interpolator(Map<String, Value> raw) {
        this.raw = raw;
    }

    /** Returns the resolved values in the same order, plus every problem found. */
    static Result resolve(Map<String, Value> raw) {
        Interpolator interpolator = new Interpolator(raw);
        Map<String, Value> out = new LinkedHashMap<>();
        for (String key : raw.keySet()) {
            out.put(key, interpolator.resolveKey(key, new ArrayDeque<>()));
        }
        return new Result(out, List.copyOf(interpolator.errors));
    }

    record Result(Map<String, Value> values, List<String> errors) {
    }

    private Value resolveKey(String key, Deque<String> chain) {
        Value done = resolved.get(key);
        if (done != null) {
            return done;
        }
        Value value = raw.get(key);
        if (chain.contains(key)) {
            List<String> cycle = new ArrayList<>(chain);
            cycle.subList(0, cycle.indexOf(key)).clear();
            List<String> sources = new ArrayList<>();
            for (String member : cycle) {
                sources.add(member + " from " + raw.get(member).source());
            }
            cycle.add(key);
            errors.add("reference cycle " + String.join(" -> ", cycle) + " (" + String.join(", ", sources) + ")");
            return value;
        }
        if (value.literal() || value.text().indexOf('$') < 0) {
            resolved.put(key, value);
            return value;
        }
        chain.addLast(key);
        Expansion expansion = expand(value.text(), key, chain);
        chain.removeLast();
        Value result = new Value(expansion.text, value.source(), value.secret() || expansion.secret);
        resolved.put(key, result);
        return result;
    }

    private record Expansion(String text, boolean secret) {
    }

    /** The text being built, and whether any secret went into it. */
    private static final class Output {
        private final StringBuilder text;
        private boolean secret;

        Output(int capacity) {
            this.text = new StringBuilder(capacity);
        }

        void append(String part, boolean fromSecret) {
            text.append(part);
            secret |= fromSecret;
        }
    }

    private Expansion expand(String text, String owner, Deque<String> chain) {
        Output out = new Output(text.length());
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("$${", i)) {
                out.text.append("${");
                i += 3;
            } else if (text.startsWith("${", i)) {
                i = appendReference(text, i, owner, chain, out);
            } else {
                out.text.append(text.charAt(i));
                i++;
            }
        }
        return new Expansion(out.text.toString(), out.secret);
    }

    /**
     * Appends the value of the reference starting at {@code start} (at {@code ${}) and returns the index after it. A
     * problem is recorded and the reference kept as written when it is unterminated (the rest of the text is taken) or
     * names a missing key without a default.
     */
    private int appendReference(String text, int start, String owner, Deque<String> chain, Output out) {
        int end = closingBrace(text, start + 2);
        if (end < 0) {
            errors.add(describe(owner) + " has an unterminated reference");
            out.text.append(text, start, text.length());
            return text.length();
        }
        String expression = text.substring(start + 2, end);
        int colon = topLevelColon(expression);
        String name = colon < 0 ? expression : expression.substring(0, colon);
        if (raw.containsKey(name)) {
            Value referenced = resolveKey(name, chain);
            out.append(referenced.text(), referenced.secret());
        } else if (colon >= 0) {
            Expansion fallback = expand(expression.substring(colon + 1), owner, chain);
            out.append(fallback.text, fallback.secret);
        } else {
            errors.add(describe(owner) + " references missing key '" + name + "'");
            out.text.append(text, start, end + 1);
        }
        return end + 1;
    }

    private String describe(String key) {
        return "'" + key + "' from " + raw.get(key).source();
    }

    /** Index of the {@code }} closing a reference whose body starts at {@code from}, or -1. */
    private static int closingBrace(String text, int from) {
        int depth = 0;
        int i = from;
        while (i < text.length()) {
            if (text.startsWith("${", i)) {
                depth++;
                i += 2;
                continue;
            }
            if (text.charAt(i) == '}') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
            i++;
        }
        return -1;
    }

    /** Index of the first {@code :} outside nested references, or -1. */
    private static int topLevelColon(String expression) {
        int depth = 0;
        int i = 0;
        while (i < expression.length()) {
            if (expression.startsWith("${", i)) {
                depth++;
                i += 2;
                continue;
            }
            char c = expression.charAt(i);
            if (c == '}') {
                depth--;
            } else if (c == ':' && depth == 0) {
                return i;
            }
            i++;
        }
        return -1;
    }
}
