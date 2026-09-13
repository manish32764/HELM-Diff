package com.helmcompare.model;

import java.time.Instant;
import java.util.List;

/** Comparison of two parent folders whose same-named sub-folders (one per microservice) are matched. */
public class FolderCompare {
    public String id;
    public Instant createdAt;
    public String leftName;
    public String rightName;
    public String leftLabel;
    public String rightLabel;
    public Summary summary = new Summary();
    public Node root;

    public static class Summary {
        public int leftFolders;
        public int rightFolders;
        public int matchedFolders;
        public int leftOnlyFolders;
        public int rightOnlyFolders;
        public int identicalFolders;
        public int differentFolders;
        public int files;
        public int identicalFiles;
        public int logicallySameFiles;
        public int differentFiles;
        public int leftOnlyFiles;
        public int rightOnlyFiles;
    }

    public static class Node {
        public String name;
        public String path;
        public boolean dir;
        /** IDENTICAL, LOGICALLY_IDENTICAL, DIFFERS, LEFT_ONLY, RIGHT_ONLY */
        public String status;
        /** PRESENT, EMPTY, MISSING */
        public String leftState;
        public String rightState;
        public String reason;
        public int differences;
        public long leftSize = -1;
        public long rightSize = -1;
        // folder counts
        public int identical;
        public int logicallySame;
        public int differs;
        public int leftOnly;
        public int rightOnly;
        public List<Node> children;
    }

    /** Lightweight projection for the list of comparisons. */
    public static class Info {
        public String id;
        public Instant createdAt;
        public String leftName;
        public String rightName;
        public String leftLabel;
        public String rightLabel;
        public Summary summary;

        public static Info of(FolderCompare c) {
            Info i = new Info();
            i.id = c.id;
            i.createdAt = c.createdAt;
            i.leftName = c.leftName;
            i.rightName = c.rightName;
            i.leftLabel = c.leftLabel;
            i.rightLabel = c.rightLabel;
            i.summary = c.summary;
            return i;
        }
    }
}
