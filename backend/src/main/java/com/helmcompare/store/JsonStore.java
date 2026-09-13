package com.helmcompare.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmcompare.model.AnalysisRecord;
import com.helmcompare.model.ChartRecord;
import com.helmcompare.model.ConfigItem;
import com.helmcompare.model.PortfolioRecord;
import com.helmcompare.parse.ChartArchive.SourceFile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * File based persistence (no database needed). Layout:
 * <pre>
 * data/charts/{id}/chart.json, items.json, files/...
 * data/analyses/{id}.json, {id}.summary.json
 * data/portfolios/{id}.json
 * </pre>
 */
@Component
public class JsonStore {

    private final ObjectMapper mapper;
    private final Path root;
    private final Map<String, ChartRecord> charts = new ConcurrentHashMap<>();
    private final Map<String, List<ConfigItem>> itemCache = new ConcurrentHashMap<>();
    private final Map<String, AnalysisRecord.Summary> analysisSummaries = new ConcurrentHashMap<>();
    private final Map<String, PortfolioRecord> portfolios = new ConcurrentHashMap<>();

    public JsonStore(ObjectMapper mapper, @Value("${helmcompare.data-dir:data}") String dataDir) {
        this.mapper = mapper;
        this.root = Path.of(dataDir).toAbsolutePath();
        load();
    }

    public Path root() {
        return root;
    }

    private void load() {
        try {
            Files.createDirectories(root.resolve("charts"));
            Files.createDirectories(root.resolve("analyses"));
            Files.createDirectories(root.resolve("portfolios"));
            try (Stream<Path> dirs = Files.list(root.resolve("charts"))) {
                dirs.map(d -> d.resolve("chart.json")).filter(Files::exists)
                        .forEach(p -> read(p, ChartRecord.class).ifPresent(c -> charts.put(c.id, c)));
            }
            try (Stream<Path> files = Files.list(root.resolve("analyses"))) {
                files.filter(p -> p.getFileName().toString().endsWith(".summary.json"))
                        .forEach(p -> read(p, AnalysisRecord.Summary.class).ifPresent(s -> analysisSummaries.put(s.id, s)));
            }
            try (Stream<Path> files = Files.list(root.resolve("portfolios"))) {
                files.forEach(p -> read(p, PortfolioRecord.class).ifPresent(r -> portfolios.put(r.id, r)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── charts ──

    public void saveChart(ChartRecord chart, List<ConfigItem> items, List<SourceFile> files) {
        Path dir = root.resolve("charts").resolve(chart.id);
        try {
            Files.createDirectories(dir);
            for (SourceFile f : files) {
                Path target = dir.resolve("files").resolve(f.path()).normalize();
                if (!target.startsWith(dir.resolve("files"))) continue;
                Files.createDirectories(target.getParent());
                Files.write(target, f.content());
            }
            write(dir.resolve("items.json"), items);
            write(dir.resolve("chart.json"), chart);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        charts.put(chart.id, chart);
        itemCache.put(chart.id, items);
    }

    public List<ChartRecord> charts() {
        return charts.values().stream().sorted(Comparator.comparing((ChartRecord c) -> c.uploadedAt).reversed()).toList();
    }

    public Optional<ChartRecord> chart(String id) {
        return Optional.ofNullable(charts.get(id));
    }

    public List<ConfigItem> items(String chartId) {
        return itemCache.computeIfAbsent(chartId, id ->
                read(root.resolve("charts").resolve(id).resolve("items.json"), new TypeReference<List<ConfigItem>>() {
                }).orElse(new ArrayList<>()));
    }

    public Optional<String> chartFile(String chartId, String path) {
        Path dir = root.resolve("charts").resolve(chartId).resolve("files");
        Path target = dir.resolve(path).normalize();
        if (!target.startsWith(dir) || !Files.isRegularFile(target)) return Optional.empty();
        try {
            return Optional.of(Files.readString(target));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void deleteChart(String id) {
        charts.remove(id);
        itemCache.remove(id);
        deleteTree(root.resolve("charts").resolve(id));
    }

    // ── analyses ──

    public void saveAnalysis(AnalysisRecord record) {
        write(root.resolve("analyses").resolve(record.id + ".json"), record);
        AnalysisRecord.Summary summary = AnalysisRecord.Summary.of(record);
        write(root.resolve("analyses").resolve(record.id + ".summary.json"), summary);
        analysisSummaries.put(record.id, summary);
    }

    public List<AnalysisRecord.Summary> analyses() {
        return analysisSummaries.values().stream()
                .sorted(Comparator.comparing((AnalysisRecord.Summary s) -> s.createdAt).reversed()).toList();
    }

    public Optional<AnalysisRecord> analysis(String id) {
        if (!analysisSummaries.containsKey(id)) return Optional.empty();
        return read(root.resolve("analyses").resolve(id + ".json"), AnalysisRecord.class);
    }

    public void deleteAnalysis(String id) {
        analysisSummaries.remove(id);
        try {
            Files.deleteIfExists(root.resolve("analyses").resolve(id + ".json"));
            Files.deleteIfExists(root.resolve("analyses").resolve(id + ".summary.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── portfolios ──

    public void savePortfolio(PortfolioRecord record) {
        write(root.resolve("portfolios").resolve(record.id + ".json"), record);
        portfolios.put(record.id, record);
    }

    public List<PortfolioRecord> portfolios() {
        return portfolios.values().stream()
                .sorted(Comparator.comparing((PortfolioRecord p) -> p.uploadedAt).reversed()).toList();
    }

    public Optional<PortfolioRecord> portfolio(String id) {
        return Optional.ofNullable(portfolios.get(id));
    }

    public void deletePortfolio(String id) {
        portfolios.remove(id);
        try {
            Files.deleteIfExists(root.resolve("portfolios").resolve(id + ".json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ── helpers ──

    private void write(Path path, Object value) {
        try {
            Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
            mapper.writeValue(tmp.toFile(), value);
            Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private <T> Optional<T> read(Path path, Class<T> type) {
        try {
            return Files.exists(path) ? Optional.of(mapper.readValue(path.toFile(), type)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private <T> Optional<T> read(Path path, TypeReference<T> type) {
        try {
            return Files.exists(path) ? Optional.of(mapper.readValue(path.toFile(), type)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static void deleteTree(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
