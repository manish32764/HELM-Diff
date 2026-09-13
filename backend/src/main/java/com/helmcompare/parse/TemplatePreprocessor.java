package com.helmcompare.parse;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a Helm template into parseable YAML while keeping line numbers stable:
 * <ul>
 *   <li>control actions ({{ if }}, {{ end }}, {{ range }}, ...) are blanked,</li>
 *   <li>simple value references ({{ .Values.x | quote }}) are resolved against values.yaml,</li>
 *   <li>{{ toYaml .Values.x | nindent N }} is inlined as single-line flow YAML,</li>
 *   <li>anything unresolvable becomes a ‹placeholder›.</li>
 * </ul>
 * {{ with .Values.x }} scopes are tracked so {{ toYaml . }} inside them resolves too.
 */
public final class TemplatePreprocessor {

    private static final Pattern ACTION = Pattern.compile("\\{\\{(.*?)\\}\\}");
    private static final Pattern INDENT = Pattern.compile("\\bn?indent\\s+(\\d+)");
    private static final Pattern TO_YAML = Pattern.compile("(?:toYaml|toJson|toPrettyJson)\\s+(\\$?\\.[\\w.\\-]*)");
    private static final Pattern PIPE_TO_YAML = Pattern.compile("^(\\$?\\.[\\w.\\-]*)\\s*\\|\\s*(?:toYaml|toJson)");
    private static final Pattern NON_VALUES_ROOT = Pattern.compile("^\\.(Release|Chart|Capabilities|Template|Files)\\b.*");

    private TemplatePreprocessor() {
    }

    private record Resolved(String text, boolean quoted, boolean structured) {
    }

