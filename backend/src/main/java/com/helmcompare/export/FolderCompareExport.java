package com.helmcompare.export;

import com.helmcompare.diff.EnvVarExtractor;
import com.helmcompare.diff.MultiEnvComparer;
import com.helmcompare.diff.MultiFileComparer;
import com.helmcompare.model.FolderCompare;
import com.helmcompare.model.FolderCompare.Node;
import com.helmcompare.service.FolderCompareService;
import com.helmcompare.service.FolderStatus;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Exports a folder comparison (the chosen folders): folders, files and every logical difference with line numbers. */
@Service
public class FolderCompareExport {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());
    private static final int MAX_DIFF_ROWS = 20_000;

    private final FolderCompareService service;
    private final ExportService exports;

    public FolderCompareExport(FolderCompareService service, ExportService exports) {
        this.service = service;
        this.exports = exports;
    }

    /** @param sidesParam the folders to include, e.g. "0,2"; all when null */
    public ExportService.Export export(String id, String format, String sidesParam) {
        FolderCompare c = service.get(id);
        int[] sides = service.parseSides(c, sidesParam);
        List<String> titles = FolderStatus.titles(c);
        List<String> shownTitles = new ArrayList<>();
        for (int s : sides) shownTitles.add(titles.get(s));
        String title = joined(c, sides, " vs ");

        List<String[]> meta = new ArrayList<>();
        for (int k = 0; k < sides.length; k++) meta.add(new String[]{"Folder " + (k + 1), describe(c.sides.get(sides[k]))});
        meta.add(new String[]{"Compared", TIME.format(c.createdAt)});
        meta.add(new String[]{"Comparison ID", c.id});

        List<List<String>> folders = new ArrayList<>();
        int[] folderCounts = new int[4];
        for (Node n : c.root.children) {
            if (!n.dir || !FolderStatus.visible(n, sides)) continue;
            FolderStatus.Eval e = FolderStatus.eval(n, sides, titles);
            folderCounts[index(e.status())]++;
            List<String> row = new ArrayList<>(List.of(n.name, e.status()));
            for (int s : sides) row.add(n.present(s) ? "Yes" : "No");
            row.addAll(List.of(String.valueOf(e.counts().identical()), String.valueOf(e.counts().logicallySame()),
                    String.valueOf(e.counts().differs()), String.valueOf(e.counts().partial()), nz(e.reason())));
            folders.add(row);
        }
        FolderStatus.Counts files = FolderStatus.eval(c.root, sides, titles).counts();
        meta.add(new String[]{"Folders", String.valueOf(folders.size())});
        meta.add(new String[]{"Identical folders", String.valueOf(folderCounts[0])});
        meta.add(new String[]{"Logically same folders", String.valueOf(folderCounts[1])});
        meta.add(new String[]{"Folders with differences", String.valueOf(folderCounts[2])});
        meta.add(new String[]{"Folders not in every folder", String.valueOf(folderCounts[3])});
        meta.add(new String[]{"Files identical", String.valueOf(files.identical())});
        meta.add(new String[]{"Files logically the same", String.valueOf(files.logicallySame())});
        meta.add(new String[]{"Files that differ", String.valueOf(files.differs())});
        meta.add(new String[]{"Files not in every folder", String.valueOf(files.partial())});

        List<List<String>> fileRows = new ArrayList<>();
        List<String> differing = new ArrayList<>();
        collect(c.root, sides, titles, fileRows, differing);

        String key = FolderCompareService.key(sides);
        List<List<String>> diffs = new ArrayList<>();
        for (String path : differing) {
            if (diffs.size() >= MAX_DIFF_ROWS) break;
            FolderCompareService.Comparison comparison = service.file(id, path).comparisons().get(key);
            for (MultiFileComparer.SideDiff d : comparison.differences()) {
                List<String> row = new ArrayList<>(List.of(path, d.kind(), d.path()));
                for (int s : sides) row.add(d.sides().contains(s) ? nz(d.values().get(s)) : "(not compared)");
                for (int s : sides) row.add(range(d.starts().get(s), d.ends().get(s)));
                diffs.add(row);
            }
        }

        List<String> folderHeaders = new ArrayList<>(List.of("Folder", "Status"));
        shownTitles.forEach(t -> folderHeaders.add("In " + t));
        folderHeaders.addAll(List.of("Identical files", "Logically same", "Differ", "Not in every folder", "Details"));
        List<String> fileHeaders = new ArrayList<>(List.of("File", "Status"));
        fileHeaders.addAll(shownTitles);
        fileHeaders.addAll(List.of("Logical differences", "Details"));
        List<String> diffHeaders = new ArrayList<>(List.of("File", "Change", "Configuration"));
        shownTitles.forEach(t -> diffHeaders.add(t + " value"));
        shownTitles.forEach(t -> diffHeaders.add(t + " lines"));

        ExportService.Report report = new ExportService.Report(title, meta, List.of(
                new ExportService.Table("Folders", folderHeaders, folders, Set.of(1)),
                new ExportService.Table("Files", fileHeaders, fileRows, Set.of(1)),
                new ExportService.Table("Logical Differences", diffHeaders, diffs, Set.of(1))));

        String base = slug(title) + "-" + c.id;
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "csv" -> new ExportService.Export(base + ".csv", "text/csv;charset=UTF-8", exports.csv(
                    new ExportService.Report(title, meta, List.of(report.tables().get(1)))));
            case "html" -> new ExportService.Export(base + ".html", "text/html;charset=UTF-8", exports.html(report));
            case "xlsx" -> new ExportService.Export(base + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", exports.xlsx(report));
            default -> throw new IllegalArgumentException("Unsupported export format: " + format);
        };
    }

    public ExportService.Export envExport(String id, String path, String scope, String format, boolean showSecrets, String sidesParam) {
        FolderCompareService.EnvView view = service.envVars(id, path, scope, sidesParam);
        List<Integer> sides = view.shown();
        List<String> titles = view.sides().stream().map(FolderCompare.SideInfo::title).toList();
        String scopeText = "FOLDER".equals(view.scope()) ? "folder " + (view.scopePath().isEmpty() ? "(all)" : view.scopePath()) : "file " + view.path();
        String title = "Environment variables · " + scopeText;
        var s = view.summary();

        List<String[]> meta = new ArrayList<>();
        for (int k = 0; k < sides.size(); k++) meta.add(new String[]{"Folder " + (k + 1), describe(view.sides().get(sides.get(k)))});
        meta.add(new String[]{"Scope", scopeText});
        for (int side : sides) meta.add(new String[]{"Missing in " + titles.get(side), String.valueOf(s.missing().get(side))});
        meta.add(new String[]{"Different value", String.valueOf(s.valueDiffers())});
        meta.add(new String[]{"Cannot verify", String.valueOf(s.unverified())});
        meta.add(new String[]{"Same value", String.valueOf(s.same())});
        meta.add(new String[]{"Source changed (e.g. plain → AKeyless)", String.valueOf(s.sourceChanged())});
        meta.add(new String[]{"Matched by similar name", String.valueOf(s.similarNames())});
        meta.add(new String[]{"Defined more than once", String.valueOf(s.duplicates())});
        for (int side : sides) {
            meta.add(new String[]{"AKeyless values (" + titles.get(side) + ")", secretsText(view.secrets().sides().get(side))});
        }
        meta.add(new String[]{"AKeyless references resolved", view.secrets().resolved() + " of " + view.secrets().referenced()});
        meta.add(new String[]{"Secret values", showSecrets ? "shown" : "masked"});

        List<List<String>> rows = new ArrayList<>();
        int n = 0;
        for (MultiEnvComparer.Row r : view.rows()) {
            n++;
            String result = !r.missingIn().isEmpty() ? "MISSING" : switch (r.comparison()) {
                case "SAME" -> "SAME";
                case "VALUE_DIFFERS" -> "DIFFERS";
                default -> "UNDETERMINED";
            };
            List<String> notes = new ArrayList<>();
            if (!r.missingIn().isEmpty()) {
                notes.add("Not defined in " + r.missingIn().stream().map(titles::get).collect(Collectors.joining(", ")));
            }
            if (r.sourceChanged()) {
                notes.add(sides.stream().filter(side -> r.vars().get(side) != null)
                        .map(side -> sourceText(r.vars().get(side).source())).collect(Collectors.joining(" → ")));
            }
            if ("SIMILAR_NAME".equals(r.match())) notes.add("Similar name (" + r.similarity() + "%)");
            for (int side : sides) {
                if (!r.others().get(side).isEmpty()) notes.add(titles.get(side) + " defined " + (r.others().get(side).size() + 1) + "×");
            }
            if (r.duplicateConflict()) notes.add("duplicate definitions have different values");
            EnvVarExtractor.EnvVar first = sides.stream().map(side -> r.vars().get(side)).filter(v -> v != null).findFirst().orElseThrow();
            List<String> row = new ArrayList<>(List.of(String.valueOf(n), first.name(), result, String.join(" · ", notes)));
            for (int side : sides) side(row, r.vars().get(side), showSecrets);
            rows.add(row);
        }
        List<String> headers = new ArrayList<>(List.of("#", "Variable", "Result", "Notes"));
        for (int side : sides) {
            String t = titles.get(side);
            headers.addAll(List.of(t + " name", t + " injected via", t + " source", t + " value", t + " AKeyless path", t + " location"));
        }
        ExportService.Report report = new ExportService.Report(title, meta,
                List.of(new ExportService.Table("Environment Variables", headers, rows, Set.of(2))));
        String names = sides.stream().map(side -> view.sides().get(side).name).collect(Collectors.joining(" vs "));
        String base = slug("env " + names + " " + (view.scopePath().isEmpty() ? view.path() : view.scopePath())) + "-" + id;
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "csv" -> new ExportService.Export(base + ".csv", "text/csv;charset=UTF-8", exports.csv(report));
            case "html" -> new ExportService.Export(base + ".html", "text/html;charset=UTF-8", exports.html(report));
            case "xlsx" -> new ExportService.Export(base + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", exports.xlsx(report));
            default -> throw new IllegalArgumentException("Unsupported export format: " + format);
        };
    }

    private static int index(String status) {
        return switch (status) {
            case FolderStatus.IDENTICAL -> 0;
            case FolderStatus.LOGICALLY_IDENTICAL -> 1;
            case FolderStatus.DIFFERS -> 2;
            default -> 3;
        };
    }

    private static String describe(FolderCompare.SideInfo side) {
        return side.name + (side.label == null ? "" : " (" + side.label + ")");
    }

    private static String joined(FolderCompare c, int[] sides, String separator) {
        List<String> parts = new ArrayList<>();
        for (int s : sides) parts.add(describe(c.sides.get(s)));
        return String.join(separator, parts);
    }

    private static String secretsText(FolderCompareService.SideSecrets s) {
        return s.paths() == 0 ? "not uploaded" : s.paths() + " path(s) from " + String.join(", ", s.files());
    }

    private static void side(List<String> row, EnvVarExtractor.EnvVar v, boolean showSecrets) {
        if (v == null) {
            row.addAll(List.of("— not defined —", "", "", "", "", ""));
            return;
        }
        row.addAll(List.of(v.name(), nz(v.injection()).replaceAll(" @ [^›]*", ""), sourceText(v.source()), valueText(v, showSecrets),
                nz(v.akeylessPath()), v.file() + ":" + v.line()));
    }

    private static String valueText(EnvVarExtractor.EnvVar v, boolean showSecrets) {
        String value = v.effectiveValue();
        if (value == null) {
            return switch (v.valueState()) {
                case EnvVarExtractor.STATE_NO_JSON -> "(AKeyless value not uploaded)";
                case EnvVarExtractor.STATE_NOT_IN_JSON -> "(AKeyless path not in JSON)";
                default -> "(not in chart" + (v.reference() == null ? ")" : ": " + v.reference() + ")");
            };
        }
        boolean secret = EnvVarExtractor.STATE_RESOLVED.equals(v.valueState()) || EnvVarExtractor.KIND_SECRET_DATA.equals(v.kind())
                || EnvVarExtractor.KIND_SECRET_VALUE.equals(v.kind());
        if (secret && !showSecrets) return "•••••••• (" + value.length() + " chars)";
        return value;
    }

    private static String sourceText(String source) {
        return switch (source) {
            case "PLAIN" -> "Plain text";
            case "AKEYLESS" -> "AKeyless";
            case "K8S_SECRET" -> "K8s Secret";
            case "EXTERNAL_SECRET" -> "External secret";
            case "CONFIGMAP" -> "ConfigMap";
            case "FIELD_REF" -> "Field ref";
            case "TEMPLATE" -> "Helm template";
            case "EMPTY" -> "Empty";
            case "VAULT" -> "Vault";
            default -> source;
        };
    }

    private static void collect(Node node, int[] sides, List<String> titles, List<List<String>> rows, List<String> differing) {
        for (Node child : node.children) {
            if (!FolderStatus.visible(child, sides)) continue;
            if (child.dir) {
                collect(child, sides, titles, rows, differing);
                continue;
            }
            FolderStatus.Eval e = FolderStatus.eval(child, sides, titles);
            List<String> row = new ArrayList<>(List.of(child.path, e.status()));
            for (int s : sides) row.add(state(child.state(s)));
            row.addAll(List.of(e.differences() == 0 ? "" : String.valueOf(e.differences()), nz(e.reason())));
            rows.add(row);
            if (FolderStatus.DIFFERS.equals(e.status())) differing.add(child.path);
        }
    }

    private static String state(String s) {
        return switch (s) {
            case "MISSING" -> "Does not exist";
            case "EMPTY" -> "Empty file";
            default -> "Present";
        };
    }

    private static String range(int start, int end) {
        if (start <= 0) return "";
        return start == end ? String.valueOf(start) : start + "–" + end;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String slug(String s) {
        String slug = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return slug.length() > 60 ? slug.substring(0, 60) : slug;
    }
}
