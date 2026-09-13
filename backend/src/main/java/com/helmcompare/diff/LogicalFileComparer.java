package com.helmcompare.diff;

import com.helmcompare.parse.YNode;
import com.helmcompare.parse.YamlTreeParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Compares two files the way Kubernetes would see them. YAML (including Helm templates) is compared
 * as a structure: key order, list order (matched by name/identity), indentation, quoting and comments
 * are not differences. Other text files are compared line by line ignoring spacing and line order.
 * Every difference carries the line ranges on both sides so it can be highlighted in the source.
 */
public final class LogicalFileComparer {

    public enum Status { IDENTICAL, LOGICALLY_IDENTICAL, DIFFERS }

    /** Line numbers are 1-based; 0 means "no lines on this side". */
    public record Diff(String id, String kind, String path, String description, String left, String right,
                       int leftStart, int leftEnd, int rightStart, int rightEnd) {
    }

    public record Result(Status status, String reason, List<Diff> diffs) {
    }

    private static final List<String> IDENTITY_KEYS = List.of("name", "key", "containerPort", "topologyKey",
            "mountPath", "secretKey", "port", "path", "ip", "host");
    private static final Set<String> ORDERED_LISTS = Set.of("command", "args");
    private static final long MAX_LCS_CELLS = 4_000_000L;

    private LogicalFileComparer() {
    }

    public static Result compare(String path, byte[] left, byte[] right) {
        if (Arrays.equals(left, right)) return new Result(Status.IDENTICAL, "Files are identical", List.of());
        if (isBinary(left) || isBinary(right)) return new Result(Status.DIFFERS, "Binary files differ", List.of());
        String l = text(left);
        String r = text(right);
        if (l.equals(r)) return new Result(Status.IDENTICAL, "Files are identical (only line endings differ)", List.of());
        return isYaml(path) ? compareYaml(l, r) : compareText(l, r);
    }

    public static boolean isYaml(String path) {
        String p = path.toLowerCase(Locale.ROOT);
        return p.endsWith(".yaml") || p.endsWith(".yml");
    }

    public static boolean isBinary(byte[] bytes) {
        int n = Math.min(bytes.length, 8000);
        for (int i = 0; i < n; i++) if (bytes[i] == 0) return true;
        return false;
    }

    public static boolean isBlank(byte[] bytes) {
        return !isBinary(bytes) && text(bytes).isBlank();
    }

