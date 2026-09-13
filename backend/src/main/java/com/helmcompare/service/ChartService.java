package com.helmcompare.service;

import com.helmcompare.model.ChartRecord;
import com.helmcompare.model.ConfigItem;
import com.helmcompare.parse.ChartArchive;
import com.helmcompare.parse.ChartArchive.SourceFile;
import com.helmcompare.parse.ChartParser;
import com.helmcompare.store.JsonStore;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ChartService {

    private final JsonStore store;

    public ChartService(JsonStore store) {
        this.store = store;
    }

    public record NewChart(String app, String version, ChartRecord.Environment environment, String revision,
                           String notes, String supersedes, String portfolioId, String originalName) {
    }

    public record SourceView(String path, List<String> lines, List<Integer> maskedLines) {
    }

    public static List<SourceFile> expandUploads(List<MultipartFile> files) {
        List<SourceFile> sources = new ArrayList<>();
        for (MultipartFile f : files) {
            String name = f.getOriginalFilename() == null ? "upload.yaml" : f.getOriginalFilename();
            try {
                sources.addAll(ChartArchive.expand(name, f.getBytes()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return sources;
    }

    public ChartRecord upload(List<MultipartFile> files, NewChart meta) {
        List<SourceFile> sources = ChartArchive.stripCommonRoot(expandUploads(files));
        return create(sources, meta);
    }

    public ChartRecord create(List<SourceFile> sources, NewChart meta) {
        List<SourceFile> yaml = sources.stream().filter(s -> ChartArchive.isYaml(s.path())).toList();
        if (yaml.isEmpty()) throw new IllegalArgumentException("The upload does not contain any YAML files.");
        if (meta.supersedes() != null && !meta.supersedes().isBlank() && store.chart(meta.supersedes()).isEmpty()) {
            throw new IllegalArgumentException("Superseded chart not found: " + meta.supersedes());
        }

        ChartParser.ParsedChart parsed = ChartParser.parse(yaml);
        ChartRecord c = new ChartRecord();
        c.id = UUID.randomUUID().toString().substring(0, 8);
        c.app = firstNonBlank(meta.app(), parsed.chartName(), baseName(meta.originalName()), "chart");
        c.version = firstNonBlank(meta.version(), parsed.appVersion(), parsed.chartVersion(), "unversioned");
        c.environment = meta.environment() == null ? ChartRecord.Environment.OTHER : meta.environment();
        c.revision = firstNonBlank(meta.revision(), "Initial");
        c.notes = meta.notes();
        c.supersedes = meta.supersedes() == null || meta.supersedes().isBlank() ? null : meta.supersedes();
        c.portfolioId = meta.portfolioId();
        c.originalName = meta.originalName();
        c.uploadedAt = Instant.now();
        c.chartName = parsed.chartName();
        c.chartVersion = parsed.chartVersion();
        c.appVersion = parsed.appVersion();
        c.files = yaml.stream().map(SourceFile::path).sorted().toList();
        c.warnings = parsed.warnings();
        c.maskedLines = parsed.maskedLines();
        c.itemCount = parsed.items().size();
        c.sha256 = fingerprint(yaml);
        store.saveChart(c, parsed.items(), yaml);
        return c;
    }

    public ChartRecord get(String id) {
        return store.chart(id).orElseThrow(() -> new NotFoundException("Chart not found: " + id));
    }

    public List<ChartRecord> list(boolean includePortfolio) {
        return store.charts().stream().filter(c -> includePortfolio || c.portfolioId == null).toList();
    }

    public List<ConfigItem> items(String id) {
        get(id);
        return store.items(id);
    }

    public SourceView source(String id, String path) {
        ChartRecord chart = get(id);
        String text = store.chartFile(id, path).orElseThrow(() -> new NotFoundException("File not found: " + path));
        List<Integer> masked = chart.maskedLines.getOrDefault(path, List.of());
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        for (int line : masked) {
            if (line >= 1 && line <= lines.length) lines[line - 1] = maskLine(lines[line - 1]);
        }
        return new SourceView(path, List.of(lines), masked);
    }

    public void delete(String id) {
        get(id);
        store.deleteChart(id);
    }

    static String maskLine(String line) {
        int colon = line.indexOf(": ");
        if (colon >= 0) return line.substring(0, colon + 2) + "••••••";
        int eq = line.indexOf('=');
        if (eq >= 0) return line.substring(0, eq + 1) + "••••••";
        int indent = 0;
        while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) indent++;
        String rest = line.substring(indent);
        String prefix = rest.startsWith("- ") ? "- " : "";
        return line.substring(0, indent) + prefix + "••••••";
    }

    private static String fingerprint(List<SourceFile> files) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            files.stream().sorted(Comparator.comparing(SourceFile::path)).forEach(f -> {
                md.update(f.path().getBytes(StandardCharsets.UTF_8));
                md.update(f.content());
            });
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String baseName(String name) {
        if (name == null) return null;
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1);
        int dot = n.indexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.trim();
        return null;
    }

    public static Map<String, Object> describe(ChartRecord c) {
        return Map.of("id", c.id, "label", c.label());
    }
}
