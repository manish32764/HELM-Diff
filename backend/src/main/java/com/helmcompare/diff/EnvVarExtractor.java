package com.helmcompare.diff;

import com.helmcompare.parse.YNode;
import com.helmcompare.parse.YamlTreeParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts environment variables and secret values (with their real values) from raw Helm files:
 * container {@code env} lists, values-style {@code envVars} maps, {@code envSecrets} lists, {@code envFrom},
 * secret maps, {@code externalsecrets.akeyless.secretItems}, Kubernetes Secrets and ExternalSecrets.
 */
public final class EnvVarExtractor {

    /**
     * @param source         PLAIN, EMPTY, TEMPLATE, AKEYLESS, K8S_SECRET, EXTERNAL_SECRET, VAULT, CONFIGMAP, FIELD_REF
     * @param injection      how the variable reaches the container, steps separated by " › ", a step may end with " @ file:line"
     * @param akeylessPath   remote AKeyless path when the value is pulled from AKeyless
     * @param effectiveValue the value the container receives, or null when it cannot be determined
     * @param valueState     LITERAL, RESOLVED, NOT_IN_JSON, NO_JSON, UNKNOWN
     */
    public record EnvVar(String name, String value, String source, String reference, String kind, String file, int line,
                         String secretName, String secretKey, String injection, String akeylessPath,
                         String effectiveValue, String valueState) {

        static EnvVar of(String name, String value, String source, String reference, String kind, String file, int line,
                         String secretName, String secretKey, String injection, String akeylessPath) {
            if (akeylessPath == null && "AKEYLESS".equals(source)) akeylessPath = akeylessPrefixPath(value);
            if (akeylessPath != null) return new EnvVar(name, value, source, reference, kind, file, line, secretName, secretKey,
                    injection, akeylessPath, null, STATE_NO_JSON);
            boolean literal = switch (source) {
                case "PLAIN", "EMPTY", "TEMPLATE" -> true;
                case "K8S_SECRET" -> KIND_SECRET_DATA.equals(kind) || KIND_SECRET_VALUE.equals(kind);
                default -> false;
            };
            return new EnvVar(name, value, source, reference, kind, file, line, secretName, secretKey, injection, null,
                    literal ? (value == null ? "" : value) : null, literal ? STATE_LITERAL : STATE_UNKNOWN);
        }

        /** The env variable now receives its value from {@code backing} (a secret item / Secret data). */
        EnvVar backedBy(EnvVar backing, String newReference, String newInjection) {
            return new EnvVar(name, backing.value, backing.source, newReference, kind, file, line, secretName, secretKey,
                    newInjection, backing.akeylessPath, backing.effectiveValue, backing.valueState);
        }

        public EnvVar withValue(String state, String effective) {
            return new EnvVar(name, value, source, reference, kind, file, line, secretName, secretKey, injection, akeylessPath,
                    effective, state);
        }
    }

    public static final String KIND_ENV = "Env variable";
    public static final String KIND_ENV_FROM = "envFrom";
    public static final String KIND_SECRET_VALUE = "Secret value";
    public static final String KIND_SECRET_DATA = "K8s Secret data";
    public static final String KIND_EXTERNAL = "ExternalSecret key";
    public static final String KIND_SECRET_ITEM = "AKeyless secret item";

    public static final String STATE_LITERAL = "LITERAL";
    public static final String STATE_RESOLVED = "RESOLVED";
    public static final String STATE_NOT_IN_JSON = "NOT_IN_JSON";
    public static final String STATE_NO_JSON = "NO_JSON";
    public static final String STATE_UNKNOWN = "UNKNOWN";

