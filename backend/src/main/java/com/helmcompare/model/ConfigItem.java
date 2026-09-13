package com.helmcompare.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One logical configuration unit extracted from a Helm chart (an environment variable,
 * a probe block, a resource limit, ...). Sensitive literal values are never stored:
 * {@link #display}, {@link #canonical} and {@link #fields} only carry masked values/fingerprints.
 */
public class ConfigItem {
    /** Position independent logical identity, e.g. {@code env:DB_PASSWORD} or {@code probe:readiness}. */
    public String key;
    /** Grouping used for similarity matching: variable, probe, tsc, resource, volume, ... */
    public String family;
    public String subject;
    public Category category;
    public ValueSource source;
    public boolean sensitive;
    public boolean block;
    public boolean enabled = true;
    public String display;
    public String canonical;
    public Map<String, String> fields = new LinkedHashMap<>();
    /** Secret name / key / AKeyless path the value is obtained from, when applicable. */
    public String reference;
    public String scope;
    public List<Location> locations = new ArrayList<>();

    public static class Location {
        public String file;
        public int line;
        public String path;

        public Location() {
        }

        public Location(String file, int line, String path) {
            this.file = file;
            this.line = line;
            this.path = path;
        }
    }
}
