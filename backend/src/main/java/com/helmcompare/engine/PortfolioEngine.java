package com.helmcompare.engine;

import com.helmcompare.model.Category;
import com.helmcompare.model.ChartRecord;
import com.helmcompare.model.ConfigItem;
import com.helmcompare.model.DiffEntry;
import com.helmcompare.model.DiffEntry.Status;
import com.helmcompare.model.DiffResult;
import com.helmcompare.model.Expectation;
import com.helmcompare.model.PortfolioRecord;
import com.helmcompare.model.PortfolioResult;
import com.helmcompare.model.PortfolioResult.AppResult;
import com.helmcompare.model.PortfolioResult.CommonChange;
import com.helmcompare.model.PortfolioResult.ExpectationResult;
import com.helmcompare.model.PortfolioResult.ExpectationSummary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Mode 4: applies historical PROD expectations across a portfolio of charts. */
public final class PortfolioEngine {

    private static final int UNUSUAL_MIN_APPS = 5;
    private static final Set<Category> SIGNIFICANT = Set.of(Category.SECRETS, Category.PROBES, Category.TSC);

    public interface ChartLoader {
        ChartRecord chart(String id);

        List<ConfigItem> items(String id);
    }

    private PortfolioEngine() {
    }

    public static PortfolioResult analyze(PortfolioRecord portfolio, List<Expectation> expectations, ChartLoader loader) {
        PortfolioResult result = new PortfolioResult();
        result.portfolioId = portfolio.id;
        result.portfolioName = portfolio.name;
        result.portfolioVersion = portfolio.version;
        result.expectations = expectations;
        List<Expectation> selected = expectations.stream().filter(e -> e.selected).toList();

        Set<String> coveredKeys = new HashSet<>();
        Set<String> coveredSubjects = new HashSet<>();
        for (Expectation x : selected) {
            if (x.key != null) coveredKeys.add(x.key);
            if (x.subject != null) coveredSubjects.add(Similarity.normalize(x.subject));
        }

        Map<String, List<DiffEntry>> changesByApp = new LinkedHashMap<>();
        for (PortfolioRecord.App app : portfolio.apps) {
            AppResult ar = new AppResult();
            ar.app = app.app;
            ar.nonProdChartId = app.nonProdChartId;
            ar.prodChartId = app.prodChartId;

            ItemIndex np = app.nonProdChartId == null ? null : new ItemIndex(loader.items(app.nonProdChartId));
            ItemIndex prod = app.prodChartId == null ? null : new ItemIndex(loader.items(app.prodChartId));
            ItemIndex other = app.otherChartId == null ? null : new ItemIndex(loader.items(app.otherChartId));
            ItemIndex target = prod != null ? prod : np != null ? np : other;
            if (target == null) continue;
            result.summary.chartsAnalyzed += (np != null ? 1 : 0) + (prod != null ? 1 : 0) + (other != null ? 1 : 0);
            ar.evaluatedOn = prod != null ? "PROD" : np != null ? "NON_PROD" : "OTHER";

            for (Expectation x : selected) {
                ExpectationResult er = ExpectationEngine.evaluate(x, target, np, prod != null);
                ar.results.add(er);
                switch (er.status) {
                    case "COMPLIANT" -> ar.compliant++;
                    case "REQUIRES_CHANGE" -> ar.requiresChange++;
                    case "REVIEW" -> ar.review++;
                    default -> ar.notApplicable++;
                }
            }
            ar.status = ar.requiresChange > 0 ? "REQUIRES_CHANGES" : ar.review > 0 ? "REQUIRES_REVIEW" : "COMPLIANT";

            if (np != null && prod != null) {
                ChartRecord npChart = loader.chart(app.nonProdChartId);
                ChartRecord prodChart = loader.chart(app.prodChartId);
                DiffResult diff = PairwiseDiffEngine.diff(npChart, loader.items(app.nonProdChartId), "NON-PROD",
                        prodChart, loader.items(app.prodChartId), "PROD");
                List<DiffEntry> changes = diff.entries.stream().filter(e -> e.status != Status.COMMON).toList();
                changesByApp.put(app.app, changes);
                for (DiffEntry e : changes) {
                    boolean covered = coveredKeys.contains(e.key)
                            || ("variable".equals(e.family) && coveredSubjects.contains(Similarity.normalize(e.subject)));
                    if (covered) continue;
                    if (e.security || e.review || SIGNIFICANT.contains(e.category)) {
                        if (ar.unexpectedChanges.size() < 50) ar.unexpectedChanges.add(e);
                    } else {
                        ar.otherProdDifferences++;
                    }
                }
            }
            result.apps.add(ar);
        }

        commonAndUnusual(result, changesByApp);
        summarize(result, selected);
        result.apps.sort(Comparator.comparingInt((AppResult a) -> statusOrder(a.status)).thenComparing(a -> a.app));
        return result;
    }

