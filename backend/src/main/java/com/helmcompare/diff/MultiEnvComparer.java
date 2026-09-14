package com.helmcompare.diff;

import com.helmcompare.diff.EnvVarExtractor.EnvVar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Environment variables of two or three sides in one table. The first two sides are paired by {@link EnvVarComparer}
 * (exact, then similar names); a third side is paired against the variables already in the table the same way.
 * A row compares the value every side's container receives.
 */
public final class MultiEnvComparer {

    /**
     * Lists are indexed by side (all sides of the folder comparison); sides that are not compared hold null / empty.
     *
     * @param match      SAME_NAME, or SIMILAR_NAME when some side uses a similar name
     * @param comparison SAME, VALUE_DIFFERS or UNVERIFIED over the sides that define the variable (null for one side)
     * @param missingIn  compared sides that do not define the variable
     */
    public record Row(String id, List<EnvVar> vars, List<List<EnvVar>> others, String match, Integer similarity,
                      String comparison, boolean sourceChanged, boolean duplicateConflict, List<Integer> missingIn) {
    }

    /**
     * @param complete rows defined in every compared side; same / valueDiffers / unverified count those rows only
     * @param missing  per side: rows not defined in that side
     * @param counts   per side: variables defined
     */
    public record Summary(int total, int complete, int partial, List<Integer> missing, int similarNames, int same,
                          int valueDiffers, int unverified, int sourceChanged, int duplicates, List<Integer> counts) {
    }

    public record Result(List<Row> rows, Summary summary) {
    }

    private static final class Acc {
        final EnvVar[] vars;
        final List<List<EnvVar>> others;
        boolean similar;
        int similarity = 100;

        Acc(int n) {
            vars = new EnvVar[n];
            others = new ArrayList<>();
            for (int i = 0; i < n; i++) others.add(List.of());
        }

        EnvVar first() {
            for (EnvVar v : vars) if (v != null) return v;
            throw new IllegalStateException("empty row");
        }

        void similar(EnvVarComparer.Row row) {
            if (!"SIMILAR_NAME".equals(row.match())) return;
            similar = true;
            similarity = Math.min(similarity, row.similarity() == null ? 100 : row.similarity());
        }
    }

    private MultiEnvComparer() {
    }

    /**
     * @param sides      the compared sides (2 or 3), ascending
     * @param varsBySide variables per side, indexed like all sides of the comparison
     */
    public static Result compare(int[] sides, List<List<EnvVar>> varsBySide) {
        int n = varsBySide.size();
        List<Acc> rows = new ArrayList<>();
        int a = sides[0];
        int b = sides[1];
        for (EnvVarComparer.Row row : EnvVarComparer.compare(varsBySide.get(a), varsBySide.get(b)).rows()) {
            Acc acc = new Acc(n);
            acc.vars[a] = row.left();
            acc.vars[b] = row.right();
            acc.others.set(a, row.leftOthers());
            acc.others.set(b, row.rightOthers());
            acc.similar(row);
            rows.add(acc);
        }
        for (int k = 2; k < sides.length; k++) {
            int c = sides[k];
            Map<EnvVar, Acc> byRepresentative = new IdentityHashMap<>();
            List<EnvVar> representatives = new ArrayList<>();
            for (Acc acc : rows) {
                byRepresentative.put(acc.first(), acc);
                representatives.add(acc.first());
            }
            for (EnvVarComparer.Row row : EnvVarComparer.compare(representatives, varsBySide.get(c)).rows()) {
                if ("LEFT_ONLY".equals(row.status())) continue;
                Acc acc = "COMMON".equals(row.status()) ? byRepresentative.get(row.left()) : null;
                if (acc == null) {
                    acc = new Acc(n);
                    rows.add(acc);
                }
                acc.vars[c] = row.right();
                acc.others.set(c, row.rightOthers());
                acc.similar(row);
            }
        }

        rows.sort(Comparator.comparing((Acc r) -> r.first().name().toLowerCase(Locale.ROOT)).thenComparing(r -> r.first().name()));
        List<Row> out = new ArrayList<>();
        int complete = 0, similar = 0, same = 0, differs = 0, unverified = 0, sourceChanged = 0, duplicates = 0;
        int[] missing = new int[n];
        int[] counts = new int[n];
        for (Acc acc : rows) {
            List<Integer> missingIn = new ArrayList<>();
            List<EnvVar> present = new ArrayList<>();
            boolean conflict = false;
            boolean duplicated = false;
            for (int s : sides) {
                EnvVar v = acc.vars[s];
                if (v == null) {
                    missingIn.add(s);
                    missing[s]++;
                    continue;
                }
                counts[s]++;
                present.add(v);
                duplicated |= !acc.others.get(s).isEmpty();
                conflict |= EnvVarComparer.conflict(v, acc.others.get(s));
            }
            String comparison = present.size() < 2 ? null : compareValues(present);
            boolean changed = present.stream().map(EnvVarComparer::family).distinct().count() > 1;
            out.add(new Row("e" + (out.size() + 1), Arrays.asList(acc.vars), acc.others, acc.similar ? "SIMILAR_NAME" : "SAME_NAME",
                    acc.similar ? acc.similarity : 100, comparison, changed, conflict, missingIn));
            if (acc.similar) similar++;
            if (changed) sourceChanged++;
            if (duplicated) duplicates++;
            if (!missingIn.isEmpty()) continue;
            complete++;
            switch (comparison) {
                case "SAME" -> same++;
                case "VALUE_DIFFERS" -> differs++;
                default -> unverified++;
            }
        }
        return new Result(out, new Summary(out.size(), complete, out.size() - complete, Arrays.stream(missing).boxed().toList(),
                similar, same, differs, unverified, sourceChanged, duplicates, Arrays.stream(counts).boxed().toList()));
    }

    /** VALUE_DIFFERS when any two sides differ, SAME when every pair is the same, otherwise UNVERIFIED. */
    private static String compareValues(List<EnvVar> vars) {
        boolean allSame = true;
        for (int i = 0; i < vars.size(); i++) {
            for (int j = i + 1; j < vars.size(); j++) {
                String result = EnvVarComparer.compareValues(vars.get(i), vars.get(j));
                if ("VALUE_DIFFERS".equals(result)) return result;
                if (!"SAME".equals(result)) allSame = false;
            }
        }
        return allSame ? "SAME" : "UNVERIFIED";
    }
}
