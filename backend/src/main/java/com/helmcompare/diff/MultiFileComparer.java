package com.helmcompare.diff;

import com.helmcompare.diff.LogicalFileComparer.Diff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Logical differences between two or three sides of one file. Every row carries a value and line range per side, so the
 * frontend renders two and three sides the same way. Three sides are merged from the three pairwise comparisons by
 * configuration path: {@code spec.replicas} is one row with the value of every side.
 */
public final class MultiFileComparer {

    /**
     * Values and lines are indexed by side (the size of the comparison, 2 or 3); entries of sides this row does not
     * compare are null / 0.
     *
     * @param kind   CHANGED when every compared side has the setting, MISSING when some do not
     * @param sides  the sides this row compares, ascending — normally all chosen sides; a pair when a text hunk could
     *               not be matched across three files
     * @param values rendered value per side, null when absent on that side
     */
    public record SideDiff(String id, String kind, String path, String description, List<Integer> sides, List<String> values,
                           List<Integer> starts, List<Integer> ends, List<Integer> anchors) {
    }

    private MultiFileComparer() {
    }

    /** Differences of sides a and b (a &lt; b) as reported by {@link LogicalFileComparer}. */
    public static List<SideDiff> pair(int n, int a, int b, List<Diff> diffs) {
        List<SideDiff> out = new ArrayList<>();
        for (Diff d : diffs) {
            Row row = new Row(n, d.path(), d.description());
            row.set(a, d.left(), d.leftStart(), d.leftEnd(), d.leftAnchor());
            row.set(b, d.right(), d.rightStart(), d.rightEnd(), d.rightAnchor());
            out.add(row.toDiff(d.id()));
        }
        return out;
    }

    /** Merges the pairwise differences of sides a &lt; b &lt; c into one row per configuration path. */
    public static List<SideDiff> merge(int n, int a, int b, int c, List<Diff> ab, List<Diff> ac, List<Diff> bc) {
        Map<String, Row> byPath = new LinkedHashMap<>();
        absorb(byPath, n, a, b, ab);
        absorb(byPath, n, a, c, ac);
        absorb(byPath, n, b, c, bc);
        int[] all = {a, b, c};

        List<Row> rows = new ArrayList<>();
        for (Row row : byPath.values()) {
            for (int s : all) {
                if (row.known[s]) continue;
                // inside a block that does not exist on this side: the setting does not exist either
                Row absentParent = absentAncestor(byPath, row.path, s);
                if (absentParent != null) row.set(s, null, 0, 0, absentParent.anchors[s]);
            }
            if (Arrays.stream(all).allMatch(s -> row.known[s]) && sameEverywhere(row, all)) continue;
            rows.add(row);
        }
        rows.sort(Comparator.comparingLong((Row r) -> r.position(all)).thenComparing(r -> r.path));
        List<SideDiff> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) out.add(rows.get(i).toDiff("d" + (i + 1)));
        return out;
    }

    private static void absorb(Map<String, Row> byPath, int n, int i, int j, List<Diff> diffs) {
        for (Diff d : diffs) {
            // text hunks are named after their line numbers, which differ per pair: keep them per pair
            String key = isLineHunk(d.path()) ? i + "-" + j + " " + d.path() : d.path();
            Row row = byPath.computeIfAbsent(key, k -> new Row(n, d.path(), d.description()));
            row.set(i, d.left(), d.leftStart(), d.leftEnd(), d.leftAnchor());
            row.set(j, d.right(), d.rightStart(), d.rightEnd(), d.rightAnchor());
        }
    }

    private static boolean isLineHunk(String path) {
        return path.startsWith("Line ") || path.startsWith("Lines ") || path.equals("Line") || path.equals("Template logic")
                || path.equals("Unparsed line");
    }

    private static Row absentAncestor(Map<String, Row> byPath, String path, int side) {
        for (Row candidate : byPath.values()) {
            if (candidate.path.equals(path) || !candidate.known[side] || candidate.values[side] != null) continue;
            String p = candidate.path;
            if (path.startsWith(p + ".") || path.startsWith(p + "[") || path.startsWith(p + " ›")) return candidate;
        }
        return null;
    }

    private static boolean sameEverywhere(Row row, int[] sides) {
        for (int s : sides) {
            if (!Objects.equals(normalize(row.values[s]), normalize(row.values[sides[0]]))) return false;
        }
        return true;
    }

    private static String normalize(String v) {
        return v == null ? null : v.strip();
    }

    private static final class Row {
        final String path;
        final String description;
        final boolean[] known;
        final String[] values;
        final int[] starts;
        final int[] ends;
        final int[] anchors;

        Row(int n, String path, String description) {
            this.path = path;
            this.description = description;
            known = new boolean[n];
            values = new String[n];
            starts = new int[n];
            ends = new int[n];
            anchors = new int[n];
        }

        void set(int side, String value, int start, int end, int anchor) {
            if (!known[side]) {
                known[side] = true;
                values[side] = value;
            }
            if (starts[side] == 0 && start > 0) {
                starts[side] = start;
                ends[side] = end;
            }
            if (anchors[side] == 0 && anchor > 0) anchors[side] = anchor;
        }

        /** In the order of the first side's file; blocks missing there sit where they would appear. */
        long position(int[] sides) {
            for (int s : sides) {
                if (!known[s]) continue;
                if (starts[s] > 0) return 2L * starts[s];
                if (anchors[s] > 0) return 2L * anchors[s] + 1;
            }
            return Long.MAX_VALUE;
        }

        SideDiff toDiff(String id) {
            List<Integer> sides = new ArrayList<>();
            boolean missing = false;
            for (int s = 0; s < known.length; s++) {
                if (!known[s]) continue;
                sides.add(s);
                if (values[s] == null) missing = true;
            }
            return new SideDiff(id, missing ? "MISSING" : "CHANGED", path, description, sides, Arrays.asList(values),
                    Arrays.stream(starts).boxed().toList(), Arrays.stream(ends).boxed().toList(),
                    Arrays.stream(anchors).boxed().toList());
        }
    }
}
