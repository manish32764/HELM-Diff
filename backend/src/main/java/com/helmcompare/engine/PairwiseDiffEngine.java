package com.helmcompare.engine;

import com.helmcompare.engine.Similarity.Match;
import com.helmcompare.model.Category;
import com.helmcompare.model.ChartRecord;
import com.helmcompare.model.ChartRef;
import com.helmcompare.model.ConfigItem;
import com.helmcompare.model.DiffEntry;
import com.helmcompare.model.DiffEntry.Status;
import com.helmcompare.model.DiffResult;
import com.helmcompare.model.ValueSource;
import com.helmcompare.model.ValueSource.SourceClass;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Configuration-level comparison of two charts (Mode 1 and building block of every other mode). */
public final class PairwiseDiffEngine {

    private static final Set<String> FUZZY_FAMILIES = Set.of("variable", "volume", "volumemount");

    private PairwiseDiffEngine() {
    }

    public static DiffResult diff(ChartRecord leftChart, List<ConfigItem> leftItems, String leftRole,
                                  ChartRecord rightChart, List<ConfigItem> rightItems, String rightRole) {
        DiffResult result = new DiffResult();
        result.left = ChartRef.of(leftRole, leftChart);
        result.right = ChartRef.of(rightRole, rightChart);
        boolean prodTarget = rightChart.environment == ChartRecord.Environment.PROD
                && leftChart.environment != ChartRecord.Environment.PROD;
        boolean rightProd = rightChart.environment == ChartRecord.Environment.PROD;

        Map<String, ConfigItem> left = new LinkedHashMap<>();
        leftItems.forEach(i -> left.put(i.key, i));
        Map<String, ConfigItem> right = new LinkedHashMap<>();
        rightItems.forEach(i -> right.put(i.key, i));

        List<DiffEntry> entries = new ArrayList<>();
        for (ConfigItem l : leftItems) {
            ConfigItem r = right.get(l.key);
            if (r != null) entries.add(entry(l, r, "KEY", null, prodTarget, rightProd));
        }
        List<ConfigItem> onlyLeft = new ArrayList<>(leftItems.stream().filter(i -> !right.containsKey(i.key)).toList());
        List<ConfigItem> onlyRight = new ArrayList<>(rightItems.stream().filter(i -> !left.containsKey(i.key)).toList());

        // Same key apart from scope suffix (e.g. probe:readiness vs probe:readiness@app).
        pairUp(onlyLeft, onlyRight, (l, r) -> baseKey(l.key).equals(baseKey(r.key)) && l.family.equals(r.family) ? 1.0 : 0.0,
                1.0, "KEY", entries, prodTarget, rightProd);
        // Same variable delivered through a different mechanism (env:X ↔ secret:X).
        pairUp(onlyLeft, onlyRight, (l, r) -> "variable".equals(l.family) && "variable".equals(r.family)
                        && Similarity.normalize(l.subject).equals(Similarity.normalize(r.subject)) ? 1.0 : 0.0,
                1.0, "CORRELATED", entries, prodTarget, rightProd);
        // Renamed configuration (DB_PASS ↔ DB_PASSWORD).
        pairUp(onlyLeft, onlyRight, (l, r) -> FUZZY_FAMILIES.contains(l.family) && l.family.equals(r.family)
                        ? Similarity.nameSimilarity(l.subject, r.subject) : 0.0,
                Similarity.NAME_THRESHOLD, "SIMILAR_NAME", entries, prodTarget, rightProd);

        onlyLeft.forEach(l -> entries.add(entry(l, null, "KEY", null, prodTarget, rightProd)));
        onlyRight.forEach(r -> entries.add(entry(null, r, "KEY", null, prodTarget, rightProd)));

        entries.sort(Comparator.comparingInt((DiffEntry e) -> statusOrder(e.status))
                .thenComparing(e -> e.category.ordinal())
                .thenComparing(e -> e.subject == null ? "" : e.subject));
        result.entries = entries;
        summarize(result, left.size() + onlyRight.size());
        return result;
    }

