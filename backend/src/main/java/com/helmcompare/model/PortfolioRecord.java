package com.helmcompare.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A set of application charts (NON-PROD and/or PROD per application) uploaded together. */
public class PortfolioRecord {
    public String id;
    public String name;
    public String version;
    public Instant uploadedAt;
    public String originalName;
    public List<App> apps = new ArrayList<>();
    public List<String> warnings = new ArrayList<>();

    public static class App {
        public String app;
        public String nonProdChartId;
        public String prodChartId;
        public String otherChartId;
    }
}
