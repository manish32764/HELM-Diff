package com.helmcompare.parse;

import com.helmcompare.model.Category;
import com.helmcompare.model.ConfigItem;
import com.helmcompare.model.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Walks parsed YAML documents and extracts logical configuration items. Recognition is based on
 * the configuration meaning (an {@code env} list, a {@code readinessProbe} block, ...) wherever it
 * appears, so the same configuration matches across charts even when it sits at a different location.
 */
public final class ConfigExtractor {

    public enum FileKind { VALUES, TEMPLATE, MANIFEST }

    public record ParsedFile(String path, FileKind kind, List<YNode> documents) {
    }

    public record Output(List<ConfigItem> items, Map<String, List<Integer>> maskedLines) {
    }

    private static final Set<String> ENV_KEYS = Set.of("env", "extraenv", "envvars", "extraenvvars", "environment",
            "environmentvariables", "additionalenv", "envs", "extraenvs", "secretenv", "secretenvvars", "containerenv");
    private static final Set<String> SECRET_MAP_KEYS = Set.of("secrets", "secret", "secretdata", "secretvalues");
    private static final Set<String> PROBE_KEYS = Set.of("livenessprobe", "readinessprobe", "startupprobe");
    private static final Set<String> PROBE_NAMES = Set.of("liveness", "readiness", "startup",
            "livenessprobe", "readinessprobe", "startupprobe");
    private static final Set<String> PROBE_GROUP_KEYS = Set.of("probes", "healthcheck", "healthchecks", "health");
    private static final Set<String> METADATA_KEYS = Set.of("annotations", "podannotations", "labels", "podlabels",
            "commonlabels", "commonannotations", "deploymentannotations", "serviceannotations");
    private static final Set<String> NOISY_METADATA = Set.of("helm.sh/chart", "chart", "heritage", "release",
            "app.kubernetes.io/version", "app.kubernetes.io/managed-by", "app.kubernetes.io/instance", "version");
    private static final Set<String> SKIP_MANIFEST_KEYS = Set.of("apiversion", "kind", "selector",
            "creationtimestamp", "status", "namespace");
    private static final Set<String> CONTAINER_LIST_KEYS = Set.of("containers", "initcontainers", "sidecars",
            "extracontainers", "sidecarcontainers");
    private static final Set<String> SCHEDULING_KEYS = Set.of("nodeselector", "affinity", "tolerations");

    private final List<ConfigItem> raw = new ArrayList<>();
    private final IdentityHashMap<ConfigItem, FileKind> kinds = new IdentityHashMap<>();
    private final Map<String, TreeSet<Integer>> masked = new LinkedHashMap<>();
    private final Map<String, ValueSource> secretBackings = new HashMap<>();
    private final Set<String> akeylessStores = new HashSet<>();

    private record Ctx(String file, FileKind fileKind, boolean manifest, String scope, List<String> path) {
        Ctx child(String segment) {
            List<String> p = new ArrayList<>(path);
            p.add(segment);
            return new Ctx(file, fileKind, manifest, scope, p);
        }

        Ctx scoped(String s) {
            return new Ctx(file, fileKind, manifest, s, path);
        }

        String pathString() {
            StringBuilder sb = new StringBuilder();
            for (String seg : path) {
                if (!seg.startsWith("[") && !sb.isEmpty()) sb.append('.');
                sb.append(seg);
            }
            return sb.toString().replace("spec.template.spec.", "pod.");
        }

        String lastKey() {
            for (int i = path.size() - 1; i >= 0; i--) {
                if (!path.get(i).startsWith("[")) return path.get(i);
            }
            return "";
        }
    }

    private record Leaf(String path, String value, int line, boolean sensitive) {
    }

    public Output extract(List<ParsedFile> files) {
        List<ParsedFile> ordered = new ArrayList<>(files);
        ordered.sort((x, y) -> Integer.compare(x.kind() == FileKind.VALUES ? 0 : 1, y.kind() == FileKind.VALUES ? 0 : 1));
        for (ParsedFile f : ordered) {
            for (YNode doc : f.documents()) preScan(doc);
        }
        for (ParsedFile f : ordered) {
            for (YNode doc : f.documents()) {
                if (doc.isMap()) walkDocument(f, doc);
            }
        }
        applySecretBackings();
        List<ConfigItem> merged = mergeDuplicates();
        Map<String, List<Integer>> lines = new LinkedHashMap<>();
        masked.forEach((file, set) -> lines.put(file, new ArrayList<>(set)));
        return new Output(merged, lines);
    }

    // ───────────────────────────── documents ─────────────────────────────

    private void preScan(YNode doc) {
        if (!doc.isMap()) return;
        String kind = doc.str("kind");
        if (!"SecretStore".equals(kind) && !"ClusterSecretStore".equals(kind)) return;
        YNode spec = doc.get("spec");
        YNode provider = spec == null ? null : spec.get("provider");
        YNode metadata = doc.get("metadata");
        if (provider != null && provider.containsText("akeyless") && metadata != null && metadata.str("name") != null) {
            akeylessStores.add(metadata.str("name"));
        }
    }