    public static String text(byte[] bytes) {
        String s = new String(bytes, StandardCharsets.UTF_8);
        if (s.startsWith("﻿")) s = s.substring(1);
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    // ───────────────────────────── YAML ─────────────────────────────

    private static Result compareYaml(String l, String r) {
        TemplateLiteral.Result lt = TemplateLiteral.process(l);
        TemplateLiteral.Result rt = TemplateLiteral.process(r);
        YamlTreeParser.Result lp = YamlTreeParser.parse("left", lt.yaml());
        YamlTreeParser.Result rp = YamlTreeParser.parse("right", rt.yaml());
        if (lp.failed() || rp.failed()) {
            Result text = compareText(l, r);
            return text.status() == Status.DIFFERS
                    ? new Result(Status.DIFFERS, text.reason() + " (compared as text: the YAML could not be parsed)", text.diffs())
                    : text;
        }

        Collector c = new Collector();
        compareDocuments(lp.documents(), rp.documents(), c);
        compareLines("Template logic", lt.directives().stream().map(d -> new Line(d.text(), d.line())).toList(),
                rt.directives().stream().map(d -> new Line(d.text(), d.line())).toList(), c, true);
        compareLines("Unparsed line", ignored(lt.yaml(), lp.ignoredLines()), ignored(rt.yaml(), rp.ignoredLines()), c, false);

        if (c.diffs.isEmpty()) {
            return new Result(Status.LOGICALLY_IDENTICAL,
                    "Logically identical — the same Kubernetes configuration; only ordering, spacing, quoting or comments differ",
                    List.of());
        }
        return new Result(Status.DIFFERS, c.diffs.size() + " logical difference(s)", c.sorted());
    }

    private static void compareDocuments(List<YNode> left, List<YNode> right, Collector c) {
        boolean multi = left.size() > 1 || right.size() > 1;
        int[] pairOf = new int[left.size()];
        Arrays.fill(pairOf, -1);
        boolean[] used = new boolean[right.size()];
        for (int i = 0; i < left.size(); i++) {
            String id = documentId(left.get(i));
            if (id == null) continue;
            for (int j = 0; j < right.size(); j++) {
                if (!used[j] && id.equals(documentId(right.get(j)))) {
                    pairOf[i] = j;
                    used[j] = true;
                    break;
                }
            }
        }
        int next = 0;
        for (int i = 0; i < left.size(); i++) {
            if (pairOf[i] >= 0 || documentId(left.get(i)) != null) continue;
            while (next < right.size() && (used[next] || documentId(right.get(next)) != null)) next++;
            if (next < right.size()) {
                pairOf[i] = next;
                used[next] = true;
            }
        }
        for (int i = 0; i < left.size(); i++) {
            YNode l = left.get(i);
            String label = documentLabel(l, i, multi);
            if (pairOf[i] < 0) {
                c.add("REMOVED", label.isEmpty() ? "Document" : label, "Document removed", render(l), null, l.line, l.endLine, 0, 0);
            } else {
                YNode r = right.get(pairOf[i]);
                compareNode(l, r, label.isEmpty() ? "" : label + " ›", l.line, r.line, c);
            }
        }
        for (int j = 0; j < right.size(); j++) {
            if (used[j]) continue;
            YNode r = right.get(j);
            String label = documentLabel(r, j, multi);
            c.add("ADDED", label.isEmpty() ? "Document" : label, "Document added", null, render(r), 0, 0, r.line, r.endLine);
        }
    }

    private static void compareNode(YNode l, YNode r, String path, int lKey, int rKey, Collector c) {
        if (empty(l) && empty(r)) return;
        int ls = Math.min(lKey, l.line);
        int le = Math.max(l.endLine, lKey);
        int rs = Math.min(rKey, r.line);
        int re = Math.max(r.endLine, rKey);
        if (l.kind != r.kind || empty(l) != empty(r)) {
            c.add("CHANGED", display(path), "Value changed", render(l), render(r), ls, le, rs, re);
            return;
        }
        switch (l.kind) {
            case SCALAR -> {
                if (!l.value.strip().equals(r.value.strip())) {
                    c.add("CHANGED", display(path), "Value changed", l.value, r.value, ls, le, rs, re);
                }
            }
            case MAP -> {
                for (Map.Entry<String, YNode> e : l.map.entrySet()) {
                    String key = e.getKey();
                    String p = join(path, key);
                    YNode lv = e.getValue();
                    if (r.has(key)) {
                        compareNode(lv, r.get(key), p, l.keyLine(key), r.keyLine(key), c);
                    } else if (!empty(lv) || !isPlaceholderKey(key)) {
                        int start = l.keyLine(key);
                        c.add("REMOVED", display(p), "Removed", render(lv), null, start, Math.max(start, lv.endLine), 0, 0);
                    }
                }
                for (Map.Entry<String, YNode> e : r.map.entrySet()) {
                    String key = e.getKey();
                    if (l.has(key)) continue;
                    YNode rv = e.getValue();
                    int start = r.keyLine(key);
                    c.add("ADDED", display(join(path, key)), "Added", null, render(rv), 0, 0, start, Math.max(start, rv.endLine));
                }
            }
            case SEQ -> compareSequence(l, r, path, c);
            case NULL -> {
                // both empty
            }
        }
    }

    private static void compareSequence(YNode l, YNode r, String path, Collector c) {
        List<YNode> left = l.seq;
        List<YNode> right = r.seq;
        String lastKey = lastKey(path);
        boolean scalars = left.stream().allMatch(n -> !n.isMap() && !n.isSeq()) && right.stream().allMatch(n -> !n.isMap() && !n.isSeq());
        if (scalars && ORDERED_LISTS.contains(lastKey)) {
            String lj = left.stream().map(LogicalFileComparer::canonical).collect(Collectors.joining(","));
            String rj = right.stream().map(LogicalFileComparer::canonical).collect(Collectors.joining(","));
            if (!lj.equals(rj)) c.add("CHANGED", display(path), "List changed", render(l), render(r), l.line, l.endLine, r.line, r.endLine);
            return;
        }

        int[] pairOf = new int[left.size()];
        Arrays.fill(pairOf, -1);
        boolean[] used = new boolean[right.size()];
        for (int i = 0; i < left.size(); i++) {
            String id = identity(left.get(i));
            if (id == null) continue;
            for (int j = 0; j < right.size(); j++) {
                if (!used[j] && id.equals(identity(right.get(j)))) {
                    pairOf[i] = j;
                    used[j] = true;
                    break;
                }
            }
        }
        for (int i = 0; i < left.size(); i++) {
            if (pairOf[i] >= 0) continue;
            String can = canonical(left.get(i));
            for (int j = 0; j < right.size(); j++) {
                if (!used[j] && can.equals(canonical(right.get(j)))) {
                    pairOf[i] = j;
                    used[j] = true;
                    break;
                }
            }
        }
        List<Integer> unpairedLeft = new ArrayList<>();
        for (int i = 0; i < left.size(); i++) if (pairOf[i] < 0 && identity(left.get(i)) == null) unpairedLeft.add(i);
        List<Integer> unusedRight = new ArrayList<>();
        for (int j = 0; j < right.size(); j++) if (!used[j] && identity(right.get(j)) == null) unusedRight.add(j);
        for (int k = 0; k < Math.min(unpairedLeft.size(), unusedRight.size()); k++) {
            pairOf[unpairedLeft.get(k)] = unusedRight.get(k);
            used[unusedRight.get(k)] = true;
        }

        for (int i = 0; i < left.size(); i++) {
            YNode ln = left.get(i);
            String label = identity(ln) != null ? identity(ln) : String.valueOf(i);
            if (pairOf[i] >= 0) {
                YNode rn = right.get(pairOf[i]);
                compareNode(ln, rn, path + "[" + label + "]", ln.line, rn.line, c);
            } else {
                c.add("REMOVED", display(path + "[" + label + "]"), "List item removed", render(ln), null, ln.line, ln.endLine, 0, 0);
            }
        }
        for (int j = 0; j < right.size(); j++) {
            if (used[j]) continue;
            YNode rn = right.get(j);
            String label = identity(rn) != null ? identity(rn) : String.valueOf(j);
            c.add("ADDED", display(path + "[" + label + "]"), "List item added", null, render(rn), 0, 0, rn.line, rn.endLine);
        }
    }

    private record Line(String text, int line) {
    }

    /** Order-insensitive comparison of lines (template logic, unparsed lines, …). */
    private static void compareLines(String label, List<Line> left, List<Line> right, Collector c, boolean template) {
        Map<String, Deque<Line>> rightByText = new HashMap<>();
        right.forEach(line -> rightByText.computeIfAbsent(line.text(), k -> new ArrayDeque<>()).add(line));
        for (Line line : left) {
            Deque<Line> match = rightByText.get(line.text());
            if (match != null && !match.isEmpty()) {
                match.poll();
            } else {
                c.add("REMOVED", label, label + " removed", template ? "{{ " + line.text() + " }}" : line.text(), null,
                        line.line(), line.line(), 0, 0);
            }
        }
        rightByText.values().forEach(rest -> rest.forEach(line ->
                c.add("ADDED", label, label + " added", null, template ? "{{ " + line.text() + " }}" : line.text(),
                        0, 0, line.line(), line.line())));
    }

    private static List<Line> ignored(String yaml, List<Integer> lines) {
        String[] all = yaml.split("\n", -1);
        return lines.stream()
                .filter(n -> n >= 1 && n <= all.length)
                .map(n -> new Line(all[n - 1].strip().replaceAll("\\s+", " "), n))
                .filter(l -> !l.text().isEmpty())
                .toList();
    }

    // ───────────────────────────── text ─────────────────────────────

    private static Result compareText(String l, String r) {
        List<Line> left = nonBlank(l);
        List<Line> right = nonBlank(r);
        List<String> lt = left.stream().map(Line::text).toList();
        List<String> rt = right.stream().map(Line::text).toList();
        if (lt.equals(rt)) {
            return new Result(Status.LOGICALLY_IDENTICAL, "Logically identical — only spacing or blank lines differ", List.of());
        }
        if (lt.stream().sorted().toList().equals(rt.stream().sorted().toList())) {
            return new Result(Status.LOGICALLY_IDENTICAL, "Logically identical — the same lines in a different order", List.of());
        }
        Collector c = new Collector();
        if ((long) left.size() * right.size() > MAX_LCS_CELLS) {
            compareLines("Line", left, right, c, false);
        } else {
            lineDiff(left, right, c);
        }
        return new Result(Status.DIFFERS, c.diffs.size() + " difference(s)", c.sorted());
    }

    private static List<Line> nonBlank(String text) {
        String[] lines = text.split("\n", -1);
        List<Line> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip().replaceAll("\\s+", " ");
            if (!t.isEmpty()) out.add(new Line(t, i + 1));
        }
        return out;
    }

