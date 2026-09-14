package com.helmcompare.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.helmcompare.model.AnalysisRecord;
import com.helmcompare.model.ChartRef;
import com.helmcompare.model.ConfigItem;
import com.helmcompare.model.DiffEntry;
import com.helmcompare.model.DiffResult;
import com.helmcompare.model.Expectation;
import com.helmcompare.model.PortfolioResult;
import com.helmcompare.model.VersionAnalysisResult;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Exports any analysis as an Excel workbook, CSV, standalone HTML report or JSON. */
@Service
public class ExportService {

    public record Export(String fileName, String contentType, byte[] content) {
    }

    record Table(String name, List<String> headers, List<List<String>> rows, Set<Integer> statusColumns) {
    }

    record Report(String title, List<String[]> meta, List<Table> tables) {
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z").withZone(ZoneId.systemDefault());

    private static final Set<String> GREEN = Set.of("IDENTICAL", "LOGICALLY_IDENTICAL", "COMMON", "COMPLIANT", "CARRIED_FORWARD", "IMPLEMENTED", "CONSISTENT",
            "ALREADY_SATISFIED", "ALREADY_IN_BASELINE", "UNCHANGED", "SAME", "ACCEPTED");
    private static final Set<String> RED = Set.of("MISSING", "REQUIRES_CHANGE", "REQUIRES_CHANGES", "REMOVED", "UNEXPECTED",
            "INCONSISTENT", "NEEDS_CHANGE");
    private static final Set<String> ORANGE = Set.of("DIFFERS", "CHANGED", "REVIEW", "REQUIRES_REVIEW", "CHANGED_IMPLEMENTATION",
            "DIFFERENT_IMPLEMENTATION", "IMPLEMENTED_DIFFERENTLY", "CONSISTENT_WITH_REVIEW", "SIMILAR", "OPEN");
    private static final Set<String> BLUE = Set.of("ADDED", "NEW_PROD_CHANGE", "NEW_CHANGE", "VERSION_CHANGE", "PREPARATION");
    private static final Set<String> PURPLE = Set.of("LEFT_ONLY", "RIGHT_ONLY", "PARTIAL","NO_LONGER_APPLICABLE", "POTENTIALLY_IRRELEVANT", "NO_LONGER_PRESENT",
            "NOT_APPLICABLE", "UNDETERMINED", "DIFFERENT");

    private final ObjectMapper mapper;