    private void walkDocument(ParsedFile f, YNode doc) {
        String kind = doc.str("kind");
        boolean manifest = kind != null && doc.has("apiVersion");
        YNode metadata = doc.get("metadata");
        String docName = metadata != null && metadata.str("name") != null ? metadata.str("name") : "";
        Ctx root = new Ctx(f.path(), f.kind(), manifest, docName.isEmpty() ? null : docName, new ArrayList<>());
        if (!manifest) {
            walk(doc, root, null);
            return;
        }
        Ctx ctx = root.child(kind + (docName.isEmpty() ? "" : "/" + docName));
        YNode spec = doc.get("spec");
        switch (kind) {
            case "Secret" -> secretDocument(doc, ctx, docName);
            case "ExternalSecret" -> externalSecret(doc, ctx, docName);
            case "SecretStore", "ClusterSecretStore" -> block("secretstore:" + docName, "Secret store " + docName,
                    "secretstore", Category.SECRETS, spec, ctx.child("spec"), doc.line,
                    akeylessStores.contains(docName) ? ValueSource.AKEYLESS : ValueSource.EXTERNAL_SECRET);
            case "ConfigMap" -> configMap(doc, ctx, docName);
            case "Service" -> block("service:" + docName, "Service " + docName, "service", Category.SERVICES,
                    spec, ctx.child("spec"), doc.line, null);
            case "Ingress" -> block("ingress:" + docName, "Ingress " + docName, "service", Category.SERVICES,
                    spec, ctx.child("spec"), doc.line, null);
            case "NetworkPolicy" -> block("networkpolicy:" + docName, "Network policy " + docName, "service",
                    Category.SERVICES, spec, ctx.child("spec"), doc.line, null);
            case "HorizontalPodAutoscaler" -> block("hpa", "Horizontal Pod Autoscaler", "scaling", Category.SCALING,
                    spec, ctx.child("spec"), doc.line, null);
            case "PodDisruptionBudget" -> block("pdb", "Pod Disruption Budget", "scaling", Category.SCALING,
                    spec, ctx.child("spec"), doc.line, null);
            case "ServiceAccount" -> block("serviceaccount", "Service account", "security", Category.SECURITY_CONTEXT,
                    doc, ctx, doc.line, null);
            default -> {
                walk(doc, ctx, null);
                return;
            }
        }
        if (metadata != null) walk(metadata, ctx.child("metadata"), null);
    }

    private void secretDocument(YNode doc, Ctx ctx, String docName) {
        boolean akeyless = doc.containsText("akeyless");
        for (String field : List.of("data", "stringData")) {
            YNode data = doc.get(field);
            if (data == null || !data.isMap()) continue;
            data.map.forEach((k, v) -> secretValue(k, v, ctx.child(field).child(k), data.keyLine(k),
                    akeyless ? ValueSource.AKEYLESS : null, docName));
        }
        if (!docName.isEmpty()) secretBackings.put(docName, akeyless ? ValueSource.AKEYLESS : ValueSource.SECRET_REF);
    }

    private void externalSecret(YNode doc, Ctx ctx, String docName) {
        YNode spec = doc.get("spec");
        if (spec == null || !spec.isMap()) return;
        YNode storeRef = spec.get("secretStoreRef");
        String store = storeRef == null || storeRef.str("name") == null ? "" : storeRef.str("name");
        boolean akeyless = akeylessStores.contains(store) || store.toLowerCase(Locale.ROOT).contains("akeyless")
                || doc.containsText("akeyless");
        ValueSource source = akeyless ? ValueSource.AKEYLESS : ValueSource.EXTERNAL_SECRET;
        YNode target = spec.get("target");
        String targetName = target != null && target.str("name") != null ? target.str("name") : docName;
        if (!targetName.isEmpty()) secretBackings.put(targetName, source);

        block("externalsecret:" + docName, "External secret " + docName, "externalsecret", Category.SECRETS,
                spec, ctx.child("spec"), doc.line, source);

        YNode data = spec.get("data");
        if (data != null && data.isSeq()) {
            for (YNode el : data.seq) {
                if (!el.isMap() || el.str("secretKey") == null) continue;
                String secretKey = el.str("secretKey");
                YNode remote = el.get("remoteRef");
                String ref = remote == null ? store : join(remote.str("key"), remote.str("property"));
                variable("secret:" + secretKey, secretKey, true, source, null, ref,
                        ctx.child("spec").child("data").child("[" + secretKey + "]"), el.line, el.line);
            }
        }
    }

    private void configMap(YNode doc, Ctx ctx, String docName) {
        for (String field : List.of("data", "binaryData")) {
            YNode data = doc.get(field);
            if (data == null || !data.isMap()) continue;
            data.map.forEach((k, v) -> configMapEntry(k, v, ctx.child(field).child(k), data.keyLine(k), docName));
        }
    }

