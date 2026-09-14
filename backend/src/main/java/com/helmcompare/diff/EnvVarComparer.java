package com.helmcompare.diff;

import com.helmcompare.diff.EnvVarExtractor.EnvVar;
import com.helmcompare.engine.Similarity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pairs environment variables / secrets of two sides: exact name first, then names that are
 * "humanly" similar — environment prefixes/suffixes (PROD, NP, AKEYLESS …) are ignored and secret keys
 * or AKeyless path segments are considered too (e.g. {@code DB_PASSWORD} ↔ {@code /prod/payments/db-password}).
 * Paired variables are compared by the value the container actually receives, whatever its source.
 */
public final class EnvVarComparer {

    public static final double SIMILARITY_THRESHOLD = 0.8;

    private static final Set<String> NOISE_TOKENS = Set.of("PROD", "PRD", "PRODUCTION", "NONPROD", "NON", "NP", "DEV",
            "QA", "UAT", "SIT", "STG", "STAGE", "STAGING", "TEST", "PREPROD", "AKEYLESS", "SECRET", "SECRETS", "ENV", "VAR");

    /**
     * @param status            COMMON, LEFT_ONLY, RIGHT_ONLY
     * @param match             SAME_NAME or SIMILAR_NAME (common rows only)
     * @param comparison        SAME, VALUE_DIFFERS, UNVERIFIED (common rows only)
     * @param sourceChanged     the value comes from a different kind of source, e.g. plain text → AKeyless
     * @param leftOthers        further definitions of the same variable on the left (e.g. in envVars and as a secret item)
     * @param duplicateConflict a further definition on either side yields a different value than the one shown
     */
    public record Row(String id, String status, String match, Integer similarity, EnvVar left, EnvVar right, String comparison,
                      boolean sourceChanged, List<EnvVar> leftOthers, List<EnvVar> rightOthers, boolean duplicateConflict) {
    }

    public record Summary(int total, int common, int leftOnly, int rightOnly, int similarNames, int same, int valueDiffers,
                          int unverified, int sourceChanged, int duplicates, int left, int right) {
    }

    public record Result(List<Row> rows, Summary summary) {
    }

    private record Candidate(int left, int right, double score) {
    }

    private record Group(EnvVar primary, List<EnvVar> others) {
    }

    private EnvVarComparer() {
    }

