package com.helmcompare.diff;

import com.helmcompare.parse.YNode;
import com.helmcompare.parse.YamlTreeParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Extracts environment variables and secret values (with their real values) from raw Helm files:
 * container {@code env} lists, values-style {@code env} maps, {@code envFrom}, secret maps,
 * Kubernetes Secrets and ExternalSecrets (e.g. backed by AKeyless).
 */
public final class EnvVarExtractor {

    /** Source: PLAIN, EMPTY, TEMPLATE, AKEYLESS, K8S_SECRET, EXTERNAL_SECRET, VAULT, CONFIGMAP, FIELD_REF. */
    public record EnvVar(String name, String value, String source, String reference, String kind, String file, int line,
                         String secretName, String secretKey) {
        EnvVar with(String newSource, String newValue, String newReference) {
            return new EnvVar(name, newValue, newSource, newReference, kind, file, line, secretName, secretKey);
        }
    }

    public static final String KIND_ENV = "Env variable";
    public static final String KIND_ENV_FROM = "envFrom";
    public static final String KIND_SECRET_VALUE = "Secret value";
    public static final String KIND_SECRET_DATA = "K8s Secret data";
    public static final String KIND_EXTERNAL = "ExternalSecret key";

    private static final Pattern ENV_KEY = Pattern.compile("(?i)^(env|extraenv|envvars|extraenvvars|environment|"
            + "environmentvariables|additionalenv|envs|extraenvs|secretenv|secretenvvars|containerenv)$");
    private static final Pattern SECRET_MAP_KEY = Pattern.compile("(?i)^(secrets|secret|secretdata|secretvalues|akeyless|akeylesssecrets|secretenvs)$");

    private EnvVarExtractor() {
    }

    public static List<EnvVar> extract(String file, byte[] bytes) {
        if (bytes == null || !LogicalFileComparer.isYaml(file) || LogicalFileComparer.isBinary(bytes)) return List.of();
        String text = LogicalFileComparer.text(bytes);
        String yaml = text.contains("{{") ? TemplateLiteral.process(text).yaml() : text;
        List<EnvVar> out = new ArrayList<>();
        for (YNode doc : YamlTreeParser.parse(file, yaml).documents()) {
            if (!doc.isMap()) continue;
            String kind = doc.str("kind");
            if ("ExternalSecret".equals(kind)) {
                externalSecret(doc, file, out);
            } else if ("Secret".equals(kind)) {
                secretDocument(doc, file, out);
            } else {
                walk(doc, file, out, 0);
            }
        }
        return out;
    }

    /**
     * Links env variables that read a Kubernetes Secret to the ExternalSecret / Secret that provides it,
     * so "DB_PASSWORD ← secretKeyRef payments-akeyless/db-password ← AKeyless /prod/db-password" becomes one row.
     */
    public static List<EnvVar> resolve(List<EnvVar> vars) {
        Map<String, EnvVar> backing = new HashMap<>();
        for (EnvVar v : vars) {
            if ((KIND_EXTERNAL.equals(v.kind()) || KIND_SECRET_DATA.equals(v.kind())) && v.secretName() != null) {
                backing.putIfAbsent(v.secretName() + "/" + v.secretKey(), v);
            }
        }
        Set<EnvVar> consumed = new HashSet<>();
        List<EnvVar> out = new ArrayList<>();
        for (EnvVar v : vars) {
            if (KIND_ENV.equals(v.kind()) && v.secretName() != null) {
                EnvVar b = backing.get(v.secretName() + "/" + v.secretKey());
                if (b == null) b = templatedBacking(backing.values(), v);
                if (b != null) {
                    consumed.add(b);
                    String reference = KIND_EXTERNAL.equals(b.kind())
                            ? b.reference() + "  (via secret " + v.secretName() + "/" + v.secretKey() + ")"
                            : v.reference();
                    out.add(v.with(b.source(), b.value(), reference));
                    continue;
                }
            }
            out.add(v);
        }
        out.removeIf(consumed::contains);
        return out;
    }

    /**
     * Secret names are often templated ({@code {{ .Values.name }}-akeyless}) on one side and literal on the other.
     * Template expressions are treated as wildcards when the secret key matches.
     */
    private static EnvVar templatedBacking(java.util.Collection<EnvVar> candidates, EnvVar env) {
        for (EnvVar b : candidates) {
            if (b.secretKey() == null || !b.secretKey().equals(env.secretKey())) continue;
            if (wildcardMatches(b.secretName(), env.secretName()) || wildcardMatches(env.secretName(), b.secretName())) return b;
        }
        return null;
    }