    private static final Pattern ENV_KEY = Pattern.compile("(?i)^(env|extraenv|envvars|extraenvvars|environment|"
            + "environmentvariables|additionalenv|envs|extraenvs|secretenv|secretenvvars|containerenv|envsecrets|extraenvsecrets)$");
    private static final Pattern SECRET_MAP_KEY = Pattern.compile("(?i)^(secrets|secret|secretdata|secretvalues|akeyless|akeylesssecrets|secretenvs)$");
    private static final Pattern SECRET_ITEMS_KEY = Pattern.compile("(?i)^(secretitems|akeylessitems|remotesecrets)$");
    private static final Pattern AKEYLESS_PREFIX = Pattern.compile("(?i)^\\s*akeyless:\\s*(\\S.*)$");

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
                walk(doc, file, out, "", 0);
            }
        }
        return out;
    }

    /**
     * Links env variables that read a Kubernetes Secret to the ExternalSecret / secret item / Secret that provides it,
     * so "JIRA_API_TOKEN ← envSecrets attlasian-mcp-server/JIRA_API_TOKEN ← AKeyless /…/JIRA_API_TOKEN" becomes one row.
     */
    public static List<EnvVar> resolve(List<EnvVar> vars) {
        Map<String, EnvVar> named = new HashMap<>();
        Map<String, List<EnvVar>> byKey = new LinkedHashMap<>();
        for (EnvVar v : vars) {
            if (!isBacking(v)) continue;
            if (v.secretName() != null) named.putIfAbsent(v.secretName() + "/" + v.secretKey(), v);
            if (v.secretKey() != null) byKey.computeIfAbsent(v.secretKey(), k -> new ArrayList<>()).add(v);
        }
        Set<EnvVar> consumed = new HashSet<>();
        List<EnvVar> out = new ArrayList<>();
        for (EnvVar v : vars) {
            if (KIND_ENV.equals(v.kind()) && v.secretName() != null) {
                EnvVar b = named.get(v.secretName() + "/" + v.secretKey());
                boolean exact = b != null;
                if (b == null) b = templatedBacking(named.values(), v);
                if (b == null) b = keyBacking(byKey.getOrDefault(v.secretKey(), List.of()), v);
                if (b != null) {
                    if (exact || topFolder(b.file()).equals(topFolder(v.file()))) consumed.add(b);
                    String reference = b.akeylessPath() != null || KIND_EXTERNAL.equals(b.kind()) ? b.reference() : v.reference();
                    String injection = v.injection() + " › " + step(b.injection(), b.file(), b.line());
                    out.add(v.backedBy(b, reference, injection));
                    continue;
                }
            }
            out.add(v);
        }
        out.removeIf(consumed::contains);
        return out;
    }

    private static boolean isBacking(EnvVar v) {
        return KIND_EXTERNAL.equals(v.kind()) || KIND_SECRET_DATA.equals(v.kind()) || KIND_SECRET_ITEM.equals(v.kind());
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

    /**
     * {@code secretItems} do not name the Kubernetes Secret they create (it is the release name), so an
     * {@code envSecrets} entry is linked by secret key: same microservice folder first, then a folder named like the secret.
     */
    private static EnvVar keyBacking(List<EnvVar> candidates, EnvVar env) {
        EnvVar fallback = null;
        String wanted = compact(env.secretName());
        for (EnvVar b : candidates) {
            if (b.secretName() != null) continue;
            if (topFolder(b.file()).equals(topFolder(env.file()))) return b;
            if (!wanted.isEmpty() && compact(b.file()).contains(wanted)) fallback = b;
            else if (fallback == null) fallback = b;
        }
        return fallback;
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

    private static void walk(YNode node, String file, List<EnvVar> out, String path, int depth) {
        if (node == null || depth > 40) return;
        if (node.isSeq()) {
            node.seq.forEach(c -> walk(c, file, out, path, depth + 1));
            return;
        }
        if (!node.isMap()) return;
        for (Map.Entry<String, YNode> e : node.map.entrySet()) {
            String key = e.getKey();
            YNode v = e.getValue();
            String childPath = path.isEmpty() ? key : path + "." + key;
            if (SECRET_ITEMS_KEY.matcher(key).matches() && (v.isMap() || v.isSeq())) {
                secretItems(node, v, file, out, childPath);
                continue;
            }
            if (ENV_KEY.matcher(key).matches() && (v.isSeq() || v.isMap())) {
                if (v.isSeq()) {
                    for (YNode el : v.seq) {
                        String name = el.isMap() ? el.str("name") : null;
                        if (name != null && !name.startsWith("«")) out.add(envEntry(name, el, file, el.line, childPath));
                    }
                } else {
                    v.map.forEach((name, spec) -> {
                        if (!name.startsWith("«")) out.add(envEntry(name, spec, file, v.keyLine(name), childPath));
                    });
                }
                continue;
            }
            // "akeyless: { enabled, secretStoreName, secretItems }" is configuration, not a map of secret values
            if (SECRET_MAP_KEY.matcher(key).matches() && v.isMap() && !v.has("enabled") && !v.has("secretItems")) {
                boolean akeyless = key.toLowerCase(Locale.ROOT).contains("akeyless");
                boolean handled = false;
                for (Map.Entry<String, YNode> s : v.map.entrySet()) {
                    YNode sv = s.getValue();
                    if (s.getKey().startsWith("«") || !(sv.isScalar() || sv.isNull())) continue;
                    handled = true;
                    String value = display(sv.isNull() ? "" : sv.value);
                    String source = akeyless && !value.isBlank() ? "AKEYLESS" : detect(value);
                    String akeylessPath = "AKEYLESS".equals(source) && akeylessPrefixPath(value) == null ? value : null;
                    out.add(EnvVar.of(display(s.getKey()), value, source, null, KIND_SECRET_VALUE, file, v.keyLine(s.getKey()),
                            null, null, childPath, akeylessPath));
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
                    out.add(new EnvVar("envFrom: " + name, "", source, name, KIND_ENV_FROM, file, el.line, null, null,
                            childPath, null, null, STATE_UNKNOWN));
                }
                continue;
            }
            walk(v, file, out, childPath, depth + 1);
        }
    }

    private static EnvVar envEntry(String rawName, YNode spec, String file, int line, String path) {
        String name = display(rawName);
        if (spec == null || spec.isNull()) return EnvVar.of(name, "", "EMPTY", null, KIND_ENV, file, line, null, null, path, null);
        if (spec.isScalar()) {
            String value = display(spec.value);
            return EnvVar.of(name, value, detect(value), null, KIND_ENV, file, line, null, null, path, null);
        }
        if (!spec.isMap()) {
            return EnvVar.of(name, display(spec.toFlowJson()), "PLAIN", null, KIND_ENV, file, line, null, null, path, null);
        }
        YNode valueNode = spec.get("value");
        if (valueNode != null) {
            String value = valueNode.isScalar() ? display(valueNode.value) : valueNode.isNull() ? "" : display(valueNode.toFlowJson());
            return EnvVar.of(name, value, detect(value), null, KIND_ENV, file, line, null, null, path, null);
        }
        // envSecrets style: { name, secretName, secretKey }
        if (spec.str("secretName") != null && (spec.str("secretKey") != null || spec.str("key") != null)) {
            return secretRef(name, spec.str("secretName"), spec.str("secretKey") != null ? spec.str("secretKey") : spec.str("key"),
                    file, line, path);
        }
        YNode from = spec.get("valueFrom") != null && spec.get("valueFrom").isMap() ? spec.get("valueFrom") : spec;
        YNode secret = from.get("secretKeyRef");
        if (secret != null && secret.isMap()) {
            return secretRef(name, secret.str("name"), secret.str("key"), file, line, path);
        }
        YNode configMap = from.get("configMapKeyRef");
        if (configMap != null && configMap.isMap()) {
            return EnvVar.of(name, "", "CONFIGMAP", display(nz(configMap.str("name")) + "/" + nz(configMap.str("key"))),
                    KIND_ENV, file, line, null, null, path, null);
        }
        YNode field = from.get("fieldRef") != null ? from.get("fieldRef") : from.get("resourceFieldRef");
        if (field != null && field.isMap()) {
            String ref = field.str("fieldPath") != null ? field.str("fieldPath") : field.str("resource");
            return EnvVar.of(name, "", "FIELD_REF", display(nz(ref)), KIND_ENV, file, line, null, null, path, null);
        }
        for (Map.Entry<String, YNode> e : spec.map.entrySet()) {
            if (e.getKey().toLowerCase(Locale.ROOT).contains("akeyless")) {
                YNode a = e.getValue();
                String reference = display(a.isScalar() ? a.value : a.toFlowJson());
                return EnvVar.of(name, "", "AKEYLESS", reference, KIND_ENV, file, line, null, null, path,
                        a.isScalar() ? stripAkeylessPrefix(reference) : null);
            }
        }
        String json = display(spec.toFlowJson());
        return EnvVar.of(name, json, detect(json), null, KIND_ENV, file, line, null, null, path, null);
    }

    private static EnvVar secretRef(String name, String rawSecretName, String rawSecretKey, String file, int line, String path) {
        String secretName = display(nz(rawSecretName));
        String secretKey = display(nz(rawSecretKey));
        String reference = secretName + "/" + secretKey;
        String source = reference.toLowerCase(Locale.ROOT).contains("akeyless") ? "AKEYLESS" : "K8S_SECRET";
        String injection = path + " › Secret " + secretName + " · key " + secretKey;
        return new EnvVar(name, "", source, reference, KIND_ENV, file, line, secretName, secretKey, injection, null, null, STATE_UNKNOWN);
    }

    /** {@code secretItems: { KEY: { path: "/Platform/…/KEY" } }} or a list of {@code { key|secretKey, path }}. */
    private static void secretItems(YNode parent, YNode items, String file, List<EnvVar> out, String path) {
        String targetName = null;
        for (String k : List.of("targetSecretName", "targetName", "secretName", "target")) {
            if (parent.str(k) != null) {
                targetName = display(parent.str(k));
                break;
            }
        }
        if (items.isMap()) {
            for (Map.Entry<String, YNode> e : items.map.entrySet()) {
                if (e.getKey().startsWith("«")) continue;
                secretItem(display(e.getKey()), e.getValue(), items.keyLine(e.getKey()), targetName, file, out, path);
            }
        } else {
            for (YNode el : items.seq) {
                if (!el.isMap()) continue;
                String key = el.str("secretKey") != null ? el.str("secretKey") : el.str("key") != null ? el.str("key") : el.str("name");
                if (key != null && !key.startsWith("«")) secretItem(display(key), el, el.line, targetName, file, out, path);
            }
        }
    }

    private static void secretItem(String key, YNode spec, int line, String targetName, String file, List<EnvVar> out, String path) {
        String remote = null;
        String property = null;
        if (spec.isScalar()) {
            remote = spec.value;
        } else if (spec.isMap()) {
            remote = spec.str("path") != null ? spec.str("path") : spec.str("remoteKey") != null ? spec.str("remoteKey") : spec.str("remotePath");
            YNode ref = spec.get("remoteRef");
            if (remote == null && ref != null && ref.isMap()) {
                remote = ref.str("key");
                property = ref.str("property");
            }
            if (property == null) property = spec.str("property");
        }
        if (remote == null || remote.isBlank()) {
            out.add(new EnvVar(key, "", "AKEYLESS", null, KIND_SECRET_ITEM, file, line, targetName, key, path, null, null, STATE_UNKNOWN));
            return;
        }
        String remotePath = display(remote) + (property == null ? "" : " › " + display(property));
        out.add(EnvVar.of(key, "", "AKEYLESS", remotePath, KIND_SECRET_ITEM, file, line, targetName, key, path, remotePath));
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
            String reference = display(remoteKey);
            out.add(EnvVar.of(display(el.str("secretKey")), "", source, reference, KIND_EXTERNAL, file, el.line,
                    display(targetName), display(el.str("secretKey")), "ExternalSecret " + display(targetName),
                    "AKEYLESS".equals(source) && !reference.isBlank() ? reference : null));
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
                        secretName, display(k), "Secret " + secretName + " · " + field, null, value, STATE_LITERAL));
            });
        }
    }

    // ───────────────────────────── helpers ─────────────────────────────

    static String detect(String value) {
        if (value == null || value.isBlank()) return "EMPTY";
        String l = value.toLowerCase(Locale.ROOT);
        // "akeyless:/path" is a reference; "VAULT_PROVIDER: akeyless" or an AKeyless gateway URL is plain text
        if (l.startsWith("akeyless:")) return "AKEYLESS";
        if (l.startsWith("vault:") || l.contains("vault.hashicorp")) return "VAULT";
        if (value.contains("{{")) return "TEMPLATE";
        return "PLAIN";
    }

    /** "akeyless:/prod/app/token" → "/prod/app/token". */
    static String akeylessPrefixPath(String value) {
        if (value == null) return null;
        Matcher m = AKEYLESS_PREFIX.matcher(value);
        return m.matches() ? m.group(1).trim() : null;
    }

    private static String stripAkeylessPrefix(String value) {
        String p = akeylessPrefixPath(value);
        return p != null ? p : value;
    }

    private static String step(String text, String file, int line) {
        return text + " @ " + file + ":" + line;
    }

    /** First path segment: the microservice folder in a folder comparison. */
    public static String topFolder(String file) {
        if (file == null) return "";
        int slash = file.indexOf('/');
        return slash < 0 ? "" : file.substring(0, slash);
    }

    private static String compact(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
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
