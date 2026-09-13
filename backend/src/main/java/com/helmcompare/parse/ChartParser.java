package com.helmcompare.parse;

import com.helmcompare.model.ConfigItem;
import com.helmcompare.parse.ChartArchive.SourceFile;
import com.helmcompare.parse.ConfigExtractor.FileKind;
import com.helmcompare.parse.ConfigExtractor.ParsedFile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Orchestrates parsing of a chart (values files, templates, rendered manifests) into configuration items. */
public final class ChartParser {

    public record ParsedChart(List<ConfigItem> items, List<String> warnings, Map<String, List<Integer>> maskedLines,
                              String chartName, String chartVersion, String appVersion) {
    }

    private ChartParser() {
    }

    public static ParsedChart parse(List<SourceFile> sources) {
        List<String> warnings = new ArrayList<>();
        List<SourceFile> yaml = sources.stream()
                .filter(s -> ChartArchive.isYaml(s.path()))
                .filter(s -> !s.path().contains("templates/tests/"))
                .toList();
        if (yaml.isEmpty()) warnings.add("No YAML files were found in the upload.");

        String chartName = null;
        String chartVersion = null;
        String appVersion = null;
        String rootDir = "";
        Optional<SourceFile> chartYaml = yaml.stream()
                .filter(s -> baseName(s.path()).equalsIgnoreCase("Chart.yaml"))
                .min(Comparator.comparingInt(s -> depth(s.path())));
        if (chartYaml.isPresent()) {
            rootDir = dirOf(chartYaml.get().path());
            List<YNode> docs = YamlTreeParser.parse(chartYaml.get().path(), chartYaml.get().text()).documents();
            if (!docs.isEmpty() && docs.get(0).isMap()) {
                chartName = docs.get(0).str("name");
                chartVersion = docs.get(0).str("version");
                appVersion = docs.get(0).str("appVersion");
            }
        }

        List<ParsedFile> parsed = new ArrayList<>();
        List<SourceFile> valuesFiles = yaml.stream()
                .filter(ChartParser::isValuesFile)
                .sorted(Comparator.comparing((SourceFile s) -> !baseName(s.path()).equalsIgnoreCase("values.yaml"))
                        .thenComparing(SourceFile::path))
                .toList();
        YNode rootValues = null;
        List<String> rootValueNames = new ArrayList<>();
        for (SourceFile v : valuesFiles) {
            YamlTreeParser.Result r = YamlTreeParser.parse(v.path(), v.text());
            warnings.addAll(r.warnings());
            parsed.add(new ParsedFile(v.path(), FileKind.VALUES, r.documents()));
            if (dirOf(v.path()).equals(rootDir) && !r.documents().isEmpty() && r.documents().get(0).isMap()) {
                rootValues = rootValues == null ? r.documents().get(0) : YNode.deepMerge(rootValues, r.documents().get(0));
                rootValueNames.add(v.path());
            }
        }
        if (rootValueNames.size() > 1) {
            warnings.add("Multiple values files were merged in this order: " + String.join(", ", rootValueNames));
        }

        for (SourceFile s : yaml) {
            String base = baseName(s.path());
            if (base.equalsIgnoreCase("Chart.yaml") || base.equalsIgnoreCase("Chart.lock") || isValuesFile(s)) continue;
            String text = s.text();
            if (text.contains("{{")) text = TemplatePreprocessor.process(text, rootValues);
            YamlTreeParser.Result r = YamlTreeParser.parse(s.path(), text);
            warnings.addAll(r.warnings());
            boolean template = s.path().startsWith("templates/") || s.path().contains("/templates/");
            parsed.add(new ParsedFile(s.path(), template ? FileKind.TEMPLATE : FileKind.MANIFEST, r.documents()));
        }

        ConfigExtractor.Output out = new ConfigExtractor().extract(parsed);
        return new ParsedChart(out.items(), warnings, out.maskedLines(), chartName, chartVersion, appVersion);
    }

    static boolean isValuesFile(SourceFile s) {
        String base = baseName(s.path()).toLowerCase(Locale.ROOT);
        return base.startsWith("values") && ChartArchive.isYaml(base)
                && !s.path().startsWith("templates/") && !s.path().contains("/templates/");
    }

    static String baseName(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    static String dirOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    private static int depth(String path) {
        return (int) path.chars().filter(c -> c == '/').count();
    }
}