    public static String process(String text, YNode values) {
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        List<List<String>> scopes = new ArrayList<>();
        StringBuilder out = new StringBuilder();
        boolean inMultiline = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String result;
            if (inMultiline) {
                if (line.contains("}}")) inMultiline = false;
                result = "";
            } else {
                int open = line.lastIndexOf("{{");
                if (open >= 0 && line.indexOf("}}", open) < 0) {
                    inMultiline = true;
                    handleControl(clean(line.substring(open + 2)), scopes);
                    result = "";
                } else {
                    result = processLine(line, values, scopes);
                }
            }
            if (i > 0) out.append('\n');
            out.append(result);
        }
        return out.toString();
    }

    private static String processLine(String line, YNode values, List<List<String>> scopes) {
        Matcher m = ACTION.matcher(line);
        if (!m.find()) return line;
        m.reset();

        String residue = ACTION.matcher(line).replaceAll("").strip();
        if (residue.isEmpty()) {
            int outputs = 0;
            String output = null;
            while (m.find()) {
                String expr = clean(m.group(1));
                if (handleControl(expr, scopes)) continue;
                outputs++;
                output = expr;
            }
            if (outputs == 1) {
                YNode node = structuredTarget(output, values, top(scopes));
                if (node != null && !node.isNull()) {
                    int indent = leadingSpaces(line);
                    Matcher im = INDENT.matcher(output);
                    if (im.find()) indent = Integer.parseInt(im.group(1));
                    return " ".repeat(indent) + node.toFlowJson();
                }
            }
            return "";
        }

        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(line, last, m.start());
            last = m.end();
            String expr = clean(m.group(1));
            if (handleControl(expr, scopes)) continue;
            Resolved r = resolveInline(expr, values, top(scopes));
            String before = sb.toString();
            String after = line.substring(last);
            sb.append(render(r, expr, before, after));
        }
        sb.append(line.substring(last));
        return sb.toString();
    }

    private static String render(Resolved r, String expr, String before, String after) {
        boolean inDouble = count(before, '"') % 2 == 1;
        boolean inSingle = !inDouble && count(before, '\'') % 2 == 1;
        if (r == null) return "‹" + sanitize(expr) + "›";
        String v = r.text();
        if (inDouble) return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        if (inSingle) return v.replace("'", "''").replace("\n", " ");
        if (r.structured()) return v;
        String trimmedBefore = before.stripTrailing();
        boolean wholeValue = after.isBlank()
                && (trimmedBefore.isEmpty() || trimmedBefore.endsWith(":") || trimmedBefore.endsWith("-"));
        if (wholeValue && (r.quoted() || needsQuotes(v))) return YNode.jsonString(v);
        return v;
    }

    private static boolean needsQuotes(String v) {
        if (v.isEmpty()) return true;
        if (v.contains(": ") || v.contains(" #") || v.contains("\n") || v.endsWith(":")) return true;
        return "[]{}&*!|>'\"%@`,?#-".indexOf(v.charAt(0)) >= 0;
    }

    /** Updates the scope stack for control actions. Returns true if the action produces no output. */
    private static boolean handleControl(String expr, List<List<String>> scopes) {
        if (expr.startsWith("/*")) return true;
        String word = firstWord(expr);
        switch (word) {
            case "if" -> scopes.add(top(scopes));
            case "with" -> scopes.add(pathOf(expr.substring(4).trim(), top(scopes)));
            case "range", "define", "block" -> scopes.add(null);
            case "end" -> {
                if (!scopes.isEmpty()) scopes.remove(scopes.size() - 1);
            }
            case "else" -> {
                // same scope
            }
            default -> {
                return word.startsWith("$") && expr.contains(":=");
            }
        }
        return true;
    }

    private static YNode structuredTarget(String expr, YNode values, List<String> scope) {
        if (values == null) return null;
        String token = null;
        Matcher t = TO_YAML.matcher(expr);
        if (t.find()) {
            token = t.group(1);
        } else {
            Matcher p = PIPE_TO_YAML.matcher(expr);
            if (p.find()) token = p.group(1);
        }
        if (token == null) return null;
        List<String> path = pathOf(token, scope);
        return path == null ? null : values.at(path);
    }

    private static Resolved resolveInline(String expr, YNode values, List<String> scope) {
        String[] stages = expr.split("\\|");
        String[] words = stages[0].trim().split("\\s+");
        if (words.length == 0 || words[0].isEmpty()) return null;

        boolean quoted = false;
        boolean structured = false;
        String fallback = null;
        String token;
        switch (words[0]) {
            case "quote", "squote" -> {
                quoted = true;
                token = words.length == 2 ? words[1] : null;
            }
            case "toYaml", "toJson" -> {
                structured = true;
                token = words.length == 2 ? words[1] : null;
            }
            case "default" -> {
                fallback = words.length >= 3 ? unquote(words[1]) : null;
                token = words.length == 3 ? words[2] : null;
            }
            case "required" -> token = words[words.length - 1];
            default -> token = words.length == 1 ? words[0] : null;
        }
        if (token == null) return null;

        List<String> path = pathOf(token, scope);
        String text = null;
        if (path != null && values != null) {
            YNode node = values.at(path);
            if (node != null && !node.isNull()) {
                if (node.isScalar()) {
                    text = node.value;
                } else {
                    text = node.toFlowJson();
                    structured = true;
                }
            }
        } else if (path == null && fallback == null) {
            return null;
        }
        if ((text == null || text.isEmpty()) && fallback != null) text = fallback;

        for (int i = 1; i < stages.length; i++) {
            String stage = stages[i].trim();
            String fn = firstWord(stage);
            switch (fn) {
                case "default" -> {
                    if (text == null || text.isEmpty()) text = unquote(stage.substring(7).trim());
                }
                case "quote", "squote" -> quoted = true;
                case "toString", "int", "int64", "float64", "toYaml", "toJson", "nindent", "indent" -> {
                    // formatting only
                }
                case "trim" -> text = text == null ? null : text.strip();
                case "lower" -> text = text == null ? null : text.toLowerCase(Locale.ROOT);
                case "upper" -> text = text == null ? null : text.toUpperCase(Locale.ROOT);
                case "b64enc" -> text = text == null ? null
                        : Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
                default -> {
                    return null;
                }
            }
        }
        return text == null ? null : new Resolved(text, quoted, structured);
    }

    /** Resolves a template token to a path inside values, or null when it does not refer to values. */
    private static List<String> pathOf(String token, List<String> scope) {
        token = token.trim();
        if (token.contains(" ") || token.contains("(")) return null;
        if (token.startsWith("$.")) token = token.substring(1);
        if (token.equals(".Values")) return List.of();
        if (token.startsWith(".Values.")) return split(token.substring(8));
        if (token.equals(".")) return scope;
        if (token.startsWith(".") && scope != null && !NON_VALUES_ROOT.matcher(token).matches()) {
            List<String> p = new ArrayList<>(scope);
            p.addAll(split(token.substring(1)));
            return p;
        }
        return null;
    }

    private static List<String> split(String dotted) {
        return Arrays.stream(dotted.split("\\.")).filter(s -> !s.isEmpty()).toList();
    }

    private static List<String> top(List<List<String>> scopes) {
        return scopes.isEmpty() ? null : scopes.get(scopes.size() - 1);
    }

    private static String clean(String expr) {
        String e = expr.strip();
        if (e.startsWith("-")) e = e.substring(1);
        if (e.endsWith("-")) e = e.substring(0, e.length() - 1);
        return e.strip();
    }

    private static String firstWord(String s) {
        String t = s.strip();
        int i = 0;
        while (i < t.length() && !Character.isWhitespace(t.charAt(i))) i++;
        return t.substring(0, i);
    }

    private static String unquote(String s) {
        String t = s.strip();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'"))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static String sanitize(String expr) {
        String s = expr.replaceAll("[^A-Za-z0-9_.$ /-]", "").replaceAll("\\s+", " ").trim();
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    private static int leadingSpaces(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') i++;
        return i;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
