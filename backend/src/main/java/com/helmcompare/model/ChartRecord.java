package com.helmcompare.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An uploaded Helm chart snapshot. Charts are immutable; a new revision is a new record. */
public class ChartRecord {
    public String id;
    public String app;
    public String version;
    public Environment environment;
    public String revision;
    public String notes;
    public String originalName;
    public String sha256;
    /** Id of the chart this revision supersedes, if any. */
    public String supersedes;
    /** Set when the chart was uploaded as part of a portfolio set. */
    public String portfolioId;
    public Instant uploadedAt;
    public String chartName;
    public String chartVersion;
    public String appVersion;
    public int itemCount;
    public List<String> files = new ArrayList<>();
    public List<String> warnings = new ArrayList<>();
    /** Per file, the lines that hold sensitive literal values and must be masked in the source viewer. */
    public Map<String, List<Integer>> maskedLines = new LinkedHashMap<>();

    public enum Environment { NON_PROD, PROD, OTHER }

    public String label() {
        String env = environment == null ? "" : switch (environment) {
            case NON_PROD -> "NON-PROD";
            case PROD -> "PROD";
            case OTHER -> "OTHER";
        };
        return (app == null ? "" : app + " ") + (version == null ? "" : version + " ") + env;
    }
}
