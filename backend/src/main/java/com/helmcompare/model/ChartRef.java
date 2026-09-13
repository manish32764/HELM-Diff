package com.helmcompare.model;

import java.time.Instant;

/** Audit snapshot of a chart that took part in an analysis. */
public class ChartRef {
    public String role;
    public String chartId;
    public String app;
    public String version;
    public ChartRecord.Environment environment;
    public String revision;
    public String originalName;
    public String sha256;
    public Instant uploadedAt;

    public static ChartRef of(String role, ChartRecord c) {
        ChartRef r = new ChartRef();
        r.role = role;
        r.chartId = c.id;
        r.app = c.app;
        r.version = c.version;
        r.environment = c.environment;
        r.revision = c.revision;
        r.originalName = c.originalName;
        r.sha256 = c.sha256;
        r.uploadedAt = c.uploadedAt;
        return r;
    }
}