    private static boolean wildcardMatches(String pattern, String value) {
        if (pattern == null || value == null || !pattern.contains("{{")) return false;
        StringBuilder regex = new StringBuilder();
        for (String part : pattern.split("\\{\\{.*?\\}\\}", -1)) {
            if (!regex.isEmpty()) regex.append(".+");
            regex.append(Pattern.quote(part));
        }
        return value.matches(regex.toString());
    }

    // ───────────────────────────── walkers ─────────────────────────────

    private static void walk(YNode node, String file, List<EnvVar> out, int depth) {
        if (node == null || depth > 40) return;
        if (node.isSeq()) {
            node.seq.forEach(c -> walk(c, file, out, depth + 1));
            return;
        }
        if (!node.isMap()) return;
        for (Map.Entry<String, YNode> e : node.map.entrySet()) {
            String key = e.getKey();
            YNode v = e.getValue();
            if (ENV_KEY.matcher(key).matches() && (v.isSeq() || v.isMap())) {
                if (v.isSeq()) {
                    for (YNode el : v.seq) {
                        String name = el.isMap() ? el.str("name") : null;
                        if (name != null && !name.startsWith("«")) out.add(envEntry(name, el, file, el.line));
                    }
                } else {
                    v.map.forEach((name, spec) -> {
                        if (!name.startsWith("«")) out.add(envEntry(name, spec, file, v.keyLine(name)));
                    });
                }
                continue;
            }
            if (SECRET_MAP_KEY.matcher(key).matches() && v.isMap()) {
                boolean akeyless = key.toLowerCase(Locale.ROOT).contains("akeyless");
                boolean handled = false;
                for (Map.Entry<String, YNode> s : v.map.entrySet()) {
                    YNode sv = s.getValue();
                    if (s.getKey().startsWith("«") || !(sv.isScalar() || sv.isNull())) continue;
                    handled = true;
                    String value = display(sv.isNull() ? "" : sv.value);
                    String source = akeyless && !value.isBlank() ? "AKEYLESS" : detect(value);
                    out.add(new EnvVar(display(s.getKey()), value, source, null, KIND_SECRET_VALUE, file, v.keyLine(s.getKey()), null, null));
                }
                if (handled) continue;
            }
            if (key.equalsIgnoreCase("envFrom") && v.isSeq()) {
                for (YNode el : v.seq) {
                    if (!el.isMap()) continue;
                    YNode ref = el.get("secretRef") != null ? el.get("secretRef") : el.get("configMapRef");
                    if (ref == null || !ref.isMap()) continue;
                    String name = display(ref.str("name") == null ? "?" : ref.str("name"));
                    boolean secret = el.get("secretRef") != null;
                    String source = !secret ? "CONFIGMAP" : name.toLowerCase(Locale.ROOT).contains("akeyless") ? "AKEYLESS" : "K8S_SECRET";
                    out.add(new EnvVar("envFrom: " + name, "", source, name, KIND_ENV_FROM, file, el.line, null, null));
                }
                continue;
            }
            walk(v, file, out, depth + 1);
        }
    }

