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
 * {@code ${}. Defaults may contain references. A value that uses a secret becomes secret. Missing references
 * without a default and cycles are collected and reported together.
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
            cycle.add(key);
            errors.add("reference cycle " + String.join(" -> ", cycle));
            return value;
        }
        if (value.text().indexOf('$') < 0) {
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

    private Expansion expand(String text, String owner, Deque<String> chain) {
        StringBuilder out = new StringBuilder(text.length());
        boolean secret = false;
        int i = 0;
        while (i < text.length()) {
            if (text.startsWith("$${", i)) {
                out.append("${");
                i += 3;
            } else if (text.startsWith("${", i)) {
                int end = closingBrace(text, i + 2);
                if (end < 0) {
                    errors.add("'" + owner + "' has an unterminated reference");
                    out.append(text, i, text.length());
                    break;
                }
                String expression = text.substring(i + 2, end);
                int colon = topLevelColon(expression);
                String name = colon < 0 ? expression : expression.substring(0, colon);
                if (raw.containsKey(name)) {
                    Value referenced = resolveKey(name, chain);
                    out.append(referenced.text());
                    secret |= referenced.secret();
                } else if (colon >= 0) {
                    Expansion fallback = expand(expression.substring(colon + 1), owner, chain);
                    out.append(fallback.text);
                    secret |= fallback.secret;
                } else {
                    errors.add("'" + owner + "' references missing key '" + name + "'");
                    out.append(text, i, end + 1);
                }
                i = end + 1;
            } else {
                out.append(text.charAt(i++));
            }
        }
        return new Expansion(out.toString(), secret);
    }

    /** Index of the {@code }} closing a reference whose body starts at {@code from}, or -1. */
    private static int closingBrace(String text, int from) {
        int depth = 0;
        for (int i = from; i < text.length(); i++) {
            if (text.startsWith("${", i)) {
                depth++;
                i++;
            } else if (text.charAt(i) == '}') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            }
        }
        return -1;
    }

    /** Index of the first {@code :} outside nested references, or -1. */
    private static int topLevelColon(String expression) {
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            if (expression.startsWith("${", i)) {
                depth++;
                i++;
            } else if (expression.charAt(i) == '}') {
                depth--;
            } else if (expression.charAt(i) == ':' && depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