    private void configMapEntry(String name, YNode value, Ctx ctx, int line, String docName) {
        String rawValue = value == null || value.isNull() ? "" : value.isScalar() ? value.value : value.toFlowJson();
        ConfigItem it = base("configmap:" + name, name, "config", Category.CONFIG, ctx, line);
        it.scope = docName;
        boolean sensitive = Sensitivity.isSensitiveName(name);
        it.sensitive = sensitive;
        it.source = Sensitivity.detectLiteralSource(rawValue);
        String lower = name.toLowerCase(Locale.ROOT);
        boolean multiline = rawValue.contains("\n");

        if (multiline && (lower.endsWith(".yaml") || lower.endsWith(".yml"))) {
            List<YNode> docs = YamlTreeParser.parse(name, rawValue).documents();
            List<Leaf> leaves = new ArrayList<>();
            for (YNode d : docs) collectLeaves(d, "", "", leaves);
            int baseLine = value == null ? line : value.line;
            for (Leaf leaf : leaves) putField(it, leaf.path(), leaf.value(), leaf.sensitive(), ctx.file(), baseLine + leaf.line());
        } else if (multiline && (lower.endsWith(".properties") || lower.endsWith(".env") || looksLikeProperties(rawValue))) {
            String[] lines = rawValue.split("\n");
            int baseLine = value == null ? line : value.line;
            for (int i = 0; i < lines.length; i++) {
                String l = lines[i].strip();
                int eq = l.indexOf('=');
                if (l.isEmpty() || l.startsWith("#") || eq <= 0) continue;
                String k = l.substring(0, eq).strip();
                putField(it, k, l.substring(eq + 1).strip(), Sensitivity.isSensitiveName(k), ctx.file(), baseLine + i + 1);
            }
        }

        if (it.fields.isEmpty()) {
            if (sensitive && it.source == ValueSource.LITERAL) {
                it.display = Sensitivity.mask(rawValue);
                it.canonical = Sensitivity.fingerprint(rawValue);
                markMasked(ctx.file(), value == null ? line : value.line);
            } else {
                it.display = multiline ? firstLine(rawValue) + " … (" + rawValue.split("\n").length + " lines)" : truncate(rawValue);
                it.canonical = rawValue;
            }
            it.fields.put("value", it.display);
        } else {
            it.display = it.fields.size() + " setting(s)";
            it.canonical = new TreeMap<>(it.fields).toString();
        }
        if (sensitive || it.source.isSecretMechanism()) it.category = Category.SECRETS;
        add(it, ctx.fileKind());
    }

    // ───────────────────────────── generic walk ─────────────────────────────

    private void walk(YNode node, Ctx ctx, String parentKeyLower) {
        if (node == null) return;
        switch (node.kind) {
            case MAP -> {
                for (Map.Entry<String, YNode> e : node.map.entrySet()) {
                    String k = e.getKey();
                    String lk = k.toLowerCase(Locale.ROOT);
                    if (ctx.manifest() && SKIP_MANIFEST_KEYS.contains(lk)) continue;
                    if (special(k, lk, e.getValue(), node.keyLine(k), ctx)) continue;
                    walk(e.getValue(), ctx.child(k), lk);
                }
            }
            case SEQ -> {
                if (node.seq.stream().allMatch(c -> c.isScalar() || c.isNull())) {
                    leaf(ctx, joinScalars(node), node.line);
                    return;
                }
                for (int i = 0; i < node.seq.size(); i++) {
                    YNode child = node.seq.get(i);
                    String name = child.isMap() ? child.str("name") : null;
                    Ctx cc = ctx.child("[" + (name != null ? name : String.valueOf(i)) + "]");
                    if (name != null && parentKeyLower != null && CONTAINER_LIST_KEYS.contains(parentKeyLower)) {
                        cc = cc.scoped(name);
                    }
                    walk(child, cc, null);
                }
            }
            case SCALAR -> leaf(ctx, node.value, node.line);
            case NULL -> {
                // nothing configured
            }
        }
    }

