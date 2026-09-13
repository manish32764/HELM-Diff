package com.helmcompare.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A persisted, auditable analysis run. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnalysisRecord {
    public String id;
    public Type type;
    public String title;
    public Instant createdAt;
    public String rerunOf;
    public List<ChartRef> charts = new ArrayList<>();
    /** Request that produced the analysis, retained so it can be repeated. */
    public Map<String, Object> inputs = new LinkedHashMap<>();
    public Map<String, Integer> headline = new LinkedHashMap<>();

    public DiffResult pairwise;
    public VersionAnalysisResult version;
    public PortfolioResult portfolio;

    public Map<String, ReviewMark> reviews = new LinkedHashMap<>();
    public List<AuditEvent> audit = new ArrayList<>();

    public enum Type { PAIRWISE, DIFF_COMPARE, FOUR_CHART, PORTFOLIO }

    public static class ReviewMark {
        /** OPEN, ACCEPTED, NEEDS_CHANGE, NOT_APPLICABLE */
        public String status;
        public String comment;
        public Instant updatedAt;
    }

    public static class AuditEvent {
        public Instant at;
        public String action;
        public String detail;

        public AuditEvent() {
        }

        public AuditEvent(String action, String detail) {
            this.at = Instant.now();
            this.action = action;
            this.detail = detail;
        }
    }

    /** Lightweight projection used by the history list. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Summary {
        public String id;
        public Type type;
        public String title;
        public Instant createdAt;
        public String rerunOf;
        public List<ChartRef> charts;
        public Map<String, Integer> headline;
        public int reviewMarks;

        public static Summary of(AnalysisRecord r) {
            Summary s = new Summary();
            s.id = r.id;
            s.type = r.type;
            s.title = r.title;
            s.createdAt = r.createdAt;
            s.rerunOf = r.rerunOf;
            s.charts = r.charts;
            s.headline = r.headline;
            s.reviewMarks = r.reviews.size();
            return s;
        }
    }
}
