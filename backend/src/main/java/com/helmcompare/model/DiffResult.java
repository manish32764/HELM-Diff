package com.helmcompare.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pairwise comparison of two charts — also retained as a reusable "Difference Set". */
public class DiffResult {
    public ChartRef left;
    public ChartRef right;
    public Summary summary = new Summary();
    public List<DiffEntry> entries = new ArrayList<>();

    public static class Summary {
        public int totalItems;
        public int differences;
        public int common;
        public int added;
        public int removed;
        public int changed;
        public int security;
        public int prodSpecific;
        public int review;
        public Map<String, Integer> differencesByCategory = new LinkedHashMap<>();
    }
}
