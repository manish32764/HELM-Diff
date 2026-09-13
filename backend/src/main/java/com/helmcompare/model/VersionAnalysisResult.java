package com.helmcompare.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of analysing a historical pair (A = old NON-PROD, B = old PROD) against a newer
 * version (C = new NON-PROD, D = new PROD, optional). Serves Mode 2 (diff-to-diff) and
 * Mode 3 (four-chart analysis).
 */
public class VersionAnalysisResult {
    public ChartRef a;
    public ChartRef b;
    public ChartRef c;
    public ChartRef d;
    /** True when a new PROD chart (D) is present, i.e. validation rather than preparation. */
    public boolean validation;

    public DiffResult historicalDiff;
    public DiffResult currentDiff;

    public List<CorrelatedChange> correlations = new ArrayList<>();
    public List<Assessment> nonProdAssessment = new ArrayList<>();
    public List<MatrixRow> matrix = new ArrayList<>();
    public Summary summary = new Summary();

    /** One historical PROD change (or new PROD change) correlated across the two version pairs. */
    public static class CorrelatedChange {
        public String id;
        public String key;
        public String subject;
        public Category category;
        /** CARRIED_FORWARD, CHANGED_IMPLEMENTATION, MISSING, NEW_PROD_CHANGE, NO_LONGER_APPLICABLE, ALREADY_IN_BASELINE */
        public String classification;
        /** SAME, SIMILAR, DIFFERENT, UNDETERMINED */
        public String similarity;
        /** IMPLEMENTED, IMPLEMENTED_DIFFERENTLY, MISSING, NO_LONGER_PRESENT, NEW_CHANGE, UNEXPECTED */
        public String validation;
        public DiffEntry historical;
        public DiffEntry current;
        public ConfigItem stateA;
        public ConfigItem stateB;
        public ConfigItem stateC;
        public ConfigItem stateD;
        public List<DiffEntry.FieldChange> implementationDifferences = new ArrayList<>();
        public String explanation;
        public boolean security;
        public boolean review;
    }

    /** Assessment of one historical PROD change against the new NON-PROD chart. */
    public static class Assessment {
        public String id;
        public String key;
        public String subject;
        public Category category;
        /** ALREADY_SATISFIED, REQUIRES_CHANGE, DIFFERENT_IMPLEMENTATION, POTENTIALLY_IRRELEVANT, REVIEW */
        public String status;
        public DiffEntry historical;
        public ConfigItem candidate;
        public String recommendation;
        public boolean security;
    }

    public static class MatrixRow {
        public String key;
        public String subject;
        public Category category;
        public List<Cell> cells = new ArrayList<>();
        /** CONSISTENT, CARRIED_FORWARD, MISSING, CHANGED_IMPLEMENTATION, NEW_PROD_CHANGE, NO_LONGER_APPLICABLE, VERSION_CHANGE, UNCHANGED, REVIEW, REQUIRES_CHANGE, ALREADY_SATISFIED, ... */
        public String assessment;
        public boolean allEqual;
        public String referenceId;
    }

    public static class Cell {
        public boolean present;
        public String label;
        public String detail;
        public ValueSource source;

        public Cell() {
        }

        public Cell(boolean present, String label, String detail, ValueSource source) {
            this.present = present;
            this.label = label;
            this.detail = detail;
            this.source = source;
        }
    }

    public static class Summary {
        public int historicalChanges;
        public int currentChanges;
        public int carriedForward;
        public int changedImplementation;
        public int missing;
        public int newProdChanges;
        public int noLongerApplicable;
        public int alreadyInBaseline;
        public int security;
        public int review;

        public int npAlreadySatisfied;
        public int npRequiresChange;
        public int npDifferentImplementation;
        public int npPotentiallyIrrelevant;
        public int npReview;

        public int implemented;
        public int implementedDifferently;
        public int validationMissing;
        public int noLongerPresent;
        public int newChanges;
        public int unexpected;

        /** CONSISTENT, CONSISTENT_WITH_REVIEW, INCONSISTENT, PREPARATION */
        public String verdict;
        public String verdictMessage;
        public int score;
    }
}