    private record Op(char type, Line line) {
    }

    private static void lineDiff(List<Line> left, List<Line> right, Collector c) {
        int n = left.size();
        int m = right.size();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                dp[i][j] = left.get(i).text().equals(right.get(j).text()) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
            }
        }
        List<Op> ops = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (left.get(i).text().equals(right.get(j).text())) {
                ops.add(new Op('=', null));
                i++;
                j++;
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                ops.add(new Op('-', left.get(i++)));
            } else {
                ops.add(new Op('+', right.get(j++)));
            }
        }
        while (i < n) ops.add(new Op('-', left.get(i++)));
        while (j < m) ops.add(new Op('+', right.get(j++)));

        // Lines that only moved are not differences.
        Map<String, Integer> removed = new HashMap<>();
        Map<String, Integer> added = new HashMap<>();
        ops.forEach(op -> {
            if (op.type() == '-') removed.merge(op.line().text(), 1, Integer::sum);
            if (op.type() == '+') added.merge(op.line().text(), 1, Integer::sum);
        });
        Map<String, Integer> movedDel = new HashMap<>();
        Map<String, Integer> movedIns = new HashMap<>();
        removed.forEach((t, count) -> {
            int moved = Math.min(count, added.getOrDefault(t, 0));
            if (moved > 0) {
                movedDel.put(t, moved);
                movedIns.put(t, moved);
            }
        });

        List<Line> dels = new ArrayList<>();
        List<Line> ins = new ArrayList<>();
        for (Op op : ops) {
            if (op.type() == '=') {
                flushHunk(dels, ins, c);
                continue;
            }
            Map<String, Integer> moved = op.type() == '-' ? movedDel : movedIns;
            int left0 = moved.getOrDefault(op.line().text(), 0);
            if (left0 > 0) {
                moved.put(op.line().text(), left0 - 1);
                continue;
            }
            (op.type() == '-' ? dels : ins).add(op.line());
        }
        flushHunk(dels, ins, c);
    }

    private static void flushHunk(List<Line> dels, List<Line> ins, Collector c) {
        if (dels.isEmpty() && ins.isEmpty()) return;
        String leftText = joinLines(dels);
        String rightText = joinLines(ins);
        int ls = dels.isEmpty() ? 0 : dels.get(0).line();
        int le = dels.isEmpty() ? 0 : dels.get(dels.size() - 1).line();
        int rs = ins.isEmpty() ? 0 : ins.get(0).line();
        int re = ins.isEmpty() ? 0 : ins.get(ins.size() - 1).line();
        if (!dels.isEmpty() && !ins.isEmpty()) {
            c.add("CHANGED", "Lines " + ls + " ↔ " + rs, "Lines changed", leftText, rightText, ls, le, rs, re);
        } else if (!dels.isEmpty()) {
            c.add("REMOVED", "Line " + ls, "Lines removed", leftText, null, ls, le, 0, 0);
        } else {
            c.add("ADDED", "Line " + rs, "Lines added", null, rightText, 0, 0, rs, re);
        }
        dels.clear();
        ins.clear();
    }

    private static String joinLines(List<Line> lines) {
        String joined = lines.stream().limit(6).map(Line::text).collect(Collectors.joining("\n"));
        return lines.size() > 6 ? joined + "\n…" : joined;
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static final class Collector {
        final List<Diff> diffs = new ArrayList<>();

        void add(String kind, String path, String description, String left, String right, int ls, int le, int rs, int re) {
            diffs.add(new Diff("d" + (diffs.size() + 1), kind, path, description, left, right, ls, le, rs, re));
        }

        List<Diff> sorted() {
            List<Diff> out = new ArrayList<>(diffs);
            out.sort(Comparator.comparingInt((Diff d) -> d.leftStart() > 0 ? d.leftStart() : d.rightStart())
                    .thenComparingInt(d -> d.rightStart()));
            List<Diff> renumbered = new ArrayList<>();
            for (int i = 0; i < out.size(); i++) {
                Diff d = out.get(i);
                renumbered.add(new Diff("d" + (i + 1), d.kind(), d.path(), d.description(), d.left(), d.right(),
                        d.leftStart(), d.leftEnd(), d.rightStart(), d.rightEnd()));
            }
            return renumbered;
        }
    }

    private static boolean empty(YNode n) {
        return n == null || n.isNull() || (n.isMap() && n.map.isEmpty()) || (n.isSeq() && n.seq.isEmpty())
                || (n.isScalar() && n.value.isEmpty());
    }

    private static boolean isPlaceholderKey(String key) {
        return key.startsWith("«");
    }

    private static String documentId(YNode doc) {
        if (!doc.isMap() || doc.str("kind") == null) return null;
        YNode metadata = doc.get("metadata");
        String name = metadata != null && metadata.isMap() && metadata.str("name") != null ? metadata.str("name") : "";
        return doc.str("kind") + (name.isEmpty() ? "" : "/" + name);
    }

    private static String documentLabel(YNode doc, int index, boolean multi) {
        String id = documentId(doc);
        if (id != null) return id;
        return multi ? "Document " + (index + 1) : "";
    }

    private static String identity(YNode n) {
        if (n == null || !n.isMap()) return null;
        for (String key : IDENTITY_KEYS) {
            String v = n.str(key);
            if (v != null) return key.equals("name") ? v : key + "=" + v;
        }
        return null;
    }

    private static String canonical(YNode n) {
        if (n == null || n.isNull()) return "~";
        return switch (n.kind) {
            case SCALAR -> "s:" + n.value.strip();
            case NULL -> "~";
            case MAP -> {
                TreeMap<String, String> sorted = new TreeMap<>();
                n.map.forEach((k, v) -> sorted.put(k, canonical(v)));
                yield "{" + sorted + "}";
            }
            case SEQ -> "[" + n.seq.stream().map(LogicalFileComparer::canonical).sorted().collect(Collectors.joining(",")) + "]";
        };
    }

    private static String render(YNode n) {
        if (n == null || n.isNull()) return "";
        if (n.isScalar()) return n.value;
        String json = n.toFlowJson();
        return json.length() > 400 ? json.substring(0, 397) + "…" : json;
    }

    private static String join(String path, String key) {
        if (path.isEmpty()) return key;
        if (path.endsWith("›")) return path + " " + key;
        return path + "." + key;
    }

    private static String display(String path) {
        String p = path.endsWith(" ›") ? path.substring(0, path.length() - 2) : path;
        return p.isEmpty() ? "(document root)" : p;
    }

    private static String lastKey(String path) {
        String p = path.replaceAll("\\[[^\\]]*\\]$", "");
        int i = Math.max(p.lastIndexOf('.'), p.lastIndexOf(' '));
        return i < 0 ? p : p.substring(i + 1);
    }
}