    /** Recognises logical configuration blocks. Returns true when the key has been fully handled. */
    private boolean special(String k, String lk, YNode v, int keyLine, Ctx parent) {
        Ctx ctx = parent.child(k);

        if (ENV_KEYS.contains(lk)) {
            boolean secretHint = lk.contains("secret");
            if (v.isSeq() && v.seq.stream().anyMatch(c -> c.isMap() && c.has("name"))) {
                for (YNode el : v.seq) {
                    String name = el.isMap() ? el.str("name") : null;
                    if (name != null && !name.contains("‹")) envVar(name, el, ctx.child("[" + name + "]"), el.line, secretHint);
                }
                return true;
            }
            if (v.isMap() && envLike(v)) {
                v.map.forEach((name, spec) -> envVar(name, spec, ctx.child(name), v.keyLine(name), secretHint));
                return true;
            }
            return false;
        }
        if (SECRET_MAP_KEYS.contains(lk) && v.isMap() && envLike(v)) {
            v.map.forEach((name, spec) -> secretValue(name, spec, ctx.child(name), v.keyLine(name), null, null));
            return true;
        }
        if (lk.equals("envfrom") && v.isSeq()) {
            envFrom(v, ctx);
            return true;
        }
        if (PROBE_KEYS.contains(lk)) {
            if (v.isMap()) probe(lk.replace("probe", ""), v, ctx, keyLine);
            return true;
        }
        if (PROBE_GROUP_KEYS.contains(lk) && v.isMap()
                && v.map.keySet().stream().anyMatch(pk -> PROBE_NAMES.contains(pk.toLowerCase(Locale.ROOT)))) {
            v.map.forEach((pk, pv) -> {
                String plk = pk.toLowerCase(Locale.ROOT);
                if (PROBE_NAMES.contains(plk)) {
                    if (pv.isMap()) probe(plk.replace("probe", ""), pv, ctx.child(pk), v.keyLine(pk));
                } else {
                    walk(pv, ctx.child(pk), plk);
                }
            });
            return true;
        }
        if (lk.equals("topologyspreadconstraints") || lk.equals("tsc") || lk.startsWith("topologyspread")) {
            if (!v.isNull()) {
                block("tsc", "Topology spread constraints (TSC)", "tsc", Category.TSC, v, ctx, keyLine, null);
            }
            return true;
        }
        if (lk.equals("resources") && v.isMap()) {
            flattenLeaves("resources", "Resources ", v, ctx, Category.RESOURCES, "resource", null);
            return true;
        }
        if ((lk.equals("volumes") || lk.equals("extravolumes")) && v.isSeq()) {
            namedBlocks("volume", "Volume ", v, ctx);
            return true;
        }
        if ((lk.equals("volumemounts") || lk.equals("extravolumemounts")) && v.isSeq()) {
            namedBlocks("volumeMount", "Volume mount ", v, ctx);
            return true;
        }
        if (lk.endsWith("securitycontext") && v.isMap()) {
            flattenLeaves(k, k + " ", v, ctx, Category.SECURITY_CONTEXT, "security", null);
            return true;
        }
        if (SCHEDULING_KEYS.contains(lk)) {
            boolean empty = v.isNull() || (v.isMap() && v.map.isEmpty()) || (v.isSeq() && v.seq.isEmpty());
            if (!empty) block("scheduling:" + k, humanize(k), "scheduling", Category.SCHEDULING, v, ctx, keyLine, null);
            return true;
        }
        if (lk.equals("priorityclassname") && v.isScalar()) {
            simple("scheduling:priorityClassName", "Priority class", "scheduling", Category.SCHEDULING, v, ctx);
            return true;
        }
        if ((lk.equals("replicas") || lk.equals("replicacount")) && v.isScalar()) {
            simple("replicas", "Replicas", "scaling", Category.SCALING, v, ctx);
            return true;
        }
        if ((lk.equals("autoscaling") || lk.equals("hpa")) && v.isMap()) {
            flattenLeaves("autoscaling", "Autoscaling ", v, ctx, Category.SCALING, "scaling", null);
            return true;
        }
        if ((lk.equals("poddisruptionbudget") || lk.equals("pdb")) && v.isMap()) {
            flattenLeaves("pdb", "Pod disruption budget ", v, ctx, Category.SCALING, "scaling", null);
            return true;
        }
        if (lk.equals("image")) {
            if (v.isScalar()) simple("image", "Image", "image", Category.WORKLOAD, v, ctx);
            else if (v.isMap()) flattenLeaves("image", "Image ", v, ctx, Category.WORKLOAD, "image", null);
            else return v.isNull();
            return true;
        }
        if (lk.equals("imagepullpolicy") && v.isScalar()) {
            simple("image:pullPolicy", "Image pull policy", "image", Category.WORKLOAD, v, ctx);
            return true;
        }
        if (METADATA_KEYS.contains(lk) && v.isMap()) {
            metadata(lk.contains("label") ? "label" : "annotation", v, ctx);
            return true;
        }
        if (!parent.manifest() && (lk.equals("service") || lk.equals("ingress")) && v.isMap()) {
            flattenLeaves(lk, humanize(k) + " ", v, ctx, Category.SERVICES, "service", null);
            return true;
        }
        if (parent.manifest() && lk.equals("ports") && v.isSeq() && !v.seq.isEmpty() && v.seq.stream().allMatch(YNode::isMap)) {
            for (YNode el : v.seq) {
                String name = el.str("name") != null ? el.str("name") : el.str("containerPort");
                if (name == null) name = String.valueOf(v.seq.indexOf(el));
                block("port:" + name, "Port " + name, "port", Category.SERVICES, el, ctx.child("[" + name + "]"), el.line, null);
            }
            return true;
        }
        if (lk.contains("akeyless") && (v.isMap() || v.isSeq())) {
            flattenLeaves("akeyless", "AKeyless ", v, ctx, Category.SECRETS, "akeyless", ValueSource.AKEYLESS);
            return true;
        }
        if (lk.startsWith("externalsecret") && v.isMap()) {
            flattenLeaves("externalsecrets", "External secrets ", v, ctx, Category.SECRETS, "externalsecret",
                    v.containsText("akeyless") ? ValueSource.AKEYLESS : ValueSource.EXTERNAL_SECRET);
            return true;
        }
        return false;
    }

    // ───────────────────────────── item builders ─────────────────────────────

