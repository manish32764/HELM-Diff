package com.helmcompare.model;

import java.util.ArrayList;
import java.util.List;

/** Mode 4: historical PROD expectations evaluated across a portfolio of charts. */
public class PortfolioResult {
    public String portfolioId;
    public String portfolioName;
    public String portfolioVersion;
    public String differenceSetId;
    public String differenceSetTitle;
    public List<Expectation> expectations = new ArrayList<>();
    public List<AppResult> apps = new ArrayList<>();
    public List<ExpectationSummary> expectationSummaries = new ArrayList<>();
    public List<CommonChange> commonChanges = new ArrayList<>();
    public Summary summary = new Summary();

    public static class Summary {
        public int chartsAnalyzed;
        public int apps;
        public int compliant;
        public int requireChanges;
        public int requireReview;
        public int withUnexpectedChanges;
        public int withUnusualConfiguration;
    }

    public static class AppResult {
        public String app;
        public String nonProdChartId;
        public String prodChartId;
        /** PROD (validation) or NON_PROD (preparation). */
        public String evaluatedOn;
        /** COMPLIANT, REQUIRES_CHANGES, REQUIRES_REVIEW */
        public String status;
        public int compliant;
        public int requiresChange;
        public int review;
        public int notApplicable;
        public List<ExpectationResult> results = new ArrayList<>();
        public List<DiffEntry> unexpectedChanges = new ArrayList<>();
        public List<DiffEntry> unusualChanges = new ArrayList<>();
        public int otherProdDifferences;
    }

    public static class ExpectationResult {
        public String expectationId;
        /** COMPLIANT, REQUIRES_CHANGE, NOT_APPLICABLE, REVIEW */
        public String status;
        /** SAME, SIMILAR, ABSENT, DIFFERENT */
        public String match;
        public String detail;
        public ConfigItem found;
    }

    public static class ExpectationSummary {
        public String expectationId;
        public String title;
        public int compliant;
        public int requiresChange;
        public int notApplicable;
        public int review;
    }

    public static class CommonChange {
        public String signature;
        public String label;
        public Category category;
        public int count;
        public List<String> apps = new ArrayList<>();
    }
}