    private interface Scorer {
        double score(ConfigItem l, ConfigItem r);
    }

    private static void pairUp(List<ConfigItem> onlyLeft, List<ConfigItem> onlyRight, Scorer scorer, double threshold,
                               String matchType, List<DiffEntry> entries, boolean prodTarget, boolean rightProd) {
        Iterator<ConfigItem> it = onlyLeft.iterator();
        while (it.hasNext()) {
            ConfigItem l = it.next();
            ConfigItem best = null;
            double bestScore = 0;
            for (ConfigItem r : onlyRight) {
                double s = scorer.score(l, r);
                if (s >= threshold && s > bestScore) {
                    best = r;
                    bestScore = s;
                }
            }
            if (best != null) {
                entries.add(entry(l, best, matchType, "SIMILAR_NAME".equals(matchType) ? bestScore : null, prodTarget, rightProd));
                onlyRight.remove(best);
                it.remove();
            }
        }
    }

    static DiffEntry entry(ConfigItem l, ConfigItem r, String matchType, Double similarity, boolean prodTarget, boolean rightProd) {
        DiffEntry e = new DiffEntry();
        ConfigItem primary = r != null ? r : l;
        e.key = primary.key;
        e.subject = primary.subject;
        e.family = primary.family;
        e.category = (l != null && l.category == Category.SECRETS) || (r != null && r.category == Category.SECRETS)
                ? Category.SECRETS : primary.category;
        e.left = l;
        e.right = r;
        e.matchType = matchType;
        e.nameSimilarity = similarity;
        e.id = shortHash((l == null ? "" : l.key) + "|" + (r == null ? "" : r.key));

        if (l == null) e.status = Status.ADDED;
        else if (r == null) e.status = Status.REMOVED;
        else if (!"KEY".equals(matchType)) e.status = Status.CHANGED;
        else e.status = Similarity.compareStates(l, r) == Match.SAME ? Status.COMMON : Status.CHANGED;

        if (e.status == Status.CHANGED) e.fieldChanges = Similarity.fieldChanges(l, r);
        classify(e);

        e.security = e.status != Status.COMMON && (e.category == Category.SECRETS
                || (l != null && (l.sensitive || Similarity.isSecretClass(l)))
                || (r != null && (r.sensitive || Similarity.isSecretClass(r))));
        e.prodSpecific = prodTarget && e.status != Status.COMMON;

        if ("SIMILAR_NAME".equals(matchType)) {
            e.review = true;
            e.notes.add(String.format("Matched by name similarity (%d%%): %s ↔ %s — confirm this is the same configuration.",
                    Math.round(similarity * 100), l.subject, r.subject));
        }
        if ("CORRELATED".equals(matchType)) {
            e.notes.add("The same variable is provided through a different mechanism (" + l.key + " → " + r.key + ").");
        }
        if (rightProd && r != null && r.sensitive && r.source == ValueSource.LITERAL) {
            e.review = true;
            e.notes.add("Sensitive value is stored as plain text in the PROD chart.");
        }
        if ("SECRET_MECHANISM_CHANGED".equals(e.intent) && Similarity.sourceClass(r) == SourceClass.PLAIN
                && Similarity.isSecretClass(l)) {
            e.review = true;
            e.notes.add("Secret handling is downgraded to plain text.");
        }
        if (e.status == Status.CHANGED && ((l.source == ValueSource.TEMPLATE) || (r.source == ValueSource.TEMPLATE))) {
            e.review = true;
            e.notes.add("One side uses a Helm expression that could not be resolved from values.");
        }
        if (rightProd && e.status == Status.REMOVED && ("probe".equals(e.family) || "tsc".equals(e.family))) {
            e.review = true;
            e.notes.add("The PROD chart no longer has " + e.subject + ".");
        }
        return e;
    }

