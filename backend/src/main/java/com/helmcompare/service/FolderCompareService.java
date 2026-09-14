package com.helmcompare.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmcompare.diff.AkeylessValues;
import com.helmcompare.diff.EnvVarExtractor;
import com.helmcompare.diff.LogicalFileComparer;
import com.helmcompare.diff.MultiEnvComparer;
import com.helmcompare.diff.MultiFileComparer;
import com.helmcompare.model.FolderCompare;
import com.helmcompare.model.FolderCompare.Node;
import com.helmcompare.model.FolderCompare.PairResult;
import com.helmcompare.model.SecretValues;
import com.helmcompare.parse.ChartArchive;
import com.helmcompare.store.FolderCompareStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Compares two or three parent folders. Sub-folders with the same name are matched, then every file inside is compared
 * logically (see {@link LogicalFileComparer}) for every pair of folders, so any folder can be hidden later.
 */
@Service
public class FolderCompareService {

    private static final Set<String> SKIPPED_DIRS = Set.of("node_modules", "target", "__MACOSX");
    private static final Set<String> SKIPPED_FILES = Set.of(".DS_Store", "Thumbs.db", "desktop.ini");
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private static final String[] ORDINALS = {"First", "Second", "Third"};

    private final FolderCompareStore store;
    private final ObjectMapper mapper;

    public FolderCompareService(FolderCompareStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    public record SideFile(String name, String label, String state, List<String> lines) {
    }

    /** Comparison of the file for one set of sides. */
    public record Comparison(String status, String reason, List<MultiFileComparer.SideDiff> differences) {
    }

    /** @param comparisons key: the compared sides joined by "-" ("0-1", "0-2", "1-2", "0-1-2") */
    public record FileView(String path, String name, boolean binary, List<SideFile> sides, Map<String, Comparison> comparisons) {
    }

    private record Side(String rootName, Map<String, byte[]> files) {
    }

    /**
     * @param uploads files of each chosen folder (empty entries are skipped; at least two are required)
     * @param labels  optional label per folder
     * @param secrets optional JSON with the AKeyless values of each folder's environment
     */
    public FolderCompare.Info create(List<List<MultipartFile>> uploads, List<String> labels, List<List<MultipartFile>> secrets) {
        List<Integer> chosen = new ArrayList<>();
        for (int i = 0; i < uploads.size(); i++) if (uploads.get(i) != null && !uploads.get(i).isEmpty()) chosen.add(i);
        if (chosen.size() < 2) throw new IllegalArgumentException("Choose at least two folders.");
        if (chosen.size() > FolderCompare.MAX_SIDES) {
            throw new IllegalArgumentException("At most " + FolderCompare.MAX_SIDES + " folders can be compared.");
        }

        List<SecretValues> values = new ArrayList<>();
        List<Side> sides = new ArrayList<>();
        FolderCompare c = new FolderCompare();
        for (int k = 0; k < chosen.size(); k++) {
            int i = chosen.get(k);
            values.add(parseSecretValues(i < secrets.size() ? secrets.get(i) : null));
            Side side = side(uploads.get(i), ORDINALS[k] + " folder");
            if (side.files().isEmpty()) {
                throw new IllegalArgumentException(side.rootName() + " does not contain any files that can be compared.");
            }
            sides.add(side);
            c.sides.add(new FolderCompare.SideInfo(side.rootName(), blankToNull(i < labels.size() ? labels.get(i) : null)));
        }

        c.id = UUID.randomUUID().toString().substring(0, 8);
        c.createdAt = Instant.now();
        c.root = buildTree(sides.stream().map(Side::files).toList(), FolderStatus.titles(c));
        FolderStatus.summarize(c);
        store.save(c, sides.stream().map(Side::files).toList());
        for (int i = 0; i < values.size(); i++) if (values.get(i) != null) store.saveSecretValues(c.id, i, values.get(i));
        return FolderCompare.Info.of(c);
    }

    public FolderCompare get(String id) {
        return store.get(id).orElseThrow(() -> new NotFoundException("Comparison not found: " + id));
    }

    public List<FolderCompare.Info> list() {
        return store.list();
    }

    public void delete(String id) {
        get(id);
        store.delete(id);
    }

    /**
     * @param sides "0,2" — the sides to compare; null, blank or fewer than two existing sides mean all. Sides this
     *              comparison does not have are ignored (a browser may remember three sides for any comparison).
     */
    public int[] parseSides(FolderCompare c, String sides) {
        int n = c.sides.size();
        if (sides == null || sides.isBlank()) return FolderStatus.all(n);
        TreeSet<Integer> set = new TreeSet<>();
        for (String part : sides.split(",")) {
            if (part.isBlank()) continue;
            try {
                int s = Integer.parseInt(part.strip());
                if (s >= 0 && s < n) set.add(s);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("sides must be side numbers such as 0,2");
            }
        }
        if (set.size() < 2) return FolderStatus.all(n);
        return set.stream().mapToInt(Integer::intValue).toArray();
    }

    public static String key(int[] sides) {
        return Arrays.stream(sides).mapToObj(String::valueOf).collect(Collectors.joining("-"));
    }

    public FileView file(String id, String path) {
        FolderCompare c = get(id);
        int n = c.sides.size();
        List<String> titles = FolderStatus.titles(c);
        byte[][] bytes = new byte[n][];
        boolean any = false;
        boolean binary = false;
        for (int i = 0; i < n; i++) {
            bytes[i] = store.file(id, i, path).orElse(null);
            any |= bytes[i] != null;
            binary |= bytes[i] != null && LogicalFileComparer.isBinary(bytes[i]);
        }
        if (!any) throw new NotFoundException("File not found: " + path);

        List<SideFile> sides = new ArrayList<>();
        boolean[] exists = new boolean[n];
        String[] states = new String[n];
        for (int i = 0; i < n; i++) {
            exists[i] = bytes[i] != null;
            states[i] = state(bytes[i]);
            sides.add(new SideFile(c.sides.get(i).name, c.sides.get(i).label, states[i], lines(bytes[i], binary)));
        }

        Map<String, LogicalFileComparer.Result> pairs = new HashMap<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (exists[i] && exists[j]) pairs.put(i + "-" + j, LogicalFileComparer.compare(path, bytes[i], bytes[j]));
            }
        }