    private void envVar(String name, YNode spec, Ctx ctx, int line, boolean secretHint) {
        ValueSource src;
        String rawValue = null;
        String ref = null;
        int valueLine = line;
        if (spec == null || spec.isNull()) {
            src = ValueSource.EMPTY;
        } else if (spec.isScalar()) {
            rawValue = spec.value;
            src = Sensitivity.detectLiteralSource(rawValue);
            valueLine = spec.line;
        } else if (spec.isMap()) {
            YNode valueNode = spec.get("value");
            YNode from = spec.get("valueFrom") != null ? spec.get("valueFrom") : spec;
            if (valueNode != null) {
                rawValue = valueNode.isScalar() ? valueNode.value : valueNode.isNull() ? null : valueNode.toFlowJson();
                src = Sensitivity.detectLiteralSource(rawValue);
                valueLine = valueNode.line;
            } else if (from.get("secretKeyRef") != null) {
                YNode s = from.get("secretKeyRef");
                ref = join(s.str("name"), s.str("key"));
                src = ValueSource.SECRET_REF;
            } else if (from.get("configMapKeyRef") != null) {
                YNode s = from.get("configMapKeyRef");
                ref = join(s.str("name"), s.str("key"));
                src = ValueSource.CONFIGMAP_REF;
            } else if (from.get("fieldRef") != null) {
                ref = from.get("fieldRef").str("fieldPath");
                src = ValueSource.FIELD_REF;
            } else if (from.get("resourceFieldRef") != null) {
                ref = from.get("resourceFieldRef").str("resource");
                src = ValueSource.FIELD_REF;
            } else {
                String akeylessKey = spec.map.keySet().stream()
                        .filter(key -> key.toLowerCase(Locale.ROOT).contains("akeyless")).findFirst().orElse(null);
                if (akeylessKey != null) {
                    YNode a = spec.get(akeylessKey);
                    ref = a.isScalar() ? a.value : a.toFlowJson();
                    src = ValueSource.AKEYLESS;
                } else {
                    rawValue = spec.toFlowJson();
                    src = Sensitivity.detectLiteralSource(rawValue);
                }
            }
        } else {
            rawValue = spec.toFlowJson();
            src = Sensitivity.detectLiteralSource(rawValue);
        }
        if (src == ValueSource.SECRET_REF && ref != null && ref.toLowerCase(Locale.ROOT).contains("akeyless")) {
            src = ValueSource.AKEYLESS;
        }
        if (src.isSecretMechanism() && ref == null) ref = rawValue;
        boolean sensitive = Sensitivity.isSensitiveName(name) || secretHint;
        variable("env:" + name, name, sensitive, src, rawValue, ref, ctx, line, valueLine);
    }

    private void secretValue(String name, YNode spec, Ctx ctx, int line, ValueSource forced, String secretName) {
        if (forced != null) {
            variable("secret:" + name, name, true, forced, null, "Secret " + secretName, ctx, line, line);
            return;
        }
        String rawValue = spec == null || spec.isNull() ? null : spec.isScalar() ? spec.value : spec.toFlowJson();
        ValueSource src = Sensitivity.detectLiteralSource(rawValue);
        String ref = src.isSecretMechanism() ? rawValue : null;
        variable("secret:" + name, name, true, src, rawValue, ref, ctx, line, spec == null ? line : spec.line);
    }

    private void variable(String key, String name, boolean sensitive, ValueSource src, String rawValue, String ref,
                          Ctx ctx, int line, int valueLine) {
        ConfigItem it = base(key, name, "variable",
                sensitive || src.isSecretMechanism() ? Category.SECRETS : Category.ENVIRONMENT, ctx, line);
        it.sensitive = sensitive;
        it.source = src;
        it.reference = ref;
        String valueDisplay;
        String valueCanonical;
        if (src == ValueSource.LITERAL && sensitive) {
            valueDisplay = Sensitivity.mask(rawValue);
            valueCanonical = Sensitivity.fingerprint(rawValue);
            markMasked(ctx.file(), valueLine);
        } else if (src == ValueSource.EMPTY) {
            valueDisplay = "(empty)";
            valueCanonical = "";
        } else if (ref != null) {
            valueDisplay = ref;
            valueCanonical = ref;
        } else {
            valueDisplay = truncate(rawValue);
            valueCanonical = rawValue == null ? "" : rawValue;
        }
        boolean plainish = src == ValueSource.LITERAL || src == ValueSource.EMPTY || src == ValueSource.TEMPLATE;
        it.display = plainish ? valueDisplay : src.label + " · " + valueDisplay;
        it.canonical = src.name() + "|" + valueCanonical;
        it.fields.put("source", src.label);
        it.fields.put("value", valueDisplay);
        if (ref != null) it.fields.put("reference", ref);
        add(it, ctx.fileKind());
    }

    private void envFrom(YNode seq, Ctx ctx) {
        Map<String, Integer> counts = new HashMap<>();
        for (YNode el : seq.seq) {
            if (!el.isMap()) continue;
            boolean secret = el.get("secretRef") != null;
            YNode ref = secret ? el.get("secretRef") : el.get("configMapRef");
            if (ref == null) continue;
            String name = ref.str("name") == null ? "?" : ref.str("name");
            String key = secret ? "envFrom:secretRef" : "envFrom:configMapRef";
            int n = counts.merge(key, 1, Integer::sum);
            if (n > 1) key += "#" + n;
            ValueSource src = secret
                    ? (name.toLowerCase(Locale.ROOT).contains("akeyless") ? ValueSource.AKEYLESS : ValueSource.SECRET_REF)
                    : ValueSource.CONFIGMAP_REF;
            ConfigItem it = base(key, secret ? "envFrom secret" : "envFrom ConfigMap", "envfrom",
                    secret ? Category.SECRETS : Category.CONFIG, ctx.child("[" + name + "]"), el.line);
            it.source = src;
            it.reference = name;
            it.display = src.label + " · " + name;
            it.canonical = src.name() + "|" + name;
            it.fields.put("source", src.label);
            it.fields.put("reference", name);
            add(it, ctx.fileKind());
        }
    }

