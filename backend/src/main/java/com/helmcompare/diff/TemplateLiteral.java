package com.helmcompare.diff;

import com.helmcompare.parse.YNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Makes a Helm template parseable as YAML <em>without</em> resolving it, so two template sources
 * can be compared structurally. Line numbers are preserved.
 * <ul>
 *   <li>Output actions become normalised placeholders ({{- .Values.x }} and {{ .Values.x }} are the same).</li>
 *   <li>Control actions (if / range / with / define / end …) are removed from the YAML and returned as
 *       template-logic directives so they can be compared separately.</li>
 *   <li>Template comments are ignored.</li>
 * </ul>
 */
public final class TemplateLiteral {

    public record Directive(String text, int line) {
    }

    public record Result(String yaml, List<Directive> directives) {
    }

    private static final Pattern ACTION = Pattern.compile("\\{\\{(.*?)\\}\\}");
    private static final Set<String> CONTROL = Set.of("if", "else", "end", "range", "with", "define", "block");

    private TemplateLiteral() {
    }

    public static Result process(String text) {
        String[] lines = text.split("\n", -1);
        String[] out = new String[lines.length];
        List<Directive> directives = new ArrayList<>();
        StringBuilder multi = null;
        int multiStart = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (multi != null) {
                int close = line.indexOf("}}");
                if (close < 0) {
                    multi.append(' ').append(line.strip());
                    out[i] = "";
                    continue;
                }
                multi.append(' ').append(line, 0, close);
                String expr = normalize(multi.toString());
                if (!expr.startsWith("/*")) directives.add(new Directive(expr, multiStart));
                multi = null;
                String rest = line.substring(close + 2);
                out[i] = rest.isBlank() ? "" : processLine(rest, i, lines, directives);
                continue;
            }
            int open = line.lastIndexOf("{{");
            if (open >= 0 && line.indexOf("}}", open) < 0) {
                multi = new StringBuilder(line.substring(open + 2));
                multiStart = i + 1;
                String before = line.substring(0, open);
                out[i] = before.isBlank() ? "" : processLine(before, i, lines, directives);
                continue;
            }
            out[i] = processLine(line, i, lines, directives);
        }
        return new Result(String.join("\n", out), directives);
    }

    private static String processLine(String line, int index, String[] lines, List<Directive> directives) {
        Matcher m = ACTION.matcher(line);
        if (!m.find()) return line;
        m.reset();
        String residue = ACTION.matcher(line).replaceAll("").strip();

        if (residue.isEmpty()) {
            List<String> outputs = new ArrayList<>();
            while (m.find()) {
                String expr = normalize(m.group(1));
                if (expr.startsWith("/*")) continue;
                if (isControl(expr)) directives.add(new Directive(expr, index + 1));
                else outputs.add(expr);
            }
            if (outputs.isEmpty()) return "";
            int indent = leadingSpaces(line);
            String token = YNode.jsonString("«" + String.join(" ", outputs) + "»");
            return " ".repeat(indent) + (sequenceContext(lines, index, indent) ? "- " + token : token + ": \"\"");
        }

        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(line, last, m.start());
            last = m.end();
            String expr = normalize(m.group(1));
            if (expr.startsWith("/*")) continue;
            if (isControl(expr)) {
                directives.add(new Directive(expr, index + 1));
                continue;
            }
            sb.append('«').append(sanitize(expr)).append('»');
        }
        sb.append(line.substring(last));
        return sb.toString();
    }

    /** True when an output-only line sits among sequence items ("- ...") at the same indentation. */
    private static boolean sequenceContext(String[] lines, int index, int indent) {
        for (int step : new int[]{1, -1}) {
            for (int j = index + step; j >= 0 && j < lines.length; j += step) {
                String content = ACTION.matcher(lines[j]).replaceAll("");
                if (content.isBlank()) continue;
                int ind = leadingSpaces(lines[j]);
                if (ind == indent) {
                    if (content.stripLeading().startsWith("- ")) return true;
                    break;
                }
                if (ind < indent) break;
            }
        }
        return false;
    }

    static boolean isControl(String expr) {
        String word = firstWord(expr);
        return CONTROL.contains(word) || (word.startsWith("$") && expr.contains(":="));
    }

    static String normalize(String inner) {
        String e = inner.strip();
        if (e.startsWith("-")) e = e.substring(1);
        if (e.endsWith("-")) e = e.substring(0, e.length() - 1);
        return e.strip()
                .replaceAll("\\s+", " ")
                .replaceAll("\\s*\\|\\s*", " | ")
                .replaceAll("\\(\\s+", "(")
                .replaceAll("\\s+\\)", ")");
    }

    private static String sanitize(String expr) {
        return expr.replace('"', '`').replace('\'', '`').replace(": ", ":").replace(" #", "#");
    }

    private static String firstWord(String s) {
        int i = 0;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(0, i);
    }

    private static int leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i;
    }
}