    private static EnvVar envEntry(String rawName, YNode spec, String file, int line) {
        String name = display(rawName);
        if (spec == null || spec.isNull()) return new EnvVar(name, "", "EMPTY", null, KIND_ENV, file, line, null, null);
        if (spec.isScalar()) {
            String value = display(spec.value);
            return new EnvVar(name, value, detect(value), null, KIND_ENV, file, line, null, null);
        }
        if (!spec.isMap()) {
            return new EnvVar(name, display(spec.toFlowJson()), "PLAIN", null, KIND_ENV, file, line, null, null);
        }
        YNode valueNode = spec.get("value");
        if (valueNode != null) {
            String value = valueNode.isScalar() ? display(valueNode.value) : valueNode.isNull() ? "" : display(valueNode.toFlowJson());
            return new EnvVar(name, value, detect(value), null, KIND_ENV, file, line, null, null);
        }
        YNode from = spec.get("valueFrom") != null && spec.get("valueFrom").isMap() ? spec.get("valueFrom") : spec;
        YNode secret = from.get("secretKeyRef");
        if (secret != null && secret.isMap()) {
            String secretName = display(nz(secret.str("name")));
            String secretKey = display(nz(secret.str("key")));
            String reference = secretName + "/" + secretKey;
            String source = reference.toLowerCase(Locale.ROOT).contains("akeyless") ? "AKEYLESS" : "K8S_SECRET";
            return new EnvVar(name, "", source, reference, KIND_ENV, file, line, secretName, secretKey);
        }
        YNode configMap = from.get("configMapKeyRef");
        if (configMap != null && configMap.isMap()) {
            return new EnvVar(name, "", "CONFIGMAP", display(nz(configMap.str("name")) + "/" + nz(configMap.str("key"))),
                    KIND_ENV, file, line, null, null);
        }
        YNode field = from.get("fieldRef") != null ? from.get("fieldRef") : from.get("resourceFieldRef");
        if (field != null && field.isMap()) {
            String ref = field.str("fieldPath") != null ? field.str("fieldPath") : field.str("resource");
            return new EnvVar(name, "", "FIELD_REF", display(nz(ref)), KIND_ENV, file, line, null, null);
        }
        for (Map.Entry<String, YNode> e : spec.map.entrySet()) {
            if (e.getKey().toLowerCase(Locale.ROOT).contains("akeyless")) {
                YNode a = e.getValue();
                return new EnvVar(name, "", "AKEYLESS", display(a.isScalar() ? a.value : a.toFlowJson()), KIND_ENV, file, line, null, null);
            }
        }
        String json = display(spec.toFlowJson());
        return new EnvVar(name, json, detect(json), null, KIND_ENV, file, line, null, null);
    }

    private static void externalSecret(YNode doc, String file, List<EnvVar> out) {
        YNode spec = doc.get("spec");
        if (spec == null || !spec.isMap()) return;
        String source = doc.containsText("akeyless") ? "AKEYLESS" : "EXTERNAL_SECRET";
        YNode metadata = doc.get("metadata");
        YNode target = spec.get("target");
        String targetName = target != null && target.isMap() && target.str("name") != null ? target.str("name")
                : metadata != null && metadata.isMap() ? nz(metadata.str("name")) : "";
        YNode data = spec.get("data");
        if (data == null || !data.isSeq()) return;
        for (YNode el : data.seq) {
            if (!el.isMap() || el.str("secretKey") == null) continue;
            YNode remote = el.get("remoteRef");
            String remoteKey = remote != null && remote.isMap() ? nz(remote.str("key")) : "";
            if (remote != null && remote.isMap() && remote.str("property") != null) remoteKey += " › " + remote.str("property");
            out.add(new EnvVar(display(el.str("secretKey")), "", source, display(remoteKey), KIND_EXTERNAL, file, el.line,
                    display(targetName), display(el.str("secretKey"))));
        }
    }

    private static void secretDocument(YNode doc, String file, List<EnvVar> out) {
        YNode metadata = doc.get("metadata");
        String secretName = metadata != null && metadata.isMap() ? display(nz(metadata.str("name"))) : "";
        String source = doc.containsText("akeyless") ? "AKEYLESS" : "K8S_SECRET";
        for (String field : List.of("stringData", "data")) {
            YNode data = doc.get(field);
            if (data == null || !data.isMap()) continue;
            data.map.forEach((k, v) -> {
                if (k.startsWith("«")) return;
                String value = v.isScalar() ? display(v.value) : "";
                out.add(new EnvVar(display(k), value, source, "Secret " + secretName, KIND_SECRET_DATA, file, data.keyLine(k),
                        secretName, display(k)));
            });
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    static String detect(String value) {
        if (value == null || value.isBlank()) return "EMPTY";
        String l = value.toLowerCase(Locale.ROOT);
        if (l.contains("akeyless")) return "AKEYLESS";
        if (l.startsWith("vault:") || l.contains("vault.hashicorp")) return "VAULT";
        if (value.contains("{{")) return "TEMPLATE";
        return "PLAIN";
    }

    /** Restores template placeholders created by {@link TemplateLiteral} for display. */
    static String display(String s) {
        if (s == null) return null;
        return s.replace("«", "{{ ").replace("»", " }}");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