    private void probe(String type, YNode node, Ctx ctx, int line) {
        String t = type.toLowerCase(Locale.ROOT);
        block("probe:" + t, capitalize(t) + " probe", "probe", Category.PROBES, node, ctx, line, null);
    }

    private void namedBlocks(String prefix, String subjectPrefix, YNode seq, Ctx ctx) {
        for (int i = 0; i < seq.seq.size(); i++) {
            YNode el = seq.seq.get(i);
            if (!el.isMap()) continue;
            String name = el.str("name") != null ? el.str("name") : el.str("mountPath") != null ? el.str("mountPath") : String.valueOf(i);
            block(prefix + ":" + name, subjectPrefix + name, prefix.toLowerCase(Locale.ROOT), Category.VOLUMES, el,
                    ctx.child("[" + name + "]"), el.line, null);
        }
    }

    private void metadata(String type, YNode map, Ctx ctx) {
        map.map.forEach((k, v) -> {
            String lk = k.toLowerCase(Locale.ROOT);
            if (NOISY_METADATA.contains(lk) || lk.startsWith("checksum/")) return;
            String rawValue = v.isScalar() ? v.value : v.isNull() ? "" : v.toFlowJson();
            String text = (k + " " + rawValue).toLowerCase(Locale.ROOT);
            ValueSource src = text.contains("akeyless") ? ValueSource.AKEYLESS
                    : text.contains("vault.hashicorp.com") ? ValueSource.VAULT
                    : Sensitivity.detectLiteralSource(rawValue);
            if (src == ValueSource.EMPTY) src = ValueSource.LITERAL;
            Category cat = src.isSecretMechanism() ? Category.SECRETS : Category.METADATA;
            scalarItem(type + ":" + k, (type.equals("label") ? "Label " : "Annotation ") + k, "metadata", cat,
                    rawValue, src, false, ctx.child(k), map.keyLine(k));
        });
    }

    private void leaf(Ctx ctx, String value, int line) {
        String last = ctx.lastKey();
        String lastLower = last.toLowerCase(Locale.ROOT);
        int size = ctx.path().size();
        boolean namedElement = size >= 2 && ctx.path().get(size - 2).startsWith("[");
        if (lastLower.equals("name") && namedElement) return;
        if (ctx.manifest() && (lastLower.equals("name") || lastLower.equals("namespace")) && ctx.path().contains("metadata")) return;

        String path = ctx.pathString();
        String key = (ctx.manifest() ? "cfg:" : "values:") + path;
        ValueSource src = Sensitivity.detectLiteralSource(value);
        boolean sensitive = Sensitivity.isSensitiveName(last);
        Category cat = sensitive || src.isSecretMechanism() ? Category.SECRETS
                : ctx.manifest() ? Category.WORKLOAD : Category.CONFIG;
        scalarItem(key, path, "config", cat, value, src, sensitive, ctx, line);
    }

    private void flattenLeaves(String prefix, String subjectPrefix, YNode node, Ctx ctx, Category category,
                               String family, ValueSource forced) {
        List<Leaf> leaves = new ArrayList<>();
        collectLeaves(node, "", ctx.lastKey(), leaves);
        for (Leaf l : leaves) {
            if (l.value() == null) continue;
            String key = l.path().isEmpty() ? prefix : prefix + ":" + l.path();
            ValueSource src = Sensitivity.detectLiteralSource(l.value());
            if (forced != null && !l.sensitive() && src != ValueSource.TEMPLATE) src = forced;
            Category cat = l.sensitive() || src.isSecretMechanism() ? Category.SECRETS : category;
            Ctx leafCtx = l.path().isEmpty() ? ctx : ctx.child(l.path());
            scalarItem(key, subjectPrefix + l.path(), family, cat, l.value(), src, l.sensitive(), leafCtx, l.line());
        }
    }

    private void simple(String key, String subject, String family, Category category, YNode v, Ctx ctx) {
        scalarItem(key, subject, family, category, v.value, Sensitivity.detectLiteralSource(v.value), false, ctx, v.line);
    }

    private void scalarItem(String key, String subject, String family, Category category, String rawValue,
                            ValueSource src, boolean sensitive, Ctx ctx, int line) {
        ConfigItem it = base(key, subject.strip(), family, category, ctx, line);
        it.source = src;
        it.sensitive = sensitive;
        if (sensitive && src == ValueSource.LITERAL) {
            it.display = Sensitivity.mask(rawValue);
            it.canonical = Sensitivity.fingerprint(rawValue);
            markMasked(ctx.file(), line);
        } else {
            it.display = rawValue == null || rawValue.isEmpty() ? "(empty)" : truncate(rawValue);
            it.canonical = rawValue == null ? "" : rawValue;
        }
        if (src.isSecretMechanism()) it.reference = rawValue;
        it.fields.put("value", it.display);
        add(it, ctx.fileKind());
    }

