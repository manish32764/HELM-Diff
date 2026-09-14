package com.helmcompare.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Comparison of two or three parent folders whose same-named sub-folders (one per microservice) are matched.
 * Every file keeps its state on each side and the result of every pair of sides, so any subset of sides can be
 * shown (a side can be hidden) without comparing again.
 */
public class FolderCompare {
    public static final int MAX_SIDES = 3;

    public String id;
    public Instant createdAt;
    public List<SideInfo> sides = new ArrayList<>();
    public Summary summary = new Summary();
    public Node root;

    // ── format before 3-way comparisons (read and converted by the store) ──
    public String leftName;
    public String rightName;
    public String leftLabel;
    public String rightLabel;

    public static class SideInfo {
        /** Name of the uploaded parent folder. */
        public String name;
        /** Optional label such as PROD; shown instead of the name. */
        public String label;

        public SideInfo() {
        }

        public SideInfo(String name, String label) {
            this.name = name;
            this.label = label;
        }

        public String title() {
            return label == null || label.isBlank() ? name : label;
        }
    }

    /** Counts over all sides. */
    public static class Summary {
        public int folders;
        public int identicalFolders;
        public int logicallySameFolders;
        public int differentFolders;
        /** Folders that do not exist in every side. */
        public int partialFolders;
        public int files;
        public int identicalFiles;
        public int logicallySameFiles;
        public int differentFiles;
        public int partialFiles;
        /** Folders per side. */
        public List<Integer> sideFolders = new ArrayList<>();
    }

    /** Result of comparing one file on two sides. */
    public static class PairResult {
        /** IDENTICAL, LOGICALLY_IDENTICAL, DIFFERS */
        public String status;
        public String reason;
        public int differences;

        public PairResult() {
        }

        public PairResult(String status, String reason, int differences) {
            this.status = status;
            this.reason = reason;
            this.differences = differences;
        }
    }

    public static class Node {
        public String name;
        public String path;
        public boolean dir;
        /** Per side: PRESENT, EMPTY, MISSING (a folder is PRESENT when it holds at least one file on that side). */
        public List<String> states;
        /** Per side file size in bytes, -1 when missing (files only). */
        public List<Long> sizes;
        /** Files only: key "i-j" (i &lt; j) for every pair of sides where the file exists on both. */
        public Map<String, PairResult> pairs;
        public List<Node> children;

        // ── format before 3-way comparisons ──
        public String status;
        public String leftState;
        public String rightState;
        public String reason;
        public Integer differences;
        public Long leftSize;
        public Long rightSize;
        public Integer identical;
        public Integer logicallySame;
        public Integer differs;
        public Integer leftOnly;
        public Integer rightOnly;

        public String state(int side) {
            return states == null || side >= states.size() ? "MISSING" : states.get(side);
        }

        public boolean present(int side) {
            return !"MISSING".equals(state(side));
        }

        public PairResult pair(int a, int b) {
            if (pairs == null) return null;
            return pairs.get(Math.min(a, b) + "-" + Math.max(a, b));
        }

        public static Map<String, PairResult> newPairs() {
            return new LinkedHashMap<>();
        }
    }

    /** Lightweight projection for the list of comparisons. */
    public static class Info {
        public String id;
        public Instant createdAt;
        public List<SideInfo> sides;
        public Summary summary;

        // ── format before 3-way comparisons ──
        public String leftName;
        public String rightName;
        public String leftLabel;
        public String rightLabel;

        public static Info of(FolderCompare c) {
            Info i = new Info();
            i.id = c.id;
            i.createdAt = c.createdAt;
            i.sides = c.sides;
            i.summary = c.summary;
            return i;
        }
    }
}
