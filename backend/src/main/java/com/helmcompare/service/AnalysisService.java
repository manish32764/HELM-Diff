package com.helmcompare.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmcompare.engine.ExpectationEngine;
import com.helmcompare.engine.PairwiseDiffEngine;
import com.helmcompare.engine.PortfolioEngine;
import com.helmcompare.engine.VersionAnalysisEngine;
import com.helmcompare.engine.VersionAnalysisEngine.Input;
import com.helmcompare.model.AnalysisRecord;
import com.helmcompare.model.AnalysisRecord.AuditEvent;
import com.helmcompare.model.AnalysisRecord.Type;
import com.helmcompare.model.ChartRecord;
import com.helmcompare.model.ChartRef;
import com.helmcompare.model.ConfigItem;
import com.helmcompare.model.DiffResult;
import com.helmcompare.model.Expectation;
import com.helmcompare.model.PortfolioRecord;
import com.helmcompare.model.PortfolioResult;
import com.helmcompare.model.VersionAnalysisResult;
import com.helmcompare.store.JsonStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AnalysisService {

    private static final Set<String> REVIEW_STATUSES = Set.of("OPEN", "ACCEPTED", "NEEDS_CHANGE", "NOT_APPLICABLE");

    private final JsonStore store;
    private final ChartService charts;
    private final ObjectMapper mapper;

    public AnalysisService(JsonStore store, ChartService charts, ObjectMapper mapper) {
        this.store = store;
        this.charts = charts;
        this.mapper = mapper;
    }

    /** Either an existing Difference Set (pairwise analysis) or two charts. */
    public record PairSource(String differenceSetId, String leftChartId, String rightChartId) {
    }

    // ── Mode 1 ──

    public AnalysisRecord pairwise(String leftId, String rightId, String title, String rerunOf) {
        ChartRecord l = charts.get(leftId);
        ChartRecord r = charts.get(rightId);
        if (l.id.equals(r.id)) throw new IllegalArgumentException("Select two different charts.");
        DiffResult diff = PairwiseDiffEngine.diff(l, store.items(l.id), "Chart A", r, store.items(r.id), "Chart B");

        AnalysisRecord rec = newRecord(Type.PAIRWISE, ChartService.firstNonBlank(title, l.label() + " ↔ " + r.label()), rerunOf);
        rec.charts.add(ChartRef.of("Chart A", l));
        rec.charts.add(ChartRef.of("Chart B", r));
        rec.inputs.put("leftChartId", l.id);
        rec.inputs.put("rightChartId", r.id);
        rec.pairwise = diff;
        rec.headline.put("differences", diff.summary.differences);
        rec.headline.put("prodSpecific", diff.summary.prodSpecific);
        rec.headline.put("security", diff.summary.security);
        rec.headline.put("review", diff.summary.review);
        rec.headline.put("common", diff.summary.common);
        rec.audit.add(new AuditEvent("CREATED", "Compared " + l.label() + " (" + l.revision + ") with " + r.label()
                + " (" + r.revision + "): " + diff.summary.differences + " difference(s)."));
        store.saveAnalysis(rec);
        return rec;
    }

    // ── Modes 2 & 3 ──

    public AnalysisRecord diffCompare(PairSource historical, PairSource current, String title) {
        String[] h = resolve(historical);
        String[] c = resolve(current);
        return versionAnalysis(Type.DIFF_COMPARE, h[0], h[1], c[0], c[1], title, null);
    }

    public AnalysisRecord fourChart(String a, String b, String c, String d, String title) {
        return versionAnalysis(Type.FOUR_CHART, a, b, c, blankToNull(d), title, null);
    }

    private AnalysisRecord versionAnalysis(Type type, String a, String b, String c, String d, String title, String rerunOf) {
        if (a == null || b == null || c == null) throw new IllegalArgumentException("Charts A, B and C are required.");
        if (type == Type.DIFF_COMPARE && d == null) throw new IllegalArgumentException("Both version pairs are required.");
        ChartRecord ca = charts.get(a);
        ChartRecord cb = charts.get(b);
        ChartRecord cc = charts.get(c);
        ChartRecord cd = d == null ? null : charts.get(d);

        VersionAnalysisResult result = VersionAnalysisEngine.analyze(input(ca), input(cb), input(cc), cd == null ? null : input(cd));
        String defaultTitle = ca.app + " · " + ca.version + " → " + cc.version + (type == Type.DIFF_COMPARE ? " diff comparison"
                : cd == null ? " PROD preparation" : " PROD validation");
        AnalysisRecord rec = newRecord(type, ChartService.firstNonBlank(title, defaultTitle), rerunOf);
        rec.charts.add(ChartRef.of("Historical NON-PROD (A)", ca));
        rec.charts.add(ChartRef.of("Historical PROD (B)", cb));
        rec.charts.add(ChartRef.of("New NON-PROD (C)", cc));
        if (cd != null) rec.charts.add(ChartRef.of("New PROD (D)", cd));
        rec.inputs.put("a", a);
        rec.inputs.put("b", b);
        rec.inputs.put("c", c);
        if (d != null) rec.inputs.put("d", d);
        rec.version = result;

        VersionAnalysisResult.Summary s = result.summary;
        if (cd != null) {
            rec.headline.put("carriedForward", s.carriedForward);
            rec.headline.put("missing", s.missing);
            rec.headline.put("changedImplementation", s.changedImplementation);
            rec.headline.put("newProdChanges", s.newProdChanges);
            rec.headline.put("noLongerApplicable", s.noLongerApplicable);
            rec.headline.put("unexpected", s.unexpected);
        } else {
            rec.headline.put("npRequiresChange", s.npRequiresChange);
            rec.headline.put("npAlreadySatisfied", s.npAlreadySatisfied);
        }
        rec.headline.put("review", s.review + s.npReview);
        rec.headline.put("score", s.score);
        rec.audit.add(new AuditEvent("CREATED", s.verdictMessage));
        store.saveAnalysis(rec);
        return rec;
    }

    private Input input(ChartRecord c) {
        return new Input(c, store.items(c.id));
    }

    private String[] resolve(PairSource source) {
        if (source == null) throw new IllegalArgumentException("Both version pairs are required.");
        if (source.differenceSetId() != null && !source.differenceSetId().isBlank()) {
            AnalysisRecord set = get(source.differenceSetId());
            if (set.type != Type.PAIRWISE) throw new IllegalArgumentException("Selected analysis is not a Difference Set.");
            return new String[]{(String) set.inputs.get("leftChartId"), (String) set.inputs.get("rightChartId")};
        }
        if (source.leftChartId() == null || source.rightChartId() == null) {
            throw new IllegalArgumentException("Select a Difference Set or two charts for each pair.");
        }
        return new String[]{source.leftChartId(), source.rightChartId()};
    }

    // ── Mode 4 ──

    public List<Expectation> expectations(String differenceSetId) {
        AnalysisRecord set = get(differenceSetId);
        if (set.pairwise == null) throw new IllegalArgumentException("Selected analysis is not a Difference Set.");
        return ExpectationEngine.derive(set.pairwise);
    }

    public AnalysisRecord portfolio(String portfolioId, String differenceSetId, List<Expectation> expectations,
                                    String title, String rerunOf) {
        PortfolioRecord portfolio = store.portfolio(portfolioId)
                .orElseThrow(() -> new NotFoundException("Portfolio not found: " + portfolioId));
        AnalysisRecord set = differenceSetId == null || differenceSetId.isBlank() ? null : get(differenceSetId);
        List<Expectation> exps = expectations != null && !expectations.isEmpty() ? expectations
                : set != null ? ExpectationEngine.derive(set.pairwise) : List.of(ExpectationEngine.noPlainSecretsPolicy());

        PortfolioResult result = PortfolioEngine.analyze(portfolio, exps, new PortfolioEngine.ChartLoader() {
            @Override
            public ChartRecord chart(String id) {
                return charts.get(id);
            }

            @Override
            public List<ConfigItem> items(String id) {
                return store.items(id);
            }
        });
        if (set != null) {
            result.differenceSetId = set.id;
            result.differenceSetTitle = set.title;
        }

        AnalysisRecord rec = newRecord(Type.PORTFOLIO, ChartService.firstNonBlank(title,
                portfolio.name + " · portfolio analysis"), rerunOf);
        if (set != null) rec.charts.addAll(set.charts);
        rec.inputs.put("portfolioId", portfolioId);
        if (set != null) rec.inputs.put("differenceSetId", set.id);
        rec.inputs.put("expectations", exps);
        rec.portfolio = result;
        PortfolioResult.Summary s = result.summary;
        rec.headline.put("apps", s.apps);
        rec.headline.put("compliant", s.compliant);
        rec.headline.put("requireChanges", s.requireChanges);
        rec.headline.put("requireReview", s.requireReview);
        rec.headline.put("withUnexpectedChanges", s.withUnexpectedChanges);
        rec.audit.add(new AuditEvent("CREATED", s.chartsAnalyzed + " chart(s) across " + s.apps + " application(s) analysed against "
                + exps.stream().filter(e -> e.selected).count() + " expectation(s)."));
        store.saveAnalysis(rec);
        return rec;
    }

    // ── audit, review, re-run ──

    public AnalysisRecord rerun(String id, Map<String, String> overrides) {
        AnalysisRecord original = get(id);
        Map<String, Object> in = new LinkedHashMap<>(original.inputs);
        if (overrides != null) overrides.forEach((k, v) -> {
            if (v != null && !v.isBlank()) in.put(k, v);
        });
        AnalysisRecord next = switch (original.type) {
            case PAIRWISE -> pairwise(str(in, "leftChartId"), str(in, "rightChartId"), null, original.id);
            case DIFF_COMPARE, FOUR_CHART -> versionAnalysis(original.type, str(in, "a"), str(in, "b"), str(in, "c"),
                    blankToNull(str(in, "d")), null, original.id);
            case PORTFOLIO -> portfolio(str(in, "portfolioId"), str(in, "differenceSetId"),
                    mapper.convertValue(in.get("expectations"), new TypeReference<List<Expectation>>() {
                    }), null, original.id);
        };
        original.audit.add(new AuditEvent("RERUN", "Analysis repeated as " + next.id));
        store.saveAnalysis(original);
        return next;
    }

    public AnalysisRecord review(String id, String itemId, String status, String comment) {
        if (status == null || !REVIEW_STATUSES.contains(status)) {
            throw new IllegalArgumentException("Review status must be one of " + REVIEW_STATUSES);
        }
        AnalysisRecord rec = get(id);
        AnalysisRecord.ReviewMark mark = new AnalysisRecord.ReviewMark();
        mark.status = status;
        mark.comment = comment;
        mark.updatedAt = Instant.now();
        rec.reviews.put(itemId, mark);
        rec.audit.add(new AuditEvent("REVIEW", itemId + " marked " + status + (comment == null || comment.isBlank() ? "" : " — " + comment)));
        store.saveAnalysis(rec);
        return rec;
    }

    public AnalysisRecord get(String id) {
        return store.analysis(id).orElseThrow(() -> new NotFoundException("Analysis not found: " + id));
    }

    public List<AnalysisRecord.Summary> list() {
        return store.analyses();
    }

    public void delete(String id) {
        get(id);
        store.deleteAnalysis(id);
    }

    private static AnalysisRecord newRecord(Type type, String title, String rerunOf) {
        AnalysisRecord rec = new AnalysisRecord();
        rec.id = UUID.randomUUID().toString().substring(0, 8);
        rec.type = type;
        rec.title = title;
        rec.createdAt = Instant.now();
        rec.rerunOf = rerunOf;
        return rec;
    }

    private static String str(Map<String, Object> in, String key) {
        Object v = in.get(key);
        return v == null ? null : v.toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
