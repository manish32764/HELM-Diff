package com.helmcompare.model;

import java.util.ArrayList;
import java.util.List;

/** Result of comparing one logical configuration item between two charts. */
public class DiffEntry {
    public String id;
    public String key;
    public String subject;
    public String family;
    public Category category;
    public Status status;
    /** KEY (same logical key), SIMILAR_NAME (fuzzy name match), CORRELATED (moved between mechanisms). */
    public String matchType = "KEY";
    public Double nameSimilarity;
    public ConfigItem left;
    public ConfigItem right;
    public List<FieldChange> fieldChanges = new ArrayList<>();
    /** Change family, e.g. PROBE_ADDED, SECRET_MECHANISM_CHANGED, VALUE_CHANGED. */
    public String intent;
    /** Source-class transition, e.g. PLAIN→AKEYLESS. */
    public String transition;
    public String summary;
    public boolean security;
    public boolean prodSpecific;
    public boolean review;
    public List<String> notes = new ArrayList<>();

    public enum Status { COMMON, ADDED, REMOVED, CHANGED }

    public static class FieldChange {
        public String path;
        public String left;
        public String right;
        public String kind;

        public FieldChange() {
        }

        public FieldChange(String path, String left, String right, String kind) {
            this.path = path;
            this.left = left;
            this.right = right;
            this.kind = kind;
        }
    }
}