        Map<String, Comparison> comparisons = new LinkedHashMap<>();
        for (int[] set : subsets(n)) {
            int[] present = Arrays.stream(set).filter(s -> exists[s]).toArray();
            List<MultiFileComparer.SideDiff> diffs = List.of();
            if (present.length == 2) {
                diffs = MultiFileComparer.pair(n, present[0], present[1], pairs.get(present[0] + "-" + present[1]).diffs());
            } else if (present.length == 3) {
                diffs = MultiFileComparer.merge(n, present[0], present[1], present[2], pairs.get(present[0] + "-" + present[1]).diffs(),
                        pairs.get(present[0] + "-" + present[2]).diffs(), pairs.get(present[1] + "-" + present[2]).diffs());
            }
            String missing = FolderStatus.missingReason(exists, set, titles);
            Comparison comparison;
            if (missing != null) {
                comparison = new Comparison(FolderStatus.PARTIAL, missing, diffs);
            } else if (set.length == 2) {
                LogicalFileComparer.Result r = pairs.get(set[0] + "-" + set[1]);
                comparison = new Comparison(r.status().name(), emptyReason(states, set, titles, r.reason()), diffs);
            } else {
                List<LogicalFileComparer.Result> all = List.of(pairs.get(set[0] + "-" + set[1]), pairs.get(set[0] + "-" + set[2]),
                        pairs.get(set[1] + "-" + set[2]));
                if (all.stream().allMatch(r -> r.status() == LogicalFileComparer.Status.IDENTICAL)) {
                    comparison = new Comparison(FolderStatus.IDENTICAL, "Files are identical in every folder", diffs);
                } else if (all.stream().noneMatch(r -> r.status() == LogicalFileComparer.Status.DIFFERS)) {
                    comparison = new Comparison(FolderStatus.LOGICALLY_IDENTICAL,
                            "Logically identical — only ordering, spacing, quoting or comments differ", diffs);
                } else {
                    String reason = diffs.isEmpty()
                            ? all.stream().filter(r -> r.status() == LogicalFileComparer.Status.DIFFERS).findFirst().orElseThrow().reason()
                            : diffs.size() + " logical difference(s)";
                    comparison = new Comparison(FolderStatus.DIFFERS, emptyReason(states, set, titles, reason), diffs);
                }
            }
            comparisons.put(key(set), comparison);
        }
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        return new FileView(path, name, binary, sides, comparisons);
    }

    /** Every set of at least two sides. */
    private static List<int[]> subsets(int n) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) for (int j = i + 1; j < n; j++) out.add(new int[]{i, j});
        if (n == 3) out.add(new int[]{0, 1, 2});
        return out;
    }

    // ───────────────────────────── environment variables ─────────────────────────────

    /**
     * @param shown the compared sides; lists in rows are indexed by all sides of the comparison
     */
    public record EnvView(String path, String scope, String scopePath, boolean pathIsFile, List<String> files,
                          List<FolderCompare.SideInfo> sides, List<Integer> shown,
                          List<MultiEnvComparer.Row> rows, MultiEnvComparer.Summary summary, SecretsSummary secrets) {
    }

    /** An AKeyless path referenced by the charts whose value is not (yet) in the uploaded JSON. */
    public record MissingPath(int side, String variable, String path, String file, int line) {
    }

    /** AKeyless values uploaded for one environment (side). */
    public record SideSecrets(int paths, List<String> files, Instant updatedAt) {
        static SideSecrets of(SecretValues v) {
            return v == null ? new SideSecrets(0, List.of(), null) : new SideSecrets(v.values.size(), v.files, v.updatedAt);
        }
    }

    /**
     * @param sides      uploaded values per side (all sides)
     * @param referenced AKeyless references of the compared variables
     * @param resolved   references whose value was found in the JSON of their side
     */
    public record SecretsSummary(List<SideSecrets> sides, int referenced, int resolved, List<MissingPath> missing) {
    }

    public record SecretValuesInfo(List<SideSecrets> sides) {
    }

    public SecretValuesInfo secretValuesInfo(String id) {
        FolderCompare c = get(id);
        List<SideSecrets> sides = new ArrayList<>();
        for (int i = 0; i < c.sides.size(); i++) sides.add(SideSecrets.of(store.secretValues(id, i).orElse(null)));
        return new SecretValuesInfo(sides);
    }

    /**
     * Stores JSON files (AKeyless path → value) for one environment; several files are merged unless {@code replace}.
     *
     * @param side a side number, or "all"
     */
    public SecretValuesInfo uploadSecretValues(String id, String side, List<MultipartFile> files, boolean replace) {
        FolderCompare c = get(id);
        SecretValues parsed = parseSecretValues(files);
        if (parsed == null) throw new IllegalArgumentException("Choose a JSON file with AKeyless values.");
        for (int s : secretSides(c, side)) {
            SecretValues values = replace ? new SecretValues() : store.secretValues(id, s).orElseGet(SecretValues::new);
            values.values.putAll(parsed.values);
            for (String f : parsed.files) {
                values.files.remove(f);
                values.files.add(f);
            }
            values.updatedAt = parsed.updatedAt;
            store.saveSecretValues(id, s, values);
        }
        return secretValuesInfo(id);
    }

    public void clearSecretValues(String id, String side) {
        FolderCompare c = get(id);
        for (int s : secretSides(c, side)) store.deleteSecretValues(id, s);
    }

    private static int[] secretSides(FolderCompare c, String side) {
        String s = side == null ? "all" : side.strip().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "all", "both" -> FolderStatus.all(c.sides.size());
            case "left" -> new int[]{0};
            case "right" -> new int[]{1};
            default -> {
                try {
                    int i = Integer.parseInt(s);
                    if (i < 0 || i >= c.sides.size()) throw new IllegalArgumentException("Unknown side: " + side);
                    yield new int[]{i};
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("side must be a side number or all");
                }
            }
        };
    }

    /** @return the merged values, or null when no non-empty file was uploaded */
    private SecretValues parseSecretValues(List<MultipartFile> files) {
        if (files == null) return null;
        SecretValues values = new SecretValues();
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            String name = f.getOriginalFilename() == null ? "values.json" : f.getOriginalFilename();
            Map<String, String> parsed;
            try {
                parsed = AkeylessValues.parse(mapper.readTree(f.getBytes()));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(name + " is not valid JSON: " + e.getOriginalMessage());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (parsed.isEmpty()) throw new IllegalArgumentException(name + " does not contain any path/value pairs.");
            values.values.putAll(parsed);
            values.files.remove(name);
            values.files.add(name);
        }
        if (values.files.isEmpty()) return null;
        values.updatedAt = Instant.now();
        return values;
    }

    /**
     * Environment variables and secrets of a file, or of the whole microservice folder containing it.
     *
     * @param scope FILE or FOLDER
     * @param sides the sides to compare ("0,2"); null for all
     */
    public EnvView envVars(String id, String path, String scope, String sides) {
        FolderCompare c = get(id);
        int[] shown = parseSides(c, sides);
        Node node = find(c.root, path);
        if (node == null) throw new NotFoundException("Not found: " + path);
        boolean folderScope = "FOLDER".equalsIgnoreCase(scope) || node.dir;
        String scopePath = !folderScope ? path : node.dir ? path : (path.contains("/") ? path.substring(0, path.indexOf('/')) : "");
        Node scopeNode = scopePath.isEmpty() ? c.root : find(c.root, scopePath);
        List<String> files = new ArrayList<>();
        if (!folderScope) files.add(path);
        else collectFiles(scopeNode, files);

        int n = c.sides.size();
        List<List<EnvVarExtractor.EnvVar>> vars = new ArrayList<>();
        List<SideSecrets> uploaded = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int side = i;
            SecretValues values = store.secretValues(id, i).orElse(null);
            uploaded.add(SideSecrets.of(values));
            if (Arrays.stream(shown).noneMatch(s -> s == side)) {
                vars.add(List.of());
                continue;
            }
            List<EnvVarExtractor.EnvVar> extracted = new ArrayList<>();
            for (String f : files) extracted.addAll(EnvVarExtractor.extract(f, store.file(id, i, f).orElse(null)));
            // each environment has its own AKeyless values
            vars.add(new AkeylessValues(values == null ? null : values.values).apply(EnvVarExtractor.resolve(extracted)));
        }
        MultiEnvComparer.Result result = MultiEnvComparer.compare(shown, vars);

        int referenced = 0;
        int resolved = 0;
        List<MissingPath> missing = new ArrayList<>();
        for (int s : shown) {
            for (EnvVarExtractor.EnvVar v : vars.get(s)) {
                if (v.akeylessPath() == null || EnvVarExtractor.STATE_UNKNOWN.equals(v.valueState())) continue;
                referenced++;
                if (EnvVarExtractor.STATE_RESOLVED.equals(v.valueState())) resolved++;
                else missing.add(new MissingPath(s, v.name(), v.akeylessPath(), v.file(), v.line()));
            }
        }
        return new EnvView(path, folderScope ? "FOLDER" : "FILE", scopePath, !node.dir, files, c.sides,
                Arrays.stream(shown).boxed().toList(), result.rows(), result.summary(),
                new SecretsSummary(uploaded, referenced, resolved, missing));
    }

    private static Node find(Node root, String path) {
        if (path == null || path.isEmpty()) return root;
        Node current = root;
        StringBuilder prefix = new StringBuilder();
        for (String segment : path.split("/")) {
            if (!prefix.isEmpty()) prefix.append('/');
            prefix.append(segment);
            if (current.children == null) return null;
            String target = prefix.toString();
            current = current.children.stream().filter(ch -> ch.path.equals(target)).findFirst().orElse(null);
            if (current == null) return null;
        }
        return current;
    }

    private static void collectFiles(Node node, List<String> out) {
        if (node == null) return;
        if (!node.dir) {
            out.add(node.path);
            return;
        }
        node.children.forEach(ch -> collectFiles(ch, out));
    }

    // ───────────────────────────── tree ─────────────────────────────

    private Node buildTree(List<Map<String, byte[]>> sides, List<String> titles) {
        Node root = dirNode("", "");
        Map<String, Node> dirs = new LinkedHashMap<>();
        dirs.put("", root);
        TreeSet<String> paths = new TreeSet<>();
        sides.forEach(s -> paths.addAll(s.keySet()));
        for (String path : paths) {
            String[] segments = path.split("/");
            String parentPath = "";
            Node parent = root;
            for (int i = 0; i < segments.length - 1; i++) {
                String dirPath = parentPath.isEmpty() ? segments[i] : parentPath + "/" + segments[i];
                Node p = parent;
                String name = segments[i];
                parent = dirs.computeIfAbsent(dirPath, k -> {
                    Node d = dirNode(name, k);
                    p.children.add(d);
                    return d;
                });
                parentPath = dirPath;
            }
            List<byte[]> bytes = sides.stream().map(s -> s.get(path)).toList();
            parent.children.add(fileNode(segments[segments.length - 1], path, bytes, titles));
        }
        aggregate(root, sides.size());
        return root;
    }

    private static Node dirNode(String name, String path) {
        Node n = new Node();
        n.name = name;
        n.path = path;
        n.dir = true;
        n.children = new ArrayList<>();
        return n;
    }

    private static Node fileNode(String name, String path, List<byte[]> bytes, List<String> titles) {
        Node n = new Node();
        n.name = name;
        n.path = path;
        n.states = new ArrayList<>();
        n.sizes = new ArrayList<>();
        n.pairs = Node.newPairs();
        String[] states = new String[bytes.size()];
        for (int i = 0; i < bytes.size(); i++) {
            byte[] b = bytes.get(i);
            states[i] = state(b);
            n.states.add(states[i]);
            n.sizes.add(b == null ? -1L : b.length);
        }
        for (int i = 0; i < bytes.size(); i++) {
            for (int j = i + 1; j < bytes.size(); j++) {
                if (bytes.get(i) == null || bytes.get(j) == null) continue;
                LogicalFileComparer.Result r = LogicalFileComparer.compare(path, bytes.get(i), bytes.get(j));
                n.pairs.put(i + "-" + j, new PairResult(r.status().name(), emptyReason(states, new int[]{i, j}, titles, r.reason()),
                        r.diffs().size()));
            }
        }
        return n;
    }

    private static void aggregate(Node dir, int sides) {
        boolean[] present = new boolean[sides];
        for (Node child : dir.children) {
            if (child.dir) aggregate(child, sides);
            for (int i = 0; i < sides; i++) present[i] |= child.present(i);
        }
        dir.children.sort(Comparator.comparing((Node n) -> !n.dir).thenComparing(n -> n.name, String.CASE_INSENSITIVE_ORDER));
        dir.states = new ArrayList<>();
        for (boolean p : present) dir.states.add(p ? "PRESENT" : "MISSING");
    }

    // ───────────────────────────── uploads ─────────────────────────────

    /** Uploaded file names carry the relative path "ParentFolder/service/templates/x.yaml". */
    private static Side side(List<MultipartFile> uploads, String fallbackName) {
        Map<String, byte[]> raw = new TreeMap<>();
        for (MultipartFile f : uploads) {
            String path = ChartArchive.normalizePath(f.getOriginalFilename() == null ? "" : f.getOriginalFilename());
            if (path.isEmpty() || skipped(path) || f.getSize() > MAX_FILE_BYTES) continue;
            try {
                raw.put(path, f.getBytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        if (raw.isEmpty()) return new Side(fallbackName, raw);
        String first = raw.keySet().iterator().next();
        int slash = first.indexOf('/');
        if (slash > 0) {
            String rootName = first.substring(0, slash);
            boolean shared = raw.keySet().stream().allMatch(p -> p.startsWith(rootName + "/"));
            if (shared) {
                Map<String, byte[]> stripped = new TreeMap<>();
                raw.forEach((p, b) -> stripped.put(p.substring(rootName.length() + 1), b));
                return new Side(rootName, stripped);
            }
        }
        return new Side(fallbackName, raw);
    }

    private static boolean skipped(String path) {
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (segments[i].startsWith(".") || SKIPPED_DIRS.contains(segments[i])) return true;
        }
        return SKIPPED_FILES.contains(segments[segments.length - 1]);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static String state(byte[] bytes) {
        if (bytes == null) return "MISSING";
        return LogicalFileComparer.isBlank(bytes) ? "EMPTY" : "PRESENT";
    }

    private static String emptyReason(String[] states, int[] sides, List<String> titles, String fallback) {
        List<String> empty = Arrays.stream(sides).filter(s -> "EMPTY".equals(states[s])).mapToObj(titles::get).toList();
        if (empty.isEmpty()) return fallback;
        if (empty.size() == sides.length) return "The files are empty in every folder";
        return "The " + String.join(", ", empty) + " file is empty";
    }

    private static List<String> lines(byte[] bytes, boolean binary) {
        if (bytes == null || binary) return null;
        String text = LogicalFileComparer.text(bytes);
        List<String> lines = new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        if (lines.size() > 1 && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        return lines;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