    private void block(String key, String subject, String family, Category category, YNode node, Ctx ctx, int line,
                       ValueSource forced) {
        if (node == null || node.isNull()) return;
        ConfigItem it = base(key, subject, family, category, ctx, line);
        it.block = true;
        List<Leaf> leaves = new ArrayList<>();
        collectLeaves(node, "", ctx.lastKey(), leaves);
        TreeMap<String, String> canonical = new TreeMap<>();
        for (Leaf l : leaves) {
            String path = l.path().isEmpty() ? "value" : l.path();
            String value = l.value() == null ? "null" : l.value();
            if (l.sensitive() && Sensitivity.detectLiteralSource(value) == ValueSource.LITERAL) {
                it.fields.put(path, Sensitivity.mask(value));
                canonical.put(path, Sensitivity.fingerprint(value));
                markMasked(ctx.file(), l.line());
            } else {
                it.fields.put(path, truncate(value));
                canonical.put(path, value);
            }
        }
        it.canonical = canonical.toString();
        it.enabled = !(node.isMap() && "false".equalsIgnoreCase(node.str("enabled")));
        if (forced != null) {
            it.source = forced;
        } else if (node.containsText("akeyless")) {
            it.source = ValueSource.AKEYLESS;
        } else if ("volume".equals(family) && node.has("secret")) {
            it.source = ValueSource.SECRET_REF;
        } else if ("volume".equals(family) && node.containsText("secrets-store")) {
            it.source = ValueSource.EXTERNAL_SECRET;
        } else {
            it.source = ValueSource.BLOCK;
        }
        if (it.source.isSecretMechanism()) it.reference = it.source.label;
        String summary = switch (family) {
            case "probe" -> probeSummary(node);
            case "tsc" -> tscSummary(node);
            default -> it.fields.size() + " setting(s)";
        };
        it.display = it.enabled ? summary : "Disabled · " + summary;
        add(it, ctx.fileKind());
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static void collectLeaves(YNode n, String path, String lastKey, List<Leaf> out) {
        if (n == null) return;
        switch (n.kind) {
            case SCALAR -> out.add(new Leaf(path, n.value, n.line, Sensitivity.isSensitiveName(lastKey)));
            case NULL -> out.add(new Leaf(path, null, n.line, false));
            case MAP -> n.map.forEach((k, v) -> collectLeaves(v, path.isEmpty() ? k : path + "." + k, k, out));
            case SEQ -> {
                if (n.seq.stream().allMatch(c -> c.isScalar() || c.isNull())) {
                    out.add(new Leaf(path, joinScalars(n), n.line, Sensitivity.isSensitiveName(lastKey)));
                    return;
                }
                for (int i = 0; i < n.seq.size(); i++) {
                    YNode c = n.seq.get(i);
                    String id = c.isMap() ? firstNonNull(c.str("name"), c.str("topologyKey"), c.str("key"),
                            c.str("containerPort"), c.str("port"), c.str("mountPath")) : null;
                    collectLeaves(c, path + "[" + (id != null ? id : String.valueOf(i)) + "]", lastKey, out);
                }
            }
        }
    }

    private void putField(ConfigItem it, String path, String value, boolean sensitive, String file, int line) {
        if (value == null) return;
        if (sensitive && Sensitivity.detectLiteralSource(value) == ValueSource.LITERAL) {
            it.fields.put(path, Sensitivity.mask(value) + " " + Sensitivity.fingerprint(value));
            markMasked(file, line);
        } else {
            it.fields.put(path, truncate(value));
        }
    }

    private ConfigItem base(String key, String subject, String family, Category category, Ctx ctx, int line) {
        ConfigItem it = new ConfigItem();
        it.key = key;
        it.subject = subject;
        it.family = family;
        it.category = category;
        it.scope = ctx.scope();
        it.locations.add(new ConfigItem.Location(ctx.file(), line, ctx.pathString()));
        return it;
    }

    private void add(ConfigItem it, FileKind kind) {
        raw.add(it);
        kinds.put(it, kind);
    }

    private void markMasked(String file, int line) {
        masked.computeIfAbsent(file, f -> new TreeSet<>()).add(line);
    }

    private void applySecretBackings() {
        for (ConfigItem it : raw) {
            if (it.reference == null || (it.source != ValueSource.SECRET_REF)) continue;
            int slash = it.reference.indexOf('/');
            String secretName = slash < 0 ? it.reference : it.reference.substring(0, slash);
            ValueSource backing = secretBackings.get(secretName);
            if (backing == null || backing == ValueSource.SECRET_REF) continue;
            it.source = backing;
            it.display = backing.label + " · " + it.reference;
            it.canonical = backing.name() + "|" + it.reference;
            it.fields.put("source", backing.label);
            it.fields.put("backedBy", "Secret " + secretName + " is managed by " + backing.label);
            it.category = Category.SECRETS;
        }
    }

    /**
     * The same logical key can appear more than once (values.yaml and the rendering template, or two
     * containers). Equivalent occurrences are merged; rendered templates win over values; genuinely
     * different occurrences are kept apart by scope.
     */
    private List<ConfigItem> mergeDuplicates() {
        Map<String, ConfigItem> byKey = new LinkedHashMap<>();
        for (ConfigItem it : raw) {
            ConfigItem existing = byKey.get(it.key);
            if (existing == null) {
                byKey.put(it.key, it);
                continue;
            }
            if (Objects.equals(existing.canonical, it.canonical) && existing.enabled == it.enabled) {
                existing.locations.addAll(it.locations);
                continue;
            }
            boolean itTemplate = it.source == ValueSource.TEMPLATE;
            boolean existingTemplate = existing.source == ValueSource.TEMPLATE;
            FileKind ek = kinds.get(existing);
            FileKind ik = kinds.get(it);
            if ((existingTemplate && !itTemplate) || (ek == FileKind.VALUES && ik != FileKind.VALUES && !itTemplate)) {
                it.locations.addAll(existing.locations);
                byKey.put(it.key, it);
                continue;
            }
            if (itTemplate || (ik == FileKind.VALUES && ek != FileKind.VALUES)) {
                existing.locations.addAll(it.locations);
                continue;
            }
            String suffix = it.scope != null ? it.scope : ChartParser.baseName(it.locations.get(0).file);
            String candidate = it.key + "@" + suffix;
            int n = 2;
            while (byKey.containsKey(candidate)) candidate = it.key + "@" + suffix + "#" + n++;
            it.key = candidate;
            it.subject = it.subject + " (" + suffix + ")";
            byKey.put(candidate, it);
        }
        return new ArrayList<>(byKey.values());
    }

    private static boolean envLike(YNode map) {
        if (map.map.isEmpty()) return false;
        long envNames = map.map.keySet().stream().filter(k -> k.matches("^[A-Z_][A-Z0-9_]*$")).count();
        return envNames * 10 >= map.map.size() * 6L;
    }

    private static boolean looksLikeProperties(String raw) {
        String[] lines = raw.split("\n");
        long props = java.util.Arrays.stream(lines).filter(l -> l.matches("^\\s*[\\w.\\-]+\\s*=.*")).count();
        return lines.length >= 2 && props * 10 >= lines.length * 6L;
    }

    private static String probeSummary(YNode node) {
        if (!node.isMap()) return "Configured";
        List<String> parts = new ArrayList<>();
        YNode http = node.get("httpGet");
        YNode tcp = node.get("tcpSocket");
        YNode exec = node.get("exec");
        YNode grpc = node.get("grpc");
        if (http != null && http.isMap()) {
            parts.add("HTTP GET " + nvl(http.str("path"), "/") + (http.str("port") != null ? " :" + http.str("port") : ""));
        } else if (tcp != null && tcp.isMap()) {
            parts.add("TCP :" + nvl(tcp.str("port"), "?"));
        } else if (exec != null && exec.isMap()) {
            YNode cmd = exec.get("command");
            parts.add("exec " + (cmd != null && cmd.isSeq() ? truncate(joinScalars(cmd)) : ""));
        } else if (grpc != null && grpc.isMap()) {
            parts.add("gRPC :" + nvl(grpc.str("port"), "?"));
        }
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("initialDelaySeconds", "delay %ss");
        labels.put("periodSeconds", "period %ss");
        labels.put("timeoutSeconds", "timeout %ss");
        labels.put("failureThreshold", "failures %s");
        labels.put("successThreshold", "success %s");
        labels.forEach((k, fmt) -> {
            if (node.str(k) != null) parts.add(String.format(fmt, node.str(k)));
        });
        return parts.isEmpty() ? node.map.size() + " setting(s)" : String.join(" · ", parts);
    }

    private static String tscSummary(YNode node) {
        List<YNode> constraints = node.isSeq() ? node.seq : List.of(node);
        List<String> keys = new ArrayList<>();
        for (YNode c : constraints) {
            if (!c.isMap()) continue;
            String key = c.str("topologyKey");
            String skew = c.str("maxSkew");
            if (key != null) keys.add(key.replace("topology.kubernetes.io/", "") + (skew != null ? " (skew " + skew + ")" : ""));
        }
        if (keys.isEmpty()) return node.isMap() && "false".equalsIgnoreCase(node.str("enabled")) ? "Configured" : "Configured";
        return constraints.size() + " constraint(s): " + String.join(", ", keys);
    }

    private static String joinScalars(YNode seq) {
        List<String> values = new ArrayList<>();
        for (YNode c : seq.seq) values.add(c.isNull() ? "null" : c.value);
        return "[" + String.join(", ", values) + "]";
    }

    private static String join(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + "/" + b;
    }

    private static String firstNonNull(String... values) {
        for (String v : values) if (v != null) return v;
        return null;
    }

    private static String nvl(String v, String fallback) {
        return v == null ? fallback : v;
    }

    private static String truncate(String v) {
        if (v == null) return "";
        String single = v.replace("\n", "⏎ ");
        return single.length() > 160 ? single.substring(0, 157) + "…" : single;
    }

    private static String firstLine(String v) {
        int nl = v.indexOf('\n');
        return truncate(nl < 0 ? v : v.substring(0, nl));
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String humanize(String key) {
        String spaced = key.replaceAll("([a-z])([A-Z])", "$1 $2");
        return capitalize(spaced.toLowerCase(Locale.ROOT));
    }
}
