package com.helmcompare.service;

import com.helmcompare.engine.ExpectationEngine;
import com.helmcompare.engine.ItemIndex;
import com.helmcompare.model.AnalysisRecord;
import com.helmcompare.model.ChartRecord;
import com.helmcompare.model.ChartRecord.Environment;
import com.helmcompare.model.Expectation;
import com.helmcompare.model.PortfolioRecord;
import com.helmcompare.model.PortfolioResult.ExpectationResult;
import com.helmcompare.parse.ChartArchive;
import com.helmcompare.parse.ChartArchive.SourceFile;
import com.helmcompare.store.JsonStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class PortfolioService {

    private static final Pattern NON_PROD_SEGMENT = Pattern.compile(
            "^(non[-_]?prod(uction)?|nonprd|np|dev|develop|development|qa|uat|sit|test|stage|staging|preprod|pre[-_]prod|lower)$");
    private static final Pattern PROD_SEGMENT = Pattern.compile("^(prod|prd|production|live)$");
    private static final Pattern NON_PROD_TOKEN = Pattern.compile(
            "(^|[-_.])(non[-_]?prod|nonprd|np|dev|qa|uat|sit|stag(e|ing)|preprod|test)(?=[-_.]|$)");
    private static final Pattern PROD_TOKEN = Pattern.compile("(^|[-_.])(prod|prd|production|live)(?=[-_.]|$)");
    private static final Pattern NOISE_TOKEN = Pattern.compile("(^|[-_.])(values|helm|chart|manifest|rendered)(?=[-_.]|$)");

    private final JsonStore store;
    private final ChartService charts;
    private final AnalysisService analyses;

    public PortfolioService(JsonStore store, ChartService charts, AnalysisService analyses) {
        this.store = store;
        this.charts = charts;
        this.analyses = analyses;
    }

    public record SearchHit(String app, String evaluatedOn, String chartId, ExpectationResult result) {
    }

    public PortfolioRecord upload(List<MultipartFile> files, String name, String version) {
        String original = files.stream().map(MultipartFile::getOriginalFilename).filter(n -> n != null)
                .findFirst().orElse("upload");
        return create(ChartService.expandUploads(files), name, version, original);
    }

    public PortfolioRecord create(List<SourceFile> sources, String name, String version, String originalName) {
        List<SourceFile> yaml = stripRoots(sources.stream().filter(s -> ChartArchive.isYaml(s.path())).toList());
        if (yaml.isEmpty()) throw new IllegalArgumentException("The upload does not contain any YAML files.");

        record Unit(String app, Environment env) {
        }
        Map<Unit, List<SourceFile>> groups = new LinkedHashMap<>();
        for (SourceFile f : yaml) {
            List<String> segments = new ArrayList<>(Arrays.asList(f.path().split("/")));
            String file = segments.remove(segments.size() - 1);
            Environment env = null;
            for (int i = 0; i < segments.size(); i++) {
                Environment e = segmentEnvironment(segments.get(i));
                if (e != null) {
                    env = e;
                    segments.remove(i);
                    break;
                }
            }
            if (env == null) env = filenameEnvironment(file);
            String app;
            String relative;
            if (!segments.isEmpty()) {
                app = segments.get(0);
                List<String> rest = new ArrayList<>(segments.subList(1, segments.size()));
                rest.add(file);
                relative = String.join("/", rest);
            } else {
                app = appFromFilename(file);
                relative = file;
            }
            groups.computeIfAbsent(new Unit(app, env == null ? Environment.OTHER : env), u -> new ArrayList<>())
                    .add(new SourceFile(relative, f.content()));
        }

        PortfolioRecord p = new PortfolioRecord();
        p.id = UUID.randomUUID().toString().substring(0, 8);
        p.name = ChartService.firstNonBlank(name, "Portfolio " + p.id);
        p.version = ChartService.firstNonBlank(version, "");
        p.uploadedAt = Instant.now();
        p.originalName = originalName;
        Map<String, PortfolioRecord.App> apps = new LinkedHashMap<>();
        groups.forEach((unit, files) -> {
            try {
                ChartRecord chart = charts.create(files, new ChartService.NewChart(unit.app(), p.version, unit.env(),
                        "Portfolio", null, null, p.id, unit.app() + " / " + unit.env()));
                PortfolioRecord.App app = apps.computeIfAbsent(unit.app(), a -> {
                    PortfolioRecord.App x = new PortfolioRecord.App();
                    x.app = a;
                    return x;
                });
                switch (unit.env()) {
                    case PROD -> app.prodChartId = chart.id;
                    case NON_PROD -> app.nonProdChartId = chart.id;
                    case OTHER -> app.otherChartId = chart.id;
                }
                if (!chart.warnings.isEmpty()) p.warnings.add(unit.app() + " " + unit.env() + ": " + chart.warnings.size() + " parse warning(s)");
            } catch (IllegalArgumentException e) {
                p.warnings.add(unit.app() + " " + unit.env() + ": " + e.getMessage());
            }
        });
        p.apps = new ArrayList<>(apps.values());
        p.apps.sort((x, y) -> x.app.compareToIgnoreCase(y.app));
        long unpaired = p.apps.stream().filter(a -> a.prodChartId == null || a.nonProdChartId == null).count();
        if (unpaired > 0) p.warnings.add(unpaired + " application(s) do not have both a NON-PROD and a PROD chart.");
        store.savePortfolio(p);
        return p;
    }

    /** Cross-chart identification: evaluates one difference from a Difference Set across the portfolio. */
    public List<SearchHit> search(String portfolioId, String differenceSetId, String entryId) {
        PortfolioRecord p = get(portfolioId);
        Expectation expectation = analyses.expectations(differenceSetId).stream()
                .filter(x -> x.id.equals("exp-" + entryId) || x.id.equals(entryId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Difference not found: " + entryId));
        List<SearchHit> hits = new ArrayList<>();
        for (PortfolioRecord.App app : p.apps) {
            String targetId = app.prodChartId != null ? app.prodChartId : app.nonProdChartId != null ? app.nonProdChartId : app.otherChartId;
            if (targetId == null) continue;
            ItemIndex target = new ItemIndex(store.items(targetId));
            ItemIndex np = app.nonProdChartId == null ? null : new ItemIndex(store.items(app.nonProdChartId));
            ExpectationResult r = ExpectationEngine.evaluate(expectation, target, np, app.prodChartId != null);
            hits.add(new SearchHit(app.app, app.prodChartId != null ? "PROD" : app.nonProdChartId != null ? "NON_PROD" : "OTHER", targetId, r));
        }
        return hits;
    }

    public PortfolioRecord get(String id) {
        return store.portfolio(id).orElseThrow(() -> new NotFoundException("Portfolio not found: " + id));
    }

    public List<PortfolioRecord> list() {
        return store.portfolios();
    }

    public void delete(String id) {
        PortfolioRecord p = get(id);
        for (PortfolioRecord.App app : p.apps) {
            for (String chartId : new String[]{app.nonProdChartId, app.prodChartId, app.otherChartId}) {
                if (chartId != null) store.deleteChart(chartId);
            }
        }
        store.deletePortfolio(id);
    }

    public List<AnalysisRecord.Summary> analysesFor(String id) {
        return analyses.list().stream().filter(s -> s.type == AnalysisRecord.Type.PORTFOLIO).toList();
    }

    /** Removes wrapping folders (e.g. "export/charts-3.1.3/") that are shared by every file. */
    private static List<SourceFile> stripRoots(List<SourceFile> files) {
        List<SourceFile> current = files;
        for (int i = 0; i < 4; i++) {
            List<SourceFile> stripped = ChartArchive.stripCommonRoot(current);
            if (stripped == current) return current;
            Set<String> firsts = stripped.stream().map(f -> f.path().split("/")[0]).collect(Collectors.toSet());
            boolean allFiles = stripped.stream().allMatch(f -> !f.path().contains("/"));
            boolean allEnv = firsts.stream().allMatch(s -> segmentEnvironment(s) != null);
            if (allFiles || (allEnv && current.stream().map(f -> f.path().split("/")[0]).distinct().count() == 1
                    && segmentEnvironment(current.get(0).path().split("/")[0]) == null)) {
                // The shared folder is the application itself (app/prod, app/nonprod) — keep it.
                return current;
            }
            current = stripped;
        }
        return current;
    }

    static Environment segmentEnvironment(String segment) {
        String s = segment.toLowerCase(Locale.ROOT);
        if (NON_PROD_SEGMENT.matcher(s).matches()) return Environment.NON_PROD;
        if (PROD_SEGMENT.matcher(s).matches()) return Environment.PROD;
        return null;
    }

    static Environment filenameEnvironment(String file) {
        String s = stripExtension(file).toLowerCase(Locale.ROOT);
        if (NON_PROD_TOKEN.matcher(s).find()) return Environment.NON_PROD;
        if (PROD_TOKEN.matcher(s).find()) return Environment.PROD;
        return null;
    }

    static String appFromFilename(String file) {
        String s = stripExtension(file).toLowerCase(Locale.ROOT);
        s = NON_PROD_TOKEN.matcher(s).replaceAll("$1");
        s = PROD_TOKEN.matcher(s).replaceAll("$1");
        s = NOISE_TOKEN.matcher(s).replaceAll("$1");
        s = s.replaceAll("[-_.]{2,}", "-").replaceAll("^[-_.]+|[-_.]+$", "");
        return s.isEmpty() ? "chart" : s;
    }

    private static String stripExtension(String file) {
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }
}
