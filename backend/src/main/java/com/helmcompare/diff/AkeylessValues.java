package com.helmcompare.diff;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmcompare.diff.EnvVarExtractor.EnvVar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Actual values of AKeyless paths, uploaded as JSON, so AKeyless-backed variables (PROD) can be compared
 * value by value with plain-text variables (NON-PROD). Accepted shapes:
 * <pre>
 * { "/Platform/…/API_KEY": "value" }                         flat map
 * { "Platform": { "KPS": { "API_KEY": "value" } } }          folder tree
 * [ { "path": "/Platform/…/API_KEY", "value": "value" } ]    list (also under any wrapper key)
 * </pre>
 */
public final class AkeylessValues {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROPERTY = " › ";

    private final Map<String, String> values;

    public AkeylessValues(Map<String, String> values) {
        this.values = values == null ? Map.of() : values;
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    /** Sets {@link EnvVar#effectiveValue()} and {@link EnvVar#valueState()} of every AKeyless-backed variable. */
    public List<EnvVar> apply(List<EnvVar> vars) {
        List<EnvVar> out = new ArrayList<>(vars.size());
        for (EnvVar v : vars) {
            if (v.akeylessPath() == null) {
                out.add(v);
            } else if (v.akeylessPath().contains("{{")) {
                out.add(v.withValue(EnvVarExtractor.STATE_UNKNOWN, null));
            } else if (values.isEmpty()) {
                out.add(v.withValue(EnvVarExtractor.STATE_NO_JSON, null));
            } else {
                String value = lookup(v.akeylessPath());
                out.add(value == null ? v.withValue(EnvVarExtractor.STATE_NOT_IN_JSON, null)
                        : v.withValue(EnvVarExtractor.STATE_RESOLVED, value));
            }
        }
        return out;
    }

    /** @param path an AKeyless path, optionally "path › property" for a JSON secret */
    public String lookup(String path) {
        String property = null;
        int p = path.indexOf(PROPERTY);
        if (p >= 0) {
            property = path.substring(p + PROPERTY.length()).trim();
            path = path.substring(0, p);
        }
        String key = normalize(path);
        String value = find(key);
        if (property == null) return value;
        String nested = find(key + "/" + property);
        if (nested != null) return nested;
        if (value == null) return null;
        try {
            JsonNode node = JSON.readTree(value);
            return node != null && node.has(property) ? text(node.get(property)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String find(String key) {
        String value = values.get(key);
        if (value != null) return value;
        // the JSON may carry an extra root folder: accept a unique entry that ends with the path
        String match = null;
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (!e.getKey().endsWith(key)) continue;
            if (match != null) return null;
            match = e.getValue();
        }
        return match;
    }

    public static Map<String, String> parse(JsonNode root) {
        Map<String, String> out = new LinkedHashMap<>();
        flatten(root, "", out);
        return out;
    }

    private static void flatten(JsonNode node, String prefix, Map<String, String> out) {
        if (node == null) return;
        if (node.isArray()) {
            node.forEach(el -> flatten(el, prefix, out));
            return;
        }
        if (!node.isObject()) {
            if (!prefix.isEmpty()) out.put(normalize(prefix), text(node));
            return;
        }
        String path = firstText(node, "path", "name", "key", "secretPath", "item", "itemName");
        JsonNode value = first(node, "value", "secretValue", "secret", "val");
        if (path != null && value != null) {
            String key = join(prefix, path);
            out.put(key, text(value));
            if (value.isContainerNode()) flatten(value, key, out);
            return;
        }
        node.fields().forEachRemaining(e -> flatten(e.getValue(), join(prefix, e.getKey()), out));
    }

    public static String normalize(String path) {
        if (path == null) return "/";
        String p = path.trim();
        if (p.length() >= 2 && (p.startsWith("\"") && p.endsWith("\"") || p.startsWith("'") && p.endsWith("'"))) {
            p = p.substring(1, p.length() - 1).trim();
        }
        p = p.replace('\\', '/').replaceAll("/{2,}", "/");
        if (!p.startsWith("/")) p = "/" + p;
        if (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
        return p;
    }

    private static String join(String prefix, String key) {
        return key.startsWith("/") ? normalize(key) : normalize(prefix + "/" + key);
    }

    private static String firstText(JsonNode node, String... names) {
        for (String n : names) {
            JsonNode v = node.get(n);
            if (v != null && v.isTextual() && !v.asText().isBlank()) return v.asText();
        }
        return null;
    }

    private static JsonNode first(JsonNode node, String... names) {
        for (String n : names) {
            if (node.has(n)) return node.get(n);
        }
        return null;
    }

    private static String text(JsonNode n) {
        if (n == null || n.isNull()) return "";
        return n.isValueNode() ? n.asText() : n.toString();
    }
}