    public ExportService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Export export(AnalysisRecord rec, String format) {
        String base = slug(rec.title) + "-" + rec.id;
        return switch (format.toLowerCase(Locale.ROOT)) {
            case "json" -> new Export(base + ".json", "application/json", json(rec));
            case "csv" -> new Export(base + ".csv", "text/csv;charset=UTF-8", csv(build(rec)));
            case "html" -> new Export(base + ".html", "text/html;charset=UTF-8", html(build(rec)));
            case "xlsx" -> new Export(base + ".xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx(build(rec)));
            default -> throw new IllegalArgumentException("Unsupported export format: " + format);
        };
    }

    // ───────────────────────────── report model ─────────────────────────────

    Report build(AnalysisRecord rec) {
        List<String[]> meta = new ArrayList<>();
        meta.add(new String[]{"Title", rec.title});
        meta.add(new String[]{"Analysis type", label(rec.type.name())});
        meta.add(new String[]{"Analysis ID", rec.id});
        meta.add(new String[]{"Performed", TIME.format(rec.createdAt)});
        if (rec.rerunOf != null) meta.add(new String[]{"Repeat of analysis", rec.rerunOf});
        for (ChartRef c : rec.charts) {
            meta.add(new String[]{c.role, c.app + " " + c.version + " " + label(String.valueOf(c.environment))
                    + " · revision " + c.revision + " · uploaded " + (c.uploadedAt == null ? "" : TIME.format(c.uploadedAt))
                    + " · sha256 " + (c.sha256 == null ? "" : c.sha256.substring(0, 12))});
        }
        rec.headline.forEach((k, v) -> meta.add(new String[]{label(camelToCode(k)), String.valueOf(v)}));

        List<Table> tables = new ArrayList<>();
        switch (rec.type) {
            case PAIRWISE -> pairwiseTables(rec, rec.pairwise, "", tables);
            case DIFF_COMPARE, FOUR_CHART -> versionTables(rec, tables);
            case PORTFOLIO -> portfolioTables(rec, tables);
        }
        tables.add(reviewTable(rec));
        tables.add(auditTable(rec));
        return new Report(rec.title, meta, tables);
    }

    private void pairwiseTables(AnalysisRecord rec, DiffResult diff, String prefix, List<Table> tables) {
        List<List<String>> rows = new ArrayList<>();
        List<List<String>> common = new ArrayList<>();
        for (DiffEntry e : diff.entries) {
            if (e.status == DiffEntry.Status.COMMON) {
                common.add(List.of(label(e.category.name()), e.subject, value(e.left)));
                continue;
            }
            AnalysisRecord.ReviewMark mark = rec.reviews.get(e.id);
            rows.add(List.of(
                    label(e.category.name()), e.subject, e.status.name(), nz(e.summary),
                    source(e.left), value(e.left), source(e.right), value(e.right),
                    e.fieldChanges.stream().map(f -> f.path + ": " + nz(f.left) + " → " + nz(f.right)).collect(Collectors.joining("; ")),
                    yes(e.security), yes(e.prodSpecific), yes(e.review), String.join(" ", e.notes),
                    mark == null ? "" : mark.status, mark == null ? "" : nz(mark.comment),
                    location(e.left), location(e.right)));
        }
        String a = diff.left.role + " (" + diff.left.app + " " + diff.left.version + " " + label(String.valueOf(diff.left.environment)) + ")";
        String b = diff.right.role + " (" + diff.right.app + " " + diff.right.version + " " + label(String.valueOf(diff.right.environment)) + ")";
        tables.add(new Table(prefix + "Differences", List.of("Category", "Configuration", "Status", "Summary",
                a + " source", a + " value", b + " source", b + " value", "Field changes", "Security", "PROD-specific",
                "Requires review", "Notes", "Review mark", "Review comment", "Location A", "Location B"), rows, Set.of(2, 13)));
        if (prefix.isEmpty()) {
            List<List<String>> byCategory = new ArrayList<>();
            diff.summary.differencesByCategory.forEach((k, v) -> byCategory.add(List.of(label(k), String.valueOf(v))));
            tables.add(new Table("By Category", List.of("Category", "Differences"), byCategory, Set.of()));
            tables.add(new Table("Common", List.of("Category", "Configuration", "Value"), common, Set.of()));
        }
    }

    private void versionTables(AnalysisRecord rec, List<Table> tables) {
        VersionAnalysisResult v = rec.version;
        boolean hasD = v.d != null;
        if (hasD) {
            List<List<String>> rows = new ArrayList<>();
            for (VersionAnalysisResult.CorrelatedChange cc : v.correlations) {
                AnalysisRecord.ReviewMark mark = rec.reviews.get(cc.id);
                rows.add(List.of(cc.classification, cc.similarity, nz(cc.validation), label(cc.category.name()), cc.subject,
                        cc.historical == null ? "" : cc.historical.summary, cc.current == null ? "" : cc.current.summary,
                        value(cc.stateA), value(cc.stateB), value(cc.stateC), value(cc.stateD), nz(cc.explanation),
                        yes(cc.security), yes(cc.review), mark == null ? "" : mark.status, mark == null ? "" : nz(mark.comment)));
            }
            tables.add(new Table("Diff Correlation", List.of("Classification", "Similarity", "Validation", "Category",
                    "Configuration", "Historical PROD change (A→B)", "New PROD change (C→D)", "A value", "B value",
                    "C value", "D value", "Explanation", "Security", "Requires review", "Review mark", "Review comment"),
                    rows, Set.of(0, 1, 2, 14)));
        }

        List<List<String>> matrix = new ArrayList<>();
        for (VersionAnalysisResult.MatrixRow row : v.matrix) {
            if (row.allEqual) continue;
            List<String> r = new ArrayList<>(List.of(row.subject, label(row.category.name())));
            row.cells.forEach(c -> r.add(c.label));
            r.add(row.assessment);
            matrix.add(r);
        }
        tables.add(new Table("Evolution Matrix", List.of("Configuration", "Category", header(v.a), header(v.b), header(v.c),
                hasD ? header(v.d) : "New PROD (not provided)", "Assessment"), matrix, Set.of(6)));

        List<List<String>> np = new ArrayList<>();
        for (VersionAnalysisResult.Assessment as : v.nonProdAssessment) {
            AnalysisRecord.ReviewMark mark = rec.reviews.get(as.id);
            np.add(List.of(as.status, label(as.category.name()), as.subject, as.historical.summary, value(as.candidate),
                    nz(as.recommendation), yes(as.security), mark == null ? "" : mark.status, mark == null ? "" : nz(mark.comment)));
        }
        tables.add(new Table("NON-PROD Assessment", List.of("Status", "Category", "Configuration", "Historical PROD change",
                "New NON-PROD configuration", "Recommendation", "Security", "Review mark", "Review comment"), np, Set.of(0, 7)));

        pairwiseTables(rec, v.historicalDiff, "Historical ", tables);
        if (v.currentDiff != null) pairwiseTables(rec, v.currentDiff, "New Version ", tables);
    }

    private void portfolioTables(AnalysisRecord rec, List<Table> tables) {
        PortfolioResult p = rec.portfolio;
        Map<String, Expectation> expectations = new HashMap<>();
        p.expectations.forEach(x -> expectations.put(x.id, x));
        Map<String, PortfolioResult.ExpectationSummary> summaries = new HashMap<>();
        p.expectationSummaries.forEach(s -> summaries.put(s.expectationId, s));

        List<List<String>> exp = new ArrayList<>();
        for (Expectation x : p.expectations) {
            PortfolioResult.ExpectationSummary s = summaries.get(x.id);
            exp.add(List.of(x.title, label(x.category.name()), label(x.kind), label(x.applicability), yes(x.selected),
                    s == null ? "" : String.valueOf(s.compliant), s == null ? "" : String.valueOf(s.requiresChange),
                    s == null ? "" : String.valueOf(s.review), s == null ? "" : String.valueOf(s.notApplicable), nz(x.rationale)));
        }
        tables.add(new Table("Expectations", List.of("Expectation", "Category", "Kind", "Applicability", "Selected",
                "Compliant", "Requires change", "Review", "Not applicable", "Derived from"), exp, Set.of()));

        List<List<String>> apps = new ArrayList<>();
        List<List<String>> details = new ArrayList<>();
        List<List<String>> unexpected = new ArrayList<>();
        for (PortfolioResult.AppResult a : p.apps) {
            AnalysisRecord.ReviewMark mark = rec.reviews.get(a.app);
            apps.add(List.of(a.app, a.status, label(a.evaluatedOn), String.valueOf(a.compliant), String.valueOf(a.requiresChange),
                    String.valueOf(a.review), String.valueOf(a.notApplicable), String.valueOf(a.unexpectedChanges.size()),
                    String.valueOf(a.unusualChanges.size()), String.valueOf(a.otherProdDifferences),
                    mark == null ? "" : mark.status, mark == null ? "" : nz(mark.comment)));
            for (PortfolioResult.ExpectationResult r : a.results) {
                Expectation x = expectations.get(r.expectationId);
                details.add(List.of(a.app, x == null ? r.expectationId : x.title, r.status, nz(r.match), nz(r.detail)));
            }
            for (DiffEntry e : a.unexpectedChanges) {
                unexpected.add(List.of(a.app, "Unexpected", label(e.category.name()), e.subject, nz(e.summary), String.join(" ", e.notes)));
            }
            for (DiffEntry e : a.unusualChanges) {
                unexpected.add(List.of(a.app, "Unusual", label(e.category.name()), e.subject, nz(e.summary), String.join(" ", e.notes)));
            }
        }
        tables.add(new Table("Charts", List.of("Application", "Status", "Evaluated on", "Compliant", "Requires change",
                "Review", "Not applicable", "Unexpected PROD changes", "Unusual changes", "Other PROD differences",
                "Review mark", "Review comment"), apps, Set.of(1, 10)));
        tables.add(new Table("Chart Details", List.of("Application", "Expectation", "Status", "Match", "Detail"), details, Set.of(2, 3)));
        tables.add(new Table("Unexpected & Unusual", List.of("Application", "Type", "Category", "Configuration", "Summary", "Notes"),
                unexpected, Set.of()));

        List<List<String>> common = new ArrayList<>();
        for (PortfolioResult.CommonChange c : p.commonChanges) {
            common.add(List.of(c.label, label(c.category.name()), String.valueOf(c.count), String.join(", ", c.apps)));
        }
        tables.add(new Table("Common PROD Changes", List.of("Change", "Category", "Charts", "Applications"), common, Set.of()));
    }

    private Table reviewTable(AnalysisRecord rec) {
        List<List<String>> rows = new ArrayList<>();
        rec.reviews.forEach((id, m) -> rows.add(List.of(id, m.status, nz(m.comment), m.updatedAt == null ? "" : TIME.format(m.updatedAt))));
        return new Table("Review Marks", List.of("Item", "Status", "Comment", "Updated"), rows, Set.of(1));
    }

    private Table auditTable(AnalysisRecord rec) {
        List<List<String>> rows = new ArrayList<>();
        rec.audit.forEach(a -> rows.add(List.of(a.at == null ? "" : TIME.format(a.at), a.action, nz(a.detail))));
        return new Table("Audit Trail", List.of("When", "Action", "Detail"), rows, Set.of());
    }

    // ───────────────────────────── renderers ─────────────────────────────

    private byte[] json(AnalysisRecord rec) {
        try {
            return mapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsBytes(rec);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    byte[] csv(Report report) {
        Table main = report.tables().get(0);
        StringBuilder sb = new StringBuilder("﻿");
        appendCsvRow(sb, main.headers());
        for (List<String> row : main.rows()) appendCsvRow(sb, labelRow(row, main.statusColumns()));
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendCsvRow(StringBuilder sb, List<String> cells) {
        sb.append(cells.stream().map(c -> {
            String v = c == null ? "" : c;
            return v.contains(",") || v.contains("\"") || v.contains("\n") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
        }).collect(Collectors.joining(","))).append("\r\n");
    }

    byte[] xlsx(Report report) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles styles = new Styles(wb);
            XSSFSheet summary = wb.createSheet("Summary");
            Row title = summary.createRow(0);
            Cell t = title.createCell(0);
            t.setCellValue(report.title());
            t.setCellStyle(styles.title);
            int r = 2;
            for (String[] m : report.meta()) {
                Row row = summary.createRow(r++);
                Cell k = row.createCell(0);
                k.setCellValue(m[0]);
                k.setCellStyle(styles.metaKey);
                Cell v = row.createCell(1);
                v.setCellValue(m[1]);
                v.setCellStyle(styles.wrap);
            }
            summary.setColumnWidth(0, 32 * 256);
            summary.setColumnWidth(1, 110 * 256);

            Set<String> names = new HashSet<>(Set.of("Summary"));
            for (Table table : report.tables()) {
                String name = WorkbookUtil.createSafeSheetName(table.name());
                if (name.length() > 31) name = name.substring(0, 31);
                String unique = name;
                int n = 2;
                while (!names.add(unique)) unique = name.substring(0, Math.min(name.length(), 28)) + " " + n++;
                XSSFSheet sheet = wb.createSheet(unique);
                Row header = sheet.createRow(0);
                int[] widths = new int[table.headers().size()];
                for (int c = 0; c < table.headers().size(); c++) {
                    Cell cell = header.createCell(c);
                    cell.setCellValue(table.headers().get(c));
                    cell.setCellStyle(styles.header);
                    widths[c] = table.headers().get(c).length();
                }
                int rowIndex = 1;
                for (List<String> values : table.rows()) {
                    Row row = sheet.createRow(rowIndex++);
                    for (int c = 0; c < values.size(); c++) {
                        String raw = values.get(c) == null ? "" : values.get(c);
                        Cell cell = row.createCell(c);
                        boolean status = table.statusColumns().contains(c);
                        String text = status ? label(raw) : raw;
                        cell.setCellValue(text.length() > 32000 ? text.substring(0, 32000) : text);
                        cell.setCellStyle(status ? styles.forStatus(raw) : styles.wrap);
                        if (c < widths.length) widths[c] = Math.max(widths[c], Math.min(text.length(), 70));
                    }
                }
                for (int c = 0; c < widths.length; c++) sheet.setColumnWidth(c, Math.min(72, widths[c] + 3) * 256);
                sheet.createFreezePane(0, 1);
                if (!table.rows().isEmpty()) {
                    sheet.setAutoFilter(new CellRangeAddress(0, table.rows().size(), 0, table.headers().size() - 1));
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final class Styles {
        final CellStyle title;
        final CellStyle header;
        final CellStyle metaKey;
        final CellStyle wrap;
        private final Map<String, CellStyle> status = new HashMap<>();
        private final XSSFWorkbook wb;

        Styles(XSSFWorkbook wb) {
            this.wb = wb;
            XSSFFont titleFont = wb.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 16);
            title = wb.createCellStyle();
            title.setFont(titleFont);

            XSSFFont bold = wb.createFont();
            bold.setBold(true);
            XSSFCellStyle h = wb.createCellStyle();
            h.setFont(bold);
            h.setFillForegroundColor(color("EAF2FD"));
            h.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            h.setBorderBottom(BorderStyle.THIN);
            h.setWrapText(true);
            h.setVerticalAlignment(VerticalAlignment.TOP);
            header = h;

            metaKey = wb.createCellStyle();
            metaKey.setFont(bold);
            metaKey.setVerticalAlignment(VerticalAlignment.TOP);

            wrap = wb.createCellStyle();
            wrap.setWrapText(true);
            wrap.setVerticalAlignment(VerticalAlignment.TOP);
        }

        CellStyle forStatus(String code) {
            String hex = GREEN.contains(code) ? "E6F6EC" : RED.contains(code) ? "FDECEC" : ORANGE.contains(code) ? "FFF3E0"
                    : BLUE.contains(code) ? "E8F1FD" : PURPLE.contains(code) ? "F4EBFA" : null;
            if (hex == null) return wrap;
            return status.computeIfAbsent(hex, x -> {
                XSSFCellStyle s = wb.createCellStyle();
                s.cloneStyleFrom(wrap);
                s.setFillForegroundColor(color(x));
                s.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                return s;
            });
        }

        private static XSSFColor color(String hex) {
            return new XSSFColor(new byte[]{(byte) Integer.parseInt(hex.substring(0, 2), 16),
                    (byte) Integer.parseInt(hex.substring(2, 4), 16), (byte) Integer.parseInt(hex.substring(4, 6), 16)}, null);
        }
    }

    byte[] html(Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>").append(esc(report.title()))
                .append("</title><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>")
                .append("body{font-family:-apple-system,BlinkMacSystemFont,'SF Pro Text','Segoe UI',Inter,system-ui,sans-serif;")
                .append("background:#f5f5f7;color:#1d1d1f;margin:0;padding:32px 24px;font-size:13px;line-height:1.45}")
                .append(".wrap{max-width:1280px;margin:0 auto}h1{font-size:28px;font-weight:600;letter-spacing:-.02em;margin:0 0 4px}")
                .append("h2{font-size:17px;font-weight:600;margin:0 0 12px}.sub{color:#6e6e73;margin-bottom:24px}")
                .append(".card{background:#fff;border-radius:16px;padding:20px 22px;margin-bottom:18px;box-shadow:0 1px 2px rgba(0,0,0,.04),0 6px 20px rgba(0,0,0,.04)}")
                .append(".scroll{overflow-x:auto}table{border-collapse:collapse;width:100%}th{text-align:left;font-weight:600;color:#6e6e73;")
                .append("font-size:11px;text-transform:uppercase;letter-spacing:.04em;padding:8px 10px;border-bottom:1px solid #e5e5ea;white-space:nowrap}")
                .append("td{padding:8px 10px;border-bottom:1px solid #f0f0f3;vertical-align:top}.meta td:first-child{color:#6e6e73;width:220px}")
                .append(".pill{display:inline-block;padding:2px 9px;border-radius:999px;font-size:11.5px;font-weight:500;white-space:nowrap}")
                .append(".g{background:#e6f6ec;color:#1b7f45}.r{background:#fdecec;color:#c4252c}.o{background:#fff3e0;color:#a85d00}")
                .append(".b{background:#e8f1fd;color:#0a5dc2}.p{background:#f4ebfa;color:#7b3fa0}.n{background:#f2f2f5;color:#515154}")
                .append(".empty{color:#86868b}@media print{body{background:#fff;padding:0}.card{box-shadow:none;border:1px solid #e5e5ea;break-inside:avoid-page}}")
                .append("</style></head><body><div class=\"wrap\">");
        sb.append("<h1>").append(esc(report.title())).append("</h1><div class=\"sub\">Helm Compare report</div>");
        sb.append("<div class=\"card\"><h2>Summary</h2><table class=\"meta\">");
        for (String[] m : report.meta()) {
            sb.append("<tr><td>").append(esc(m[0])).append("</td><td>").append(esc(m[1])).append("</td></tr>");
        }
        sb.append("</table></div>");
        for (Table table : report.tables()) {
            sb.append("<div class=\"card\"><h2>").append(esc(table.name())).append(" <span class=\"empty\">(")
                    .append(table.rows().size()).append(")</span></h2>");
            if (table.rows().isEmpty()) {
                sb.append("<div class=\"empty\">Nothing to show.</div></div>");
                continue;
            }
            sb.append("<div class=\"scroll\"><table><thead><tr>");
            table.headers().forEach(h -> sb.append("<th>").append(esc(h)).append("</th>"));
            sb.append("</tr></thead><tbody>");
            for (List<String> row : table.rows()) {
                sb.append("<tr>");
                for (int c = 0; c < row.size(); c++) {
                    String v = row.get(c) == null ? "" : row.get(c);
                    sb.append("<td>");
                    if (table.statusColumns().contains(c) && !v.isEmpty()) {
                        sb.append("<span class=\"pill ").append(tone(v)).append("\">").append(esc(label(v))).append("</span>");
                    } else {
                        sb.append(esc(v));
                    }
                    sb.append("</td>");
                }
                sb.append("</tr>");
            }
            sb.append("</tbody></table></div></div>");
        }
        sb.append("</div></body></html>");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static List<String> labelRow(List<String> row, Set<Integer> statusColumns) {
        List<String> out = new ArrayList<>(row);
        for (int c : statusColumns) if (c < out.size()) out.set(c, label(out.get(c)));
        return out;
    }

    private static String tone(String code) {
        if (GREEN.contains(code)) return "g";
        if (RED.contains(code)) return "r";
        if (ORANGE.contains(code)) return "o";
        if (BLUE.contains(code)) return "b";
        if (PURPLE.contains(code)) return "p";
        return "n";
    }

    static String label(String code) {
        if (code == null || code.isEmpty() || "null".equals(code)) return "";
        return switch (code) {
            case "NON_PROD" -> "NON-PROD";
            case "PROD" -> "PROD";
            case "PAIRWISE" -> "Two-chart diff";
            case "DIFF_COMPARE" -> "Two-diff comparison";
            case "FOUR_CHART" -> "Four-chart analysis";
            case "PORTFOLIO" -> "Portfolio analysis";
            case "ALREADY_IN_BASELINE" -> "Consistent (already in NON-PROD)";
            case "NO_LONGER_APPLICABLE" -> "No longer applicable / review";
            case "NEW_PROD_CHANGE" -> "New PROD change";
            case "TSC" -> "TSC / Topology Spread";
            case "SECURITY_CONTEXT" -> "Security Context";
            case "REQUIRES_CHANGE" -> "Requires change";
            case "NEEDS_CHANGE" -> "Change required";
            case "LOGICALLY_IDENTICAL" -> "Logically same";
            case "LEFT_ONLY" -> "Only in left";
            case "RIGHT_ONLY" -> "Only in right";
            case "PARTIAL" -> "Not in every folder";
            default -> {
                if (!code.equals(code.toUpperCase(Locale.ROOT)) || code.contains(" ")) yield code;
                String s = code.replace('_', ' ').toLowerCase(Locale.ROOT);
                yield Character.toUpperCase(s.charAt(0)) + s.substring(1);
            }
        };
    }

    private static String camelToCode(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static String header(ChartRef c) {
        return c == null ? "" : c.version + " " + label(String.valueOf(c.environment));
    }

    private static String source(ConfigItem i) {
        return i == null ? "Not present" : i.source.label;
    }

    private static String value(ConfigItem i) {
        return i == null ? "—" : nz(i.display);
    }

    private static String location(ConfigItem i) {
        if (i == null || i.locations.isEmpty()) return "";
        ConfigItem.Location l = i.locations.get(0);
        return l.file + ":" + l.line;
    }

    private static String yes(boolean b) {
        return b ? "Yes" : "";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String slug(String s) {
        String slug = s == null ? "analysis" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return slug.length() > 60 ? slug.substring(0, 60) : slug;
    }
}
