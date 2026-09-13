package com.helmcompare.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmcompare.diff.AkeylessValues;
import com.helmcompare.diff.EnvVarComparer;
import com.helmcompare.diff.EnvVarExtractor;
import com.helmcompare.diff.LogicalFileComparer;
import com.helmcompare.model.FolderCompare;
import com.helmcompare.model.FolderCompare.Node;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Compares two parent folders. Sub-folders with the same name are matched, then every file inside
 * is compared logically (see {@link LogicalFileComparer}).
 */
@Service
public class FolderCompareService {

    private static final Set<String> SKIPPED_DIRS = Set.of("node_modules", "target", "__MACOSX");
    private static final Set<String> SKIPPED_FILES = Set.of(".DS_Store", "Thumbs.db", "desktop.ini");
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;

    private final FolderCompareStore store;
    private final ObjectMapper mapper;

    public FolderCompareService(FolderCompareStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    public record FileView(String path, String name, String status, String reason, String leftState, String rightState,
                           boolean binary, List<String> leftLines, List<String> rightLines,
                           List<LogicalFileComparer.Diff> differences,
                           String leftName, String rightName, String leftLabel, String rightLabel) {
    }

    private record Side(String rootName, Map<String, byte[]> files) {
    }

    /** @param leftSecrets optional JSON with the AKeyless values of the left environment (same for right) */
    public FolderCompare.Info create(List<MultipartFile> left, List<MultipartFile> right, String leftLabel, String rightLabel,
                                     List<MultipartFile> leftSecrets, List<MultipartFile> rightSecrets) {
        if (left == null || left.isEmpty() || right == null || right.isEmpty()) {
            throw new IllegalArgumentException("Choose both a left and a right folder.");
        }
        SecretValues leftValues = parseSecretValues(leftSecrets);
        SecretValues rightValues = parseSecretValues(rightSecrets);
        Side l = side(left, "Left folder");
        Side r = side(right, "Right folder");
        if (l.files().isEmpty() || r.files().isEmpty()) {
            throw new IllegalArgumentException("One of the folders does not contain any files that can be compared.");
        }

        FolderCompare c = new FolderCompare();
        c.id = UUID.randomUUID().toString().substring(0, 8);
        c.createdAt = Instant.now();
        c.leftName = l.rootName();
        c.rightName = r.rootName();
        c.leftLabel = blankToNull(leftLabel);
        c.rightLabel = blankToNull(rightLabel);
        c.root = buildTree(l.files(), r.files());
        summarize(c);
        store.save(c, l.files(), r.files());
        if (leftValues != null) store.saveSecretValues(c.id, "left", leftValues);
        if (rightValues != null) store.saveSecretValues(c.id, "right", rightValues);
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

    public FileView file(String id, String path) {
        FolderCompare c = get(id);
        byte[] lb = store.file(id, "left", path).orElse(null);
        byte[] rb = store.file(id, "right", path).orElse(null);
        if (lb == null && rb == null) throw new NotFoundException("File not found: " + path);

        String leftState = state(lb);
        String rightState = state(rb);
        boolean binary = (lb != null && LogicalFileComparer.isBinary(lb)) || (rb != null && LogicalFileComparer.isBinary(rb));
        List<LogicalFileComparer.Diff> diffs = List.of();
        String status;
        String reason;
        if (lb == null) {
            status = "RIGHT_ONLY";
            reason = "The file does not exist in the left folder";
        } else if (rb == null) {
            status = "LEFT_ONLY";
            reason = "The file does not exist in the right folder";
        } else {
            LogicalFileComparer.Result result = LogicalFileComparer.compare(path, lb, rb);
            status = result.status().name();
            reason = emptyReason(leftState, rightState, result.reason());
            diffs = result.diffs();
        }
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        return new FileView(path, name, status, reason, leftState, rightState, binary,
                lines(lb, binary), lines(rb, binary), diffs, c.leftName, c.rightName, c.leftLabel, c.rightLabel);
    }

    public record EnvView(String path, String scope, String scopePath, boolean pathIsFile, List<String> files,
                          String leftName, String rightName, String leftLabel, String rightLabel,
                          List<EnvVarComparer.Row> rows, EnvVarComparer.Summary summary, SecretsSummary secrets) {
    }

    /** An AKeyless path referenced by the charts whose value is not (yet) in the uploaded JSON. */
    public record MissingPath(String side, String variable, String path, String file, int line) {
    }

    /** AKeyless values uploaded for one environment (side). */
    public record SideSecrets(int paths, List<String> files, Instant updatedAt) {
        static SideSecrets of(SecretValues v) {
            return v == null ? new SideSecrets(0, List.of(), null) : new SideSecrets(v.values.size(), v.files, v.updatedAt);
        }
    }

    /**
     * @param referenced AKeyless references of the compared variables (both sides)
     * @param resolved   references whose value was found in the JSON of their side
     */
    public record SecretsSummary(SideSecrets left, SideSecrets right, int referenced, int resolved, List<MissingPath> missing) {
    }

    public record SecretValuesInfo(SideSecrets left, SideSecrets right) {
    }

    public SecretValuesInfo secretValuesInfo(String id) {
        get(id);
        return new SecretValuesInfo(SideSecrets.of(store.secretValues(id, "left").orElse(null)),
                SideSecrets.of(store.secretValues(id, "right").orElse(null)));
    }

    /**
     * Stores JSON files (AKeyless path → value) for one environment; several files are merged unless {@code replace}.
     *
     * @param side left, right or both
     */
    public SecretValuesInfo uploadSecretValues(String id, String side, List<MultipartFile> files, boolean replace) {
        get(id);
        SecretValues parsed = parseSecretValues(files);
        if (parsed == null) throw new IllegalArgumentException("Choose a JSON file with AKeyless values.");
        for (String s : sides(side)) {
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
        get(id);
        for (String s : sides(side)) store.deleteSecretValues(id, s);
    }

    private static List<String> sides(String side) {
        return switch (side == null ? "both" : side.toLowerCase(Locale.ROOT)) {
            case "left" -> List.of("left");
            case "right" -> List.of("right");
            case "both" -> List.of("left", "right");
            default -> throw new IllegalArgumentException("side must be left, right or both");
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
     */
    public EnvView envVars(String id, String path, String scope) {
        FolderCompare c = get(id);
        Node node = find(c.root, path);
        if (node == null) throw new NotFoundException("Not found: " + path);
        boolean folderScope = "FOLDER".equalsIgnoreCase(scope) || node.dir;
        String scopePath = !folderScope ? path : node.dir ? path : (path.contains("/") ? path.substring(0, path.indexOf('/')) : "");
        Node scopeNode = scopePath.isEmpty() ? c.root : find(c.root, scopePath);
        List<String> files = new ArrayList<>();
        if (!folderScope) files.add(path);
        else collectFiles(scopeNode, files);

        List<EnvVarExtractor.EnvVar> left = new ArrayList<>();
        List<EnvVarExtractor.EnvVar> right = new ArrayList<>();
        for (String f : files) {
            left.addAll(EnvVarExtractor.extract(f, store.file(id, "left", f).orElse(null)));
            right.addAll(EnvVarExtractor.extract(f, store.file(id, "right", f).orElse(null)));
        }
        // each environment has its own AKeyless values
        SecretValues leftUploaded = store.secretValues(id, "left").orElse(null);
        SecretValues rightUploaded = store.secretValues(id, "right").orElse(null);
        List<EnvVarExtractor.EnvVar> l = new AkeylessValues(leftUploaded == null ? null : leftUploaded.values)
                .apply(EnvVarExtractor.resolve(left));
        List<EnvVarExtractor.EnvVar> r = new AkeylessValues(rightUploaded == null ? null : rightUploaded.values)
                .apply(EnvVarExtractor.resolve(right));
        EnvVarComparer.Result result = EnvVarComparer.compare(l, r);

        int referenced = 0;
        int resolved = 0;
        List<MissingPath> missing = new ArrayList<>();
        for (List<EnvVarExtractor.EnvVar> side : List.of(l, r)) {
            for (EnvVarExtractor.EnvVar v : side) {
                if (v.akeylessPath() == null || EnvVarExtractor.STATE_UNKNOWN.equals(v.valueState())) continue;
                referenced++;
                if (EnvVarExtractor.STATE_RESOLVED.equals(v.valueState())) resolved++;
                else missing.add(new MissingPath(side == l ? "LEFT" : "RIGHT", v.name(), v.akeylessPath(), v.file(), v.line()));
            }
        }
        SecretsSummary secrets = new SecretsSummary(SideSecrets.of(leftUploaded), SideSecrets.of(rightUploaded),
                referenced, resolved, missing);
        return new EnvView(path, folderScope ? "FOLDER" : "FILE", scopePath, !node.dir, files,
                c.leftName, c.rightName, c.leftLabel, c.rightLabel, result.rows(), result.summary(), secrets);
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

    private Node buildTree(Map<String, byte[]> left, Map<String, byte[]> right) {
        Node root = dirNode("", "");
        Map<String, Node> dirs = new LinkedHashMap<>();
        dirs.put("", root);
        TreeSet<String> paths = new TreeSet<>(left.keySet());
        paths.addAll(right.keySet());
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
            parent.children.add(fileNode(segments[segments.length - 1], path, left.get(path), right.get(path)));
        }
        aggregate(root);
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

    private static Node fileNode(String name, String path, byte[] lb, byte[] rb) {
        Node n = new Node();
        n.name = name;
        n.path = path;
        n.leftState = state(lb);
        n.rightState = state(rb);
        n.leftSize = lb == null ? -1 : lb.length;
        n.rightSize = rb == null ? -1 : rb.length;
        if (lb == null) {
            n.status = "RIGHT_ONLY";
            n.reason = "Does not exist in the left folder";
        } else if (rb == null) {
            n.status = "LEFT_ONLY";
            n.reason = "Does not exist in the right folder";
        } else {
            LogicalFileComparer.Result result = LogicalFileComparer.compare(path, lb, rb);
            n.status = result.status().name();
            n.reason = emptyReason(n.leftState, n.rightState, result.reason());
            n.differences = result.diffs().size();
        }
        return n;
    }

    private static void aggregate(Node dir) {
        boolean leftPresent = false;
        boolean rightPresent = false;
        for (Node child : dir.children) {
            if (child.dir) aggregate(child);
            if (child.dir) {
                dir.identical += child.identical;
                dir.logicallySame += child.logicallySame;
                dir.differs += child.differs;
                dir.leftOnly += child.leftOnly;
                dir.rightOnly += child.rightOnly;
            } else {
                switch (child.status) {
                    case "IDENTICAL" -> dir.identical++;
                    case "LOGICALLY_IDENTICAL" -> dir.logicallySame++;
                    case "DIFFERS" -> dir.differs++;
                    case "LEFT_ONLY" -> dir.leftOnly++;
                    default -> dir.rightOnly++;
                }
            }
            leftPresent |= !"MISSING".equals(child.leftState);
            rightPresent |= !"MISSING".equals(child.rightState);
            dir.differences += child.differences;
        }
        dir.children.sort(Comparator.comparing((Node n) -> !n.dir).thenComparing(n -> n.name, String.CASE_INSENSITIVE_ORDER));
        dir.leftState = leftPresent ? "PRESENT" : "MISSING";
        dir.rightState = rightPresent ? "PRESENT" : "MISSING";
        if (!leftPresent) {
            dir.status = "RIGHT_ONLY";
            dir.reason = "Folder does not exist in the left folder";
        } else if (!rightPresent) {
            dir.status = "LEFT_ONLY";
            dir.reason = "Folder does not exist in the right folder";
        } else if (dir.differs + dir.leftOnly + dir.rightOnly + dir.logicallySame == 0) {
            dir.status = "IDENTICAL";
            dir.reason = "All files are identical";
        } else if (dir.differs + dir.leftOnly + dir.rightOnly == 0) {
            dir.status = "LOGICALLY_IDENTICAL";
            dir.reason = dir.logicallySame + " file(s) are formatted differently but logically the same";
        } else {
            dir.status = "DIFFERS";
            List<String> parts = new ArrayList<>();
            if (dir.differs > 0) parts.add(dir.differs + " differ");
            if (dir.leftOnly > 0) parts.add(dir.leftOnly + " only in left");
            if (dir.rightOnly > 0) parts.add(dir.rightOnly + " only in right");
            dir.reason = String.join(" · ", parts);
        }
    }

    private static void summarize(FolderCompare c) {
        FolderCompare.Summary s = c.summary;
        for (Node child : c.root.children) {
            if (!child.dir) continue;
            if (!"MISSING".equals(child.leftState)) s.leftFolders++;
            if (!"MISSING".equals(child.rightState)) s.rightFolders++;
            switch (child.status) {
                case "LEFT_ONLY" -> s.leftOnlyFolders++;
                case "RIGHT_ONLY" -> s.rightOnlyFolders++;
                case "IDENTICAL" -> {
                    s.matchedFolders++;
                    s.identicalFolders++;
                }
                default -> {
                    s.matchedFolders++;
                    s.differentFolders++;
                }
            }
        }
        s.identicalFiles = c.root.identical;
        s.logicallySameFiles = c.root.logicallySame;
        s.differentFiles = c.root.differs;
        s.leftOnlyFiles = c.root.leftOnly;
        s.rightOnlyFiles = c.root.rightOnly;
        s.files = s.identicalFiles + s.logicallySameFiles + s.differentFiles + s.leftOnlyFiles + s.rightOnlyFiles;
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

    private static String emptyReason(String leftState, String rightState, String fallback) {
        if ("EMPTY".equals(leftState) && "EMPTY".equals(rightState)) return "Both files are empty";
        if ("EMPTY".equals(leftState)) return "The left file is empty";
        if ("EMPTY".equals(rightState)) return "The right file is empty";
        return fallback;
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
