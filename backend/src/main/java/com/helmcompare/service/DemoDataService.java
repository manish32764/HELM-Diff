package com.helmcompare.service;

import com.helmcompare.model.AnalysisRecord;
import com.helmcompare.model.ChartRecord;
import com.helmcompare.model.ChartRecord.Environment;
import com.helmcompare.model.PortfolioRecord;
import com.helmcompare.parse.ChartArchive.SourceFile;
import com.helmcompare.store.JsonStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the bundled sample charts (payments-api 3.0.4 / 3.1.3, NON-PROD / PROD) and a generated
 * 40-application portfolio, then runs one analysis of every kind so the application can be explored immediately.
 */
@Service
public class DemoDataService {

    static final String SAMPLE_NOTE = "Sample data";

    private static final String[] APPS = {"accounts", "billing", "cards", "claims", "customers", "documents", "fraud",
            "gateway", "identity", "invoices", "ledger", "loans", "notifications", "orders", "batch", "pricing", "profiles",
            "quotes", "rates", "reports", "rewards", "risk", "search", "settlements", "statements", "tax", "transfers",
            "users", "wallets", "kyc", "audit", "limits", "fx", "inventory", "catalog", "shipping", "returns", "support",
            "analytics", "scheduler"};

    private final JsonStore store;
    private final ChartService charts;
    private final AnalysisService analyses;
    private final PortfolioService portfolios;

    public DemoDataService(JsonStore store, ChartService charts, AnalysisService analyses, PortfolioService portfolios) {
        this.store = store;
        this.charts = charts;
        this.analyses = analyses;
        this.portfolios = portfolios;
    }

    public synchronized Map<String, Object> seed() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (store.charts().stream().anyMatch(c -> SAMPLE_NOTE.equals(c.notes))) {
            out.put("alreadySeeded", true);
            return out;
        }

        Map<String, List<SourceFile>> byChart = new LinkedHashMap<>();
        for (String path : readResource("samples/index.txt").lines().map(String::strip).filter(l -> !l.isEmpty()).toList()) {
            String[] parts = path.split("/", 3);
            byChart.computeIfAbsent(parts[0] + "/" + parts[1], k -> new ArrayList<>())
                    .add(new SourceFile(parts[2], readResource("samples/" + path).getBytes(StandardCharsets.UTF_8)));
        }
        Map<String, String> ids = new LinkedHashMap<>();
        byChart.forEach((key, files) -> {
            String[] parts = key.split("/");
            String[] versionEnv = parts[1].split("-", 2);
            Environment env = versionEnv[1].equals("prod") ? Environment.PROD : Environment.NON_PROD;
            ChartRecord c = charts.create(files, new ChartService.NewChart(parts[0], versionEnv[0], env, "Initial",
                    SAMPLE_NOTE, null, null, key));
            ids.put(parts[1], c.id);
        });

        String a = ids.get("3.0.4-nonprod");
        String b = ids.get("3.0.4-prod");
        String c = ids.get("3.1.3-nonprod");
        String d = ids.get("3.1.3-prod");
        AnalysisRecord diffSet = analyses.pairwise(a, b, "payments-api 3.0.4 PROD Difference Set", null);
        AnalysisRecord prep = analyses.fourChart(a, b, c, null, "payments-api 3.1.3 PROD preparation");
        AnalysisRecord diffCompare = analyses.diffCompare(new AnalysisService.PairSource(diffSet.id, null, null),
                new AnalysisService.PairSource(null, c, d), "payments-api 3.0.4 vs 3.1.3 PROD changes");
        AnalysisRecord validation = analyses.fourChart(a, b, c, d, "payments-api 3.1.3 PROD validation");

        PortfolioRecord portfolio = portfolios.create(generatePortfolio(), "Retail platform 3.1.3", "3.1.3", "generated-sample");
        AnalysisRecord portfolioAnalysis = analyses.portfolio(portfolio.id, diffSet.id, null,
                "Retail platform 3.1.3 · 3.0.4 PROD expectations", null);

