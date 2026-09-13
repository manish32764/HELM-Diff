package com.helmcompare.export;

import com.helmcompare.diff.EnvVarExtractor;
import com.helmcompare.diff.LogicalFileComparer;
import com.helmcompare.model.FolderCompare;
import com.helmcompare.model.FolderCompare.Node;
import com.helmcompare.service.FolderCompareService;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Exports a folder comparison: folders, files and every logical difference with line numbers. */
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

    public ExportService.Export export(String id, String format) {
        FolderCompare c = service.get(id);
        String left = c.leftName + (c.leftLabel == null ? "" : " (" + c.leftLabel + ")");
        String right = c.rightName + (c.rightLabel == null ? "" : " (" + c.rightLabel + ")");
        String title = left + " vs " + right;
        FolderCompare.Summary s = c.summary;

        List<String[]> meta = new ArrayList<>();
        meta.add(new String[]{"Left folder", left});
        meta.add(new String[]{"Right folder", right});
        meta.add(new String[]{"Compared", TIME.format(c.createdAt)});
        meta.add(new String[]{"Comparison ID", c.id});
        meta.add(new String[]{"Folders in left", String.valueOf(s.leftFolders)});
        meta.add(new String[]{"Folders in right", String.valueOf(s.rightFolders)});
        meta.add(new String[]{"Matched folders", String.valueOf(s.matchedFolders)});
        meta.add(new String[]{"Identical folders", String.valueOf(s.identicalFolders)});
        meta.add(new String[]{"Folders with differences", String.valueOf(s.differentFolders)});
        meta.add(new String[]{"Folders only in left", String.valueOf(s.leftOnlyFolders)});
        meta.add(new String[]{"Folders only in right", String.valueOf(s.rightOnlyFolders)});
        meta.add(new String[]{"Files identical", String.valueOf(s.identicalFiles)});
        meta.add(new String[]{"Files logically the same", String.valueOf(s.logicallySameFiles)});
        meta.add(new String[]{"Files that differ", String.valueOf(s.differentFiles)});
        meta.add(new String[]{"Files only in left", String.valueOf(s.leftOnlyFiles)});
        meta.add(new String[]{"Files only in right", String.valueOf(s.rightOnlyFiles)});

        List<List<String>> folders = new ArrayList<>();
        for (Node n : c.root.children) {
            if (!n.dir) continue;
            folders.add(List.of(n.name, n.status, String.valueOf(n.identical), String.valueOf(n.logicallySame),
                    String.valueOf(n.differs), String.valueOf(n.leftOnly), String.valueOf(n.rightOnly), nz(n.reason)));
        }

        List<List<String>> files = new ArrayList<>();
        List<String> differing = new ArrayList<>();
        collect(c.root, files, differing);

        List<List<String>> diffs = new ArrayList<>();
        for (String path : differing) {
            if (diffs.size() >= MAX_DIFF_ROWS) break;
            FolderCompareService.FileView view = service.file(id, path);
            for (LogicalFileComparer.Diff d : view.differences()) {
                diffs.add(List.of(path, d.kind(), d.path(), nz(d.left()), nz(d.right()),
                        range(d.leftStart(), d.leftEnd()), range(d.rightStart(), d.rightEnd())));
            }
        }

        ExportService.Report report = new ExportService.Report(title, meta, List.of(
                new ExportService.Table("Folders", List.of("Folder", "Status", "Identical files", "Logically same",
                        "Differ", "Only in left", "Only in right", "Details"), folders, Set.of(1)),
                new ExportService.Table("Files", List.of("File", "Status", "Left", "Right", "Logical differences", "Details"),
                        files, Set.of(1)),
                new ExportService.Table("Logical Differences", List.of("File", "Change", "Configuration", "Left value",
                        "Right value", "Left lines", "Right lines"), diffs, Set.of(1))));

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

    public ExportService.Export envExport(String id, String path, String scope, String format, boolean showSecrets) {
        FolderCompareService.EnvView view = service.envVars(id, path, scope);
        String left = view.leftName() + (view.leftLabel() == null ? "" : " (" + view.leftLabel() + ")");
        String right = view.rightName() + (view.rightLabel() == null ? "" : " (" + view.rightLabel() + ")");
        String scopeText = "FOLDER".equals(view.scope()) ? "folder " + (view.scopePath().isEmpty() ? "(all)" : view.scopePath()) : "file " + view.path();
        String title = "Environment variables · " + scopeText;
        var s = view.summary();

        List<String[]> meta = new ArrayList<>();
        meta.add(new String[]{"Left folder", left});
        meta.add(new String[]{"Right folder", right});
        meta.add(new String[]{"Scope", scopeText});
        meta.add(new String[]{"Missing in right (only in left)", String.valueOf(s.leftOnly())});
        meta.add(new String[]{"Missing in left (only in right)", String.valueOf(s.rightOnly())});
        meta.add(new String[]{"Different value", String.valueOf(s.valueDiffers())});
        meta.add(new String[]{"Cannot verify", String.valueOf(s.unverified())});
        meta.add(new String[]{"Same value", String.valueOf(s.same())});
        meta.add(new String[]{"Source changed (e.g. plain → AKeyless)", String.valueOf(s.sourceChanged())});
        meta.add(new String[]{"Matched by similar name", String.valueOf(s.similarNames())});
        meta.add(new String[]{"Defined more than once", String.valueOf(s.duplicates())});
        meta.add(new String[]{"AKeyless values", view.secrets().loadedPaths() == 0 ? "not uploaded"
                : view.secrets().loadedPaths() + " path(s) from " + String.join(", ", view.secrets().files())
                + " · " + view.secrets().resolved() + " of " + view.secrets().referenced() + " reference(s) resolved"});
        meta.add(new String[]{"Secret values", showSecrets ? "shown" : "masked"});

        List<List<String>> rows = new ArrayList<>();
        int n = 0;
        for (var r : view.rows()) {
            n++;
            String result = switch (r.status()) {
                case "LEFT_ONLY", "RIGHT_ONLY" -> "MISSING";
                default -> switch (r.comparison()) {
                    case "SAME" -> "SAME";
                    case "VALUE_DIFFERS" -> "DIFFERS";
                    default -> "UNDETERMINED";
                };
            };
            List<String> notes = new ArrayList<>();
            if ("LEFT_ONLY".equals(r.status())) notes.add("Not defined in " + view.rightName());
            if ("RIGHT_ONLY".equals(r.status())) notes.add("Not defined in " + view.leftName());
            if (r.sourceChanged()) notes.add(sourceText(r.left().source()) + " → " + sourceText(r.right().source()));
            if ("SIMILAR_NAME".equals(r.match())) notes.add("Similar name (" + r.similarity() + "%)");
            if (!r.leftOthers().isEmpty()) notes.add("Left defined " + (r.leftOthers().size() + 1) + "×");
            if (!r.rightOthers().isEmpty()) notes.add("Right defined " + (r.rightOthers().size() + 1) + "×");
            if (r.duplicateConflict()) notes.add("duplicate definitions have different values");
            List<String> row = new ArrayList<>(List.of(String.valueOf(n), r.left() != null ? r.left().name() : r.right().name(),
                    result, String.join(" · ", notes)));
            side(row, r.left(), showSecrets);
            side(row, r.right(), showSecrets);
            rows.add(row);
        }
        ExportService.Report report = new ExportService.Report(title, meta, List.of(new ExportService.Table("Environment Variables",
                List.of("#", "Variable", "Result", "Notes",
                        "Left name", "Left injected via", "Left source", "Left value", "Left AKeyless path", "Left location",
                        "Right name", "Right injected via", "Right source", "Right value", "Right AKeyless path", "Right location"),
                rows, Set.of(2))));
        String base = slug("env " + view.leftName() + " vs " + view.rightName() + " " + (view.scopePath().isEmpty() ? view.path() : view.scopePath())) + "-" + id;
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "csv" -> new ExportService.Export(base + ".csv", "text/csv;charset=UTF-8", exports.csv(report));
            case "html" -> new ExportService.Export(base + ".html", "text/html;charset=UTF-8", exports.html(report));
            case "xlsx" -> new ExportService.Export(base + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", exports.xlsx(report));
            default -> throw new IllegalArgumentException("Unsupported export format: " + format);
        };
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

    private static void collect(Node node, List<List<String>> rows, List<String> differing) {
        for (Node child : node.children) {
            if (child.dir) {
                collect(child, rows, differing);
                continue;
            }
            rows.add(List.of(child.path, child.status, state(child.leftState), state(child.rightState),
                    child.differences == 0 ? "" : String.valueOf(child.differences), nz(child.reason)));
            if ("DIFFERS".equals(child.status)) differing.add(child.path);
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
