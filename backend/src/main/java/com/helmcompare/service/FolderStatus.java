package com.helmcompare.service;

import com.helmcompare.model.FolderCompare;
import com.helmcompare.model.FolderCompare.Node;
import com.helmcompare.model.FolderCompare.PairResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Status of a file or folder for a chosen set of sides (2 or 3): IDENTICAL, LOGICALLY_IDENTICAL, DIFFERS or PARTIAL
 * (does not exist in every chosen side). The frontend evaluates the same rules (lib/compare.ts) when a side is hidden.
 */
public final class FolderStatus {

    public static final String IDENTICAL = "IDENTICAL";
    public static final String LOGICALLY_IDENTICAL = "LOGICALLY_IDENTICAL";
    public static final String DIFFERS = "DIFFERS";
    public static final String PARTIAL = "PARTIAL";

    public record Counts(int identical, int logicallySame, int differs, int partial) {
        public int total() {
            return identical + logicallySame + differs + partial;
        }

        Counts plus(Counts o) {
            return new Counts(identical + o.identical, logicallySame + o.logicallySame, differs + o.differs, partial + o.partial);
        }
    }

    /** @param differences most logical differences between two chosen sides (files) or in total (folders) */
    public record Eval(String status, String reason, int differences, Counts counts) {
    }

    private FolderStatus() {
    }

    public static int[] all(int sides) {
        int[] out = new int[sides];
        for (int i = 0; i < sides; i++) out[i] = i;
        return out;
    }

    /** A node is shown when it exists in at least one chosen side. */
    public static boolean visible(Node n, int[] sides) {
        for (int s : sides) if (n.present(s)) return true;
        return false;
    }

    public static Eval eval(Node n, int[] sides, List<String> titles) {
        return n.dir ? dir(n, sides, titles) : file(n, sides, titles);
    }

    private static Eval file(Node n, int[] sides, List<String> titles) {
        String missing = missingReason(n, sides, titles);
        int differences = 0;
        boolean allIdentical = true;
        boolean allSame = true;
        for (int i = 0; i < sides.length; i++) {
            for (int j = i + 1; j < sides.length; j++) {
                PairResult p = n.pair(sides[i], sides[j]);
                if (p == null) continue;
                differences = Math.max(differences, p.differences);
                if (!IDENTICAL.equals(p.status)) allIdentical = false;
                if (DIFFERS.equals(p.status)) allSame = false;
            }
        }
        if (missing != null) return new Eval(PARTIAL, missing, differences, new Counts(0, 0, 0, 1));
        if (allIdentical) return new Eval(IDENTICAL, "Identical in every folder", 0, new Counts(1, 0, 0, 0));
        if (allSame) {
            return new Eval(LOGICALLY_IDENTICAL, "Logically the same — only formatting or order differs", 0, new Counts(0, 1, 0, 0));
        }
        return new Eval(DIFFERS, differences + " logical difference(s)", differences, new Counts(0, 0, 1, 0));
    }

    private static Eval dir(Node n, int[] sides, List<String> titles) {
        Counts counts = new Counts(0, 0, 0, 0);
        int differences = 0;
        for (Node child : n.children) {
            if (!visible(child, sides)) continue;
            Eval e = eval(child, sides, titles);
            counts = counts.plus(e.counts());
            differences += e.differences();
        }
        String missing = missingReason(n, sides, titles);
        if (missing != null) return new Eval(PARTIAL, missing, differences, counts);
        if (counts.differs() + counts.partial() + counts.logicallySame() == 0) {
            return new Eval(IDENTICAL, "All files are identical", differences, counts);
        }
        if (counts.differs() + counts.partial() == 0) {
            return new Eval(LOGICALLY_IDENTICAL, counts.logicallySame() + " file(s) are formatted differently but logically the same",
                    differences, counts);
        }
        List<String> parts = new ArrayList<>();
        if (counts.differs() > 0) parts.add(counts.differs() + " differ");
        if (counts.partial() > 0) parts.add(counts.partial() + " not in every folder");
        return new Eval(DIFFERS, String.join(" · ", parts), differences, counts);
    }

    /** "Only in PROD" / "Missing in UAT", or null when the node exists in every chosen side. */
    public static String missingReason(Node n, int[] sides, List<String> titles) {
        boolean[] present = new boolean[titles.size()];
        for (int s : sides) present[s] = n.present(s);
        return missingReason(present, sides, titles);
    }

    /** @param exists per side (indexed like titles) */
    public static String missingReason(boolean[] exists, int[] sides, List<String> titles) {
        List<String> present = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        for (int s : sides) (exists[s] ? present : absent).add(titles.get(s));
        if (absent.isEmpty()) return null;
        if (present.size() == 1) return "Only in " + present.get(0);
        return "Missing in " + String.join(", ", absent);
    }

    public static List<String> titles(FolderCompare c) {
        return c.sides.stream().map(FolderCompare.SideInfo::title).toList();
    }

    public static void summarize(FolderCompare c) {
        int[] sides = all(c.sides.size());
        List<String> titles = titles(c);
        FolderCompare.Summary s = new FolderCompare.Summary();
        for (int i = 0; i < sides.length; i++) s.sideFolders.add(0);
        for (Node child : c.root.children) {
            if (!child.dir) continue;
            s.folders++;
            for (int i = 0; i < sides.length; i++) if (child.present(i)) s.sideFolders.set(i, s.sideFolders.get(i) + 1);
            switch (eval(child, sides, titles).status()) {
                case IDENTICAL -> s.identicalFolders++;
                case LOGICALLY_IDENTICAL -> s.logicallySameFolders++;
                case PARTIAL -> s.partialFolders++;
                default -> s.differentFolders++;
            }
        }
        Counts files = eval(c.root, sides, titles).counts();
        s.identicalFiles = files.identical();
        s.logicallySameFiles = files.logicallySame();
        s.differentFiles = files.differs();
        s.partialFiles = files.partial();
        s.files = files.total();
        c.summary = s;
    }
}