        out.put("charts", ids);
        out.put("differenceSetId", diffSet.id);
        out.put("preparationId", prep.id);
        out.put("diffCompareId", diffCompare.id);
        out.put("validationId", validation.id);
        out.put("portfolioId", portfolio.id);
        out.put("portfolioAnalysisId", portfolioAnalysis.id);
        return out;
    }

    private static String readResource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ───────────────────────────── generated portfolio ─────────────────────────────

    private record Variant(boolean db, boolean similarDbName, boolean k8sSecret, boolean token, boolean plainToken,
                           boolean tsc, boolean readiness, boolean region, boolean debugLogs, boolean unusual,
                           boolean prodMissing, boolean sameReplicas) {
        static Variant of(int i) {
            return new Variant(i % 8 != 2, i % 10 == 7, i % 13 == 6, i % 5 != 4, i % 7 == 3, i % 9 != 4, i % 11 != 5,
                    i % 6 != 1, i % 12 == 11, i == 9, i % 15 == 14, i % 14 == 13);
        }
    }

    static List<SourceFile> generatePortfolio() {
        List<SourceFile> files = new ArrayList<>();
        for (int i = 0; i < APPS.length; i++) {
            String app = APPS[i] + (i % 3 == 0 ? "-service" : "-api");
            Variant v = Variant.of(i);
            files.add(new SourceFile("retail-3.1.3/" + app + "/nonprod/manifest.yaml", manifest(app, false, v).getBytes(StandardCharsets.UTF_8)));
            if (!v.prodMissing()) {
                files.add(new SourceFile("retail-3.1.3/" + app + "/prod/manifest.yaml", manifest(app, true, v).getBytes(StandardCharsets.UTF_8)));
            }
        }
        return files;
    }

    private static String manifest(String app, boolean prod, Variant v) {
        StringBuilder y = new StringBuilder();
        y.append("apiVersion: apps/v1\nkind: Deployment\nmetadata:\n  name: ").append(app)
                .append("\n  labels:\n    app: ").append(app)
                .append("\nspec:\n  replicas: ").append(prod && !v.sameReplicas() ? 3 : 1)
                .append("\n  selector:\n    matchLabels:\n      app: ").append(app)
                .append("\n  template:\n    metadata:\n      labels:\n        app: ").append(app)
                .append("\n    spec:\n");
        if (prod && v.tsc()) {
            y.append("      topologySpreadConstraints:\n        - maxSkew: 1\n          topologyKey: topology.kubernetes.io/zone\n")
                    .append("          whenUnsatisfiable: DoNotSchedule\n          labelSelector:\n            matchLabels:\n              app: ")
                    .append(app).append('\n');
        }
        y.append("      containers:\n        - name: ").append(app)
                .append("\n          image: registry.example.com/").append(app).append(":3.1.3")
                .append("\n          ports:\n            - name: http\n              containerPort: 8080\n          env:\n");
        env(y, "SPRING_PROFILES_ACTIVE", prod ? "prod" : "nonprod");
        if (v.db()) {
            env(y, "DB_HOST", app + "-db." + (prod ? "prod" : "nonprod") + ".internal");
            String name = v.similarDbName() ? "DATABASE_PASSWORD" : "DB_PASSWORD";
            if (!prod) env(y, name, "np-" + app + "-Passw0rd");
            else if (v.k8sSecret()) secretRef(y, name, app + "-secrets", "db-password");
            else env(y, name, "akeyless:/prod/" + app + "/db-password");
        }
        if (v.token()) {
            if (prod && !v.plainToken()) env(y, "API_TOKEN", "akeyless:/prod/" + app + "/api-token");
            else env(y, "API_TOKEN", (prod ? "prod-" : "np-") + "tok-" + Math.abs(app.hashCode()));
        }
        env(y, "LOG_LEVEL", prod && !v.debugLogs() ? "INFO" : "DEBUG");
        env(y, "CACHE_TTL_SECONDS", "60");
        if (prod && v.region()) env(y, "PROD_REGION", "eu-west-1");
        if (prod && v.unusual()) env(y, "DEBUG_ENDPOINTS_ENABLED", "true");
        probe(y, "livenessProbe", "/actuator/health/liveness", 30);
        if (v.readiness()) probe(y, "readinessProbe", "/actuator/health/readiness", 20);
        y.append("          resources:\n            requests:\n              cpu: ").append(prod ? "500m" : "250m")
                .append("\n              memory: ").append(prod ? "1Gi" : "512Mi")
                .append("\n            limits:\n              cpu: ").append(prod ? "1" : "500m")
                .append("\n              memory: ").append(prod ? "2Gi" : "1Gi").append('\n');
        y.append("---\napiVersion: v1\nkind: Service\nmetadata:\n  name: ").append(app)
                .append("\nspec:\n  type: ClusterIP\n  selector:\n    app: ").append(app)
                .append("\n  ports:\n    - name: http\n      port: 8080\n      targetPort: http\n");
        return y.toString();
    }

    private static void env(StringBuilder y, String name, String value) {
        y.append("            - name: ").append(name).append("\n              value: \"").append(value).append("\"\n");
    }

    private static void secretRef(StringBuilder y, String name, String secret, String key) {
        y.append("            - name: ").append(name).append("\n              valueFrom:\n                secretKeyRef:\n")
                .append("                  name: ").append(secret).append("\n                  key: ").append(key).append('\n');
    }

    private static void probe(StringBuilder y, String type, String path, int delay) {
        y.append("          ").append(type).append(":\n            httpGet:\n              path: ").append(path)
                .append("\n              port: http\n            initialDelaySeconds: ").append(delay)
                .append("\n            periodSeconds: 10\n");
    }
}