    private static void commonAndUnusual(PortfolioResult result, Map<String, List<DiffEntry>> changesByApp) {
        Map<String, CommonChange> bySignature = new LinkedHashMap<>();
        for (Map.Entry<String, List<DiffEntry>> e : changesByApp.entrySet()) {
            Set<String> seen = new HashSet<>();
            for (DiffEntry d : e.getValue()) {
                String sig = signature(d);
                if (!seen.add(sig)) continue;
                CommonChange cc = bySignature.computeIfAbsent(sig, s -> {
                    CommonChange c = new CommonChange();
                    c.signature = s;
                    c.label = label(d);
                    c.category = d.category;
                    return c;
                });
                cc.count++;
                cc.apps.add(e.getKey());
            }
        }
        result.commonChanges = bySignature.values().stream()
                .filter(c -> c.count >= 2)
                .sorted(Comparator.comparingInt((CommonChange c) -> -c.count).thenComparing(c -> c.label))
                .limit(100)
                .toList();

        if (changesByApp.size() < UNUSUAL_MIN_APPS) return;
        Map<String, AppResult> apps = new HashMap<>();
        result.apps.forEach(a -> apps.put(a.app, a));
        for (Map.Entry<String, List<DiffEntry>> e : changesByApp.entrySet()) {
            AppResult ar = apps.get(e.getKey());
            for (DiffEntry d : e.getValue()) {
                if (d.category == Category.METADATA) continue;
                if (bySignature.get(signature(d)).count == 1 && ar.unusualChanges.size() < 20) ar.unusualChanges.add(d);
            }
        }
    }

    private static void summarize(PortfolioResult result, List<Expectation> selected) {
        PortfolioResult.Summary s = result.summary;
        s.apps = result.apps.size();
        for (AppResult a : result.apps) {
            switch (a.status) {
                case "COMPLIANT" -> s.compliant++;
                case "REQUIRES_CHANGES" -> s.requireChanges++;
                default -> s.requireReview++;
            }
            if (!a.unexpectedChanges.isEmpty()) s.withUnexpectedChanges++;
            if (!a.unusualChanges.isEmpty()) s.withUnusualConfiguration++;
        }
        for (Expectation x : selected) {
            ExpectationSummary es = new ExpectationSummary();
            es.expectationId = x.id;
            es.title = x.title;
            for (AppResult a : result.apps) {
                for (ExpectationResult r : a.results) {
                    if (!r.expectationId.equals(x.id)) continue;
                    switch (r.status) {
                        case "COMPLIANT" -> es.compliant++;
                        case "REQUIRES_CHANGE" -> es.requiresChange++;
                        case "REVIEW" -> es.review++;
                        default -> es.notApplicable++;
                    }
                }
            }
            result.expectationSummaries.add(es);
        }
    }

    static String signature(DiffEntry d) {
        String subject = "variable".equals(d.family) ? Similarity.normalize(d.subject) : d.key;
        return d.category + "|" + d.intent + "|" + d.transition + "|" + subject;
    }

    private static String label(DiffEntry d) {
        String what = switch (d.intent) {
            case "SECRET_MECHANISM_CHANGED", "SOURCE_CHANGED" -> d.left.source.label + " → " + d.right.source.label;
            case "VALUE_CHANGED" -> "value changed";
            case "REFERENCE_CHANGED" -> "reference changed";
            default -> d.status == Status.ADDED ? "added" : d.status == Status.REMOVED ? "removed" : "settings changed";
        };
        return d.subject + " — " + what;
    }

    private static int statusOrder(String s) {
        return switch (s) {
            case "REQUIRES_CHANGES" -> 0;
            case "REQUIRES_REVIEW" -> 1;
            default -> 2;
        };
    }
}
