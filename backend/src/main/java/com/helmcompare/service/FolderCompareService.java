package com.helmcompare.service;

import com.helmcompare.diff.LogicalFileComparer;
import com.helmcompare.model.FolderCompare;
import com.helmcompare.model.FolderCompare.Node;
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

    public FolderCompareService(FolderCompareStore store) {
        this.store = store;
    }

    public record FileView(String path, String name, String status, String reason, String leftState, String rightState,
                           boolean binary, List<String> leftLines, List<String> rightLines,
                           List<LogicalFileComparer.Diff> differences,
                           String leftName, String rightName, String leftLabel, String rightLabel) {
    }

    private record Side(String rootName, Map<String, byte[]> files) {
    }

    public FolderCompare.Info create(List<MultipartFile> left, List<MultipartFile> right, String leftLabel, String rightLabel) {
        if (left == null || left.isEmpty() || right == null || right.isEmpty()) {
            throw new IllegalArgumentException("Choose both a left and a right folder.");
        }
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
