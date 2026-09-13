package com.helmcompare.diff;

import com.helmcompare.diff.EnvVarExtractor.EnvVar;
import com.helmcompare.engine.Similarity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Pairs environment variables / secrets of two sides: exact name first, then names that are
 * "humanly" similar — environment prefixes/suffixes (PROD, NP, AKEYLESS …) are ignored and secret keys
 * or AKeyless path segments are considered too (e.g. {@code DB_PASSWORD} ↔ {@code /prod/payments/db-password}).
 */
public final class EnvVarComparer {

    public static final double SIMILARITY_THRESHOLD = 0.8;

    private static final Set<String> NOISE_TOKENS = Set.of("PROD", "PRD", "PRODUCTION", "NONPROD", "NON", "NP", "DEV",
            "QA", "UAT", "SIT", "STG", "STAGE", "STAGING", "TEST", "PREPROD", "AKEYLESS", "SECRET", "SECRETS", "ENV", "VAR");

    /**
     * @param status     COMMON, LEFT_ONLY, RIGHT_ONLY
     * @param match      SAME_NAME or SIMILAR_NAME (common rows only)
     * @param comparison SAME, VALUE_DIFFERS, SOURCE_CHANGED (common rows only)
     */
    public record Row(String id, String status, String match, Integer similarity, EnvVar left, EnvVar right, String comparison) {
    }

    public record Summary(int total, int common, int leftOnly, int rightOnly, int similarNames, int same, int valueDiffers,
                          int sourceChanged, int left, int right) {
    }

    public record Result(List<Row> rows, Summary summary) {
    }

    private record Candidate(int left, int right, double score) {
    }

    private EnvVarComparer() {
    }

    public static Result compare(List<EnvVar> left, List<EnvVar> right) {
        int[] pairOf = new int[left.size()];
        Arrays.fill(pairOf, -1);
        double[] scoreOf = new double[left.size()];
        boolean[] used = new boolean[right.size()];

        // 1. exact name, 2. same name ignoring case and separators
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < left.size(); i++) {
                if (pairOf[i] >= 0) continue;
                for (int j = 0; j < right.size(); j++) {
                    if (used[j]) continue;
                    boolean same = pass == 0 ? left.get(i).name().equals(right.get(j).name())
                            : Similarity.normalize(left.get(i).name()).equals(Similarity.normalize(right.get(j).name()));
                    if (same) {
                        pairOf[i] = j;
                        scoreOf[i] = 1;
                        used[j] = true;
                        break;
                    }
                }
            }
        }

        // 3. similar names, best pairs first
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < left.size(); i++) {
            if (pairOf[i] >= 0) continue;
            for (int j = 0; j < right.size(); j++) {
                if (used[j]) continue;
                double score = score(left.get(i), right.get(j));
                if (score >= SIMILARITY_THRESHOLD) candidates.add(new Candidate(i, j, score));
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score).reversed());
        for (Candidate c : candidates) {
            if (pairOf[c.left()] >= 0 || used[c.right()]) continue;
            pairOf[c.left()] = c.right();
            scoreOf[c.left()] = c.score();
            used[c.right()] = true;
        }

        List<Row> rows = new ArrayList<>();
        int common = 0, similar = 0, same = 0, valueDiffers = 0, sourceChanged = 0, leftOnly = 0, rightOnly = 0;
        for (int i = 0; i < left.size(); i++) {
            EnvVar l = left.get(i);
            if (pairOf[i] < 0) {
                rows.add(new Row(null, "LEFT_ONLY", null, null, l, null, null));
                leftOnly++;
                continue;
            }
            EnvVar r = right.get(pairOf[i]);
            boolean exact = scoreOf[i] >= 1;
            String comparison = !Objects.equals(l.source(), r.source()) ? "SOURCE_CHANGED"
                    : !Objects.equals(l.value(), r.value()) || !Objects.equals(l.reference(), r.reference()) ? "VALUE_DIFFERS" : "SAME";
            rows.add(new Row(null, "COMMON", exact ? "SAME_NAME" : "SIMILAR_NAME", exact ? 100 : (int) Math.round(scoreOf[i] * 100),
                    l, r, comparison));
            common++;
            if (!exact) similar++;
            switch (comparison) {
                case "SAME" -> same++;
                case "VALUE_DIFFERS" -> valueDiffers++;
                default -> sourceChanged++;
            }
        }
        for (int j = 0; j < right.size(); j++) {
            if (used[j]) continue;
            rows.add(new Row(null, "RIGHT_ONLY", null, null, null, right.get(j), null));
            rightOnly++;
        }

        rows.sort(Comparator.comparing((Row row) -> sortName(row).toLowerCase(Locale.ROOT)).thenComparing(EnvVarComparer::sortName));
        List<Row> numbered = new ArrayList<>();
        for (int k = 0; k < rows.size(); k++) {
            Row row = rows.get(k);
            numbered.add(new Row("e" + (k + 1), row.status(), row.match(), row.similarity(), row.left(), row.right(), row.comparison()));
        }
        return new Result(numbered, new Summary(numbered.size(), common, leftOnly, rightOnly, similar, same, valueDiffers,
                sourceChanged, left.size(), right.size()));
    }

    private static String sortName(Row row) {
        return row.left() != null ? row.left().name() : row.right().name();
    }

    static double score(EnvVar a, EnvVar b) {
        double best = 0;
        for (String x : candidateNames(a)) {
            for (String y : candidateNames(b)) best = Math.max(best, nameScore(x, y));
        }
        return best;
    }

    private static Set<String> candidateNames(EnvVar v) {
        Set<String> names = new LinkedHashSet<>();
        names.add(v.name());
        if (v.secretKey() != null && !v.secretKey().isBlank()) names.add(v.secretKey());
        for (String ref : new String[]{v.reference(), "AKEYLESS".equals(v.source()) ? v.value() : null}) {
            if (ref == null || ref.isBlank()) continue;
            String head = ref.split("\\s+\\(via")[0].split(" › ")[0];
            String[] parts = head.split("[/:]");
            if (parts.length > 0 && !parts[parts.length - 1].isBlank()) names.add(parts[parts.length - 1]);
        }
        return names;
    }

    static double nameScore(String a, String b) {
        List<String> ta = tokens(a);
        List<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) return 0;
        String ja = String.join("_", ta);
        String jb = String.join("_", tb);
        if (ja.equals(jb)) return 0.95;
        List<String> shorter = ta.size() <= tb.size() ? ta : tb;
        List<String> longer = ta.size() <= tb.size() ? tb : ta;
        boolean meaningful = shorter.size() >= 2 || String.join("", shorter).length() >= 6;
        if (meaningful && (isPrefix(shorter, longer) || isSuffix(shorter, longer))) return 0.88;
        return Similarity.nameSimilarity(ja, jb);
    }

    private static List<String> tokens(String name) {
        List<String> all = Arrays.stream(Similarity.normalize(name.replaceAll("([a-z0-9])([A-Z])", "$1_$2")).split("_"))
                .filter(t -> !t.isEmpty()).toList();
        List<String> meaningful = all.stream().filter(t -> !NOISE_TOKENS.contains(t)).toList();
        return meaningful.isEmpty() ? all : meaningful;
    }

    private static boolean isPrefix(List<String> shorter, List<String> longer) {
        return longer.subList(0, shorter.size()).equals(shorter);
    }

    private static boolean isSuffix(List<String> shorter, List<String> longer) {
        return longer.subList(longer.size() - shorter.size(), longer.size()).equals(shorter);
    }
}