    private static void classify(DiffEntry e) {
        ConfigItem l = e.left;
        ConfigItem r = e.right;
        String family = e.family;
        SourceClass lc = Similarity.sourceClass(l);
        SourceClass rc = Similarity.sourceClass(r);
        e.transition = (lc == null ? "∅" : lc.name()) + "→" + (rc == null ? "∅" : rc.name());
        String prefix = "probe".equals(family) ? "PROBE" : "tsc".equals(family) ? "TSC"
                : e.category == Category.SECRETS ? "SECRET" : null;
        switch (e.status) {
            case COMMON -> {
                e.intent = "COMMON";
                e.summary = e.subject + " is the same";
            }
            case ADDED -> {
                e.intent = prefix == null ? "ADDED" : prefix + "_ADDED";
                e.summary = e.subject + " added" + ("variable".equals(family) ? " (" + r.source.label + ")" : "");
            }
            case REMOVED -> {
                e.intent = prefix == null ? "REMOVED" : prefix + "_REMOVED";
                e.summary = e.subject + " removed";
            }
            case CHANGED -> {
                String renamed = "SIMILAR_NAME".equals(e.matchType) ? "Renamed " + l.subject + " → " + r.subject + "; " : "";
                boolean secretRelated = l.sensitive || r.sensitive || Similarity.isSecretClass(l) || Similarity.isSecretClass(r);
                if (lc != rc) {
                    e.intent = secretRelated ? "SECRET_MECHANISM_CHANGED" : "SOURCE_CHANGED";
                    e.summary = renamed + e.subject + ": " + l.source.label + " → " + r.source.label;
                } else if (l.enabled != r.enabled) {
                    e.intent = (prefix == null ? "BLOCK" : prefix) + (r.enabled ? "_ENABLED" : "_DISABLED");
                    e.summary = renamed + e.subject + (r.enabled ? " enabled" : " disabled");
                } else if (l.block || r.block) {
                    e.intent = prefix == null || "SECRET".equals(prefix) ? "BLOCK_CHANGED" : prefix + "_CHANGED";
                    e.summary = renamed + e.subject + ": " + describeFields(e.fieldChanges);
                } else if (Similarity.isSecretClass(l)) {
                    e.intent = "REFERENCE_CHANGED";
                    e.summary = renamed + e.subject + ": " + r.source.label + " reference " + l.reference + " → " + r.reference;
                } else {
                    e.intent = "VALUE_CHANGED";
                    e.summary = renamed + e.subject + ": " + l.display + " → " + r.display;
                }
            }
        }
    }

    static String describeFields(List<DiffEntry.FieldChange> changes) {
        if (changes.isEmpty()) return "settings changed";
        DiffEntry.FieldChange first = changes.get(0);
        String head = switch (first.kind) {
            case "ADDED" -> first.path + " added (" + first.right + ")";
            case "REMOVED" -> first.path + " removed";
            default -> first.path + " " + first.left + " → " + first.right;
        };
        return changes.size() == 1 ? head : head + " (+" + (changes.size() - 1) + " more)";
    }

    private static void summarize(DiffResult result, int totalItems) {
        DiffResult.Summary s = result.summary;
        s.totalItems = totalItems;
        for (Category c : Category.values()) s.differencesByCategory.put(c.name(), 0);
        for (DiffEntry e : result.entries) {
            switch (e.status) {
                case COMMON -> s.common++;
                case ADDED -> s.added++;
                case REMOVED -> s.removed++;
                case CHANGED -> s.changed++;
            }
            if (e.status != Status.COMMON) {
                s.differences++;
                s.differencesByCategory.merge(e.category.name(), 1, Integer::sum);
            }
            if (e.security) s.security++;
            if (e.prodSpecific) s.prodSpecific++;
            if (e.review) s.review++;
        }
        s.differencesByCategory.values().removeIf(v -> v == 0);
    }

    private static int statusOrder(Status s) {
        return switch (s) {
            case CHANGED -> 0;
            case ADDED -> 1;
            case REMOVED -> 2;
            case COMMON -> 3;
        };
    }

    private static String baseKey(String key) {
        int at = key.indexOf('@');
        return at < 0 ? key : key.substring(0, at);
    }

    static String shortHash(String s) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