    public static Result compare(List<EnvVar> leftVars, List<EnvVar> rightVars) {
        List<Group> left = group(leftVars);
        List<Group> right = group(rightVars);
        int[] pairOf = new int[left.size()];
        Arrays.fill(pairOf, -1);
        double[] scoreOf = new double[left.size()];
        boolean[] used = new boolean[right.size()];

        // 1. exact name in the same microservice folder, 2. exact name, 3. same name ignoring case and separators
        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < left.size(); i++) {
                if (pairOf[i] >= 0) continue;
                EnvVar l = left.get(i).primary();
                for (int j = 0; j < right.size(); j++) {
                    if (used[j]) continue;
                    EnvVar r = right.get(j).primary();
                    boolean same = switch (pass) {
                        case 0 -> l.name().equals(r.name())
                                && EnvVarExtractor.topFolder(l.file()).equals(EnvVarExtractor.topFolder(r.file()));
                        case 1 -> l.name().equals(r.name());
                        default -> Similarity.normalize(l.name()).equals(Similarity.normalize(r.name()));
                    };
                    if (same) {
                        pairOf[i] = j;
                        scoreOf[i] = 1;
                        used[j] = true;
                        break;
                    }
                }
            }
        }

        // 4. similar names, best pairs first
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < left.size(); i++) {
            if (pairOf[i] >= 0) continue;
            for (int j = 0; j < right.size(); j++) {
                if (used[j]) continue;
                double score = score(left.get(i).primary(), right.get(j).primary());
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
        int common = 0, similar = 0, same = 0, valueDiffers = 0, unverified = 0, sourceChanged = 0, duplicates = 0,
                leftOnly = 0, rightOnly = 0;
        for (int i = 0; i < left.size(); i++) {
            Group gl = left.get(i);
            if (!gl.others().isEmpty()) duplicates++;
            if (pairOf[i] < 0) {
                rows.add(new Row(null, "LEFT_ONLY", null, null, gl.primary(), null, null, false, gl.others(), List.of(), conflict(gl)));
                leftOnly++;
                continue;
            }
            Group gr = right.get(pairOf[i]);
            if (!gr.others().isEmpty() && gl.others().isEmpty()) duplicates++;
            EnvVar l = gl.primary();
            EnvVar r = gr.primary();
            boolean exact = scoreOf[i] >= 1;
            String comparison = compareValues(l, r);
            boolean changed = !family(l).equals(family(r));
            rows.add(new Row(null, "COMMON", exact ? "SAME_NAME" : "SIMILAR_NAME", exact ? 100 : (int) Math.round(scoreOf[i] * 100),
                    l, r, comparison, changed, gl.others(), gr.others(), conflict(gl) || conflict(gr)));
            common++;
            if (!exact) similar++;
            if (changed) sourceChanged++;
            switch (comparison) {
                case "SAME" -> same++;
                case "VALUE_DIFFERS" -> valueDiffers++;
                default -> unverified++;
            }
        }
        for (int j = 0; j < right.size(); j++) {
            if (used[j]) continue;
            Group gr = right.get(j);
            if (!gr.others().isEmpty()) duplicates++;
            rows.add(new Row(null, "RIGHT_ONLY", null, null, null, gr.primary(), null, false, List.of(), gr.others(), conflict(gr)));
            rightOnly++;
        }

        rows.sort(Comparator.comparing((Row row) -> sortName(row).toLowerCase(Locale.ROOT)).thenComparing(EnvVarComparer::sortName));
        List<Row> numbered = new ArrayList<>();
        for (int k = 0; k < rows.size(); k++) {
            Row row = rows.get(k);
            numbered.add(new Row("e" + (k + 1), row.status(), row.match(), row.similarity(), row.left(), row.right(),
                    row.comparison(), row.sourceChanged(), row.leftOthers(), row.rightOthers(), row.duplicateConflict()));
        }
        return new Result(numbered, new Summary(numbered.size(), common, leftOnly, rightOnly, similar, same, valueDiffers,
                unverified, sourceChanged, duplicates, left.size(), right.size()));
    }

    /**
     * SAME / VALUE_DIFFERS when both values are known (plain text, or AKeyless resolved from the uploaded JSON);
     * otherwise SAME only when both read the very same AKeyless path or secret, else UNVERIFIED.
     */
    static String compareValues(EnvVar l, EnvVar r) {
        String a = l.effectiveValue();
        String b = r.effectiveValue();
        if (a != null && b != null) return a.strip().equals(b.strip()) ? "SAME" : "VALUE_DIFFERS";
        if (a == null && b == null) {
            if (l.akeylessPath() != null && l.akeylessPath().equals(r.akeylessPath())) return "SAME";
            if (l.akeylessPath() == null && r.akeylessPath() == null && l.reference() != null
                    && l.reference().equals(r.reference()) && Objects.equals(l.source(), r.source())) return "SAME";
        }
        return "UNVERIFIED";
    }

    /** Same-named definitions on one side (e.g. envVars and a secret item) collapse into one row; env entries win. */
    private static List<Group> group(List<EnvVar> vars) {
        Map<String, List<EnvVar>> byName = new LinkedHashMap<>();
        int n = 0;
        for (EnvVar v : vars) {
            String key = EnvVarExtractor.KIND_ENV_FROM.equals(v.kind()) ? "" + (n++)
                    : EnvVarExtractor.topFolder(v.file()) + " " + v.name();
            byName.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
        }
        List<Group> out = new ArrayList<>();
        for (List<EnvVar> list : byName.values()) {
            List<EnvVar> sorted = new ArrayList<>(list);
            sorted.sort(Comparator.comparingInt(EnvVarComparer::rank));
            out.add(new Group(sorted.get(0), List.copyOf(sorted.subList(1, sorted.size()))));
        }
        return out;
    }

    private static int rank(EnvVar v) {
        return switch (v.kind()) {
            case EnvVarExtractor.KIND_ENV -> 0;
            case EnvVarExtractor.KIND_SECRET_VALUE -> 1;
            case EnvVarExtractor.KIND_SECRET_ITEM, EnvVarExtractor.KIND_EXTERNAL -> 2;
            default -> 3;
        };
    }

    private static boolean conflict(Group g) {
        return conflict(g.primary(), g.others());
    }

    /** A further definition of the variable on the same side yields another value than the primary one. */
    static boolean conflict(EnvVar p, List<EnvVar> others) {
        for (EnvVar o : others) {
            if (p.effectiveValue() != null && o.effectiveValue() != null) {
                if (!p.effectiveValue().strip().equals(o.effectiveValue().strip())) return true;
            } else if (p.akeylessPath() != null && o.akeylessPath() != null && !p.akeylessPath().equals(o.akeylessPath())) {
                return true;
            }
        }
        return false;
    }

    static String family(EnvVar v) {
        return switch (v.source()) {
            case "PLAIN", "EMPTY", "TEMPLATE" -> "PLAIN";
            default -> v.source();
        };
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
        for (String ref : new String[]{v.reference(), v.akeylessPath(), "AKEYLESS".equals(v.source()) ? v.value() : null}) {
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
