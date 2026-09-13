package com.helmcompare.parse;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Minimal YAML tree that keeps 1-based source line numbers for navigation. */
public final class YNode {
    public enum Kind { MAP, SEQ, SCALAR, NULL }

    public final Kind kind;
    public final int line;
    public final String value;
    public final LinkedHashMap<String, YNode> map;
    public final Map<String, Integer> keyLines;
    public final List<YNode> seq;

    private YNode(Kind kind, int line, String value) {
        this.kind = kind;
        this.line = line;
        this.value = value;
        this.map = kind == Kind.MAP ? new LinkedHashMap<>() : null;
        this.keyLines = kind == Kind.MAP ? new HashMap<>() : null;
        this.seq = kind == Kind.SEQ ? new ArrayList<>() : null;
    }

    public static YNode map(int line) {
        return new YNode(Kind.MAP, line, null);
    }

    public static YNode seq(int line) {
        return new YNode(Kind.SEQ, line, null);
    }

    public static YNode scalar(int line, String value) {
        return new YNode(Kind.SCALAR, line, value);
    }

    public static YNode nul(int line) {
        return new YNode(Kind.NULL, line, null);
    }

    public boolean isMap() {
        return kind == Kind.MAP;
    }

    public boolean isSeq() {
        return kind == Kind.SEQ;
    }

    public boolean isScalar() {
        return kind == Kind.SCALAR;
    }

    public boolean isNull() {
        return kind == Kind.NULL;
    }

    public boolean has(String key) {
        return map != null && map.containsKey(key);
    }

    public YNode get(String key) {
        return map == null ? null : map.get(key);
    }

    /** Scalar value of a child key, or null. */
    public String str(String key) {
        YNode n = get(key);
        return n != null && n.kind == Kind.SCALAR ? n.value : null;
    }

    public int keyLine(String key) {
        Integer l = keyLines == null ? null : keyLines.get(key);
        return l == null ? line : l;
    }

    public void put(String key, int keyLine, YNode value) {
        map.put(key, value);
        keyLines.put(key, keyLine);
    }

    public YNode at(List<String> path) {
        YNode cur = this;
        for (String p : path) {
            if (cur == null || cur.map == null) return null;
            cur = cur.map.get(p);
        }
        return cur;
    }

    public boolean containsText(String needleLower) {
        return switch (kind) {
            case SCALAR -> value != null && value.toLowerCase(Locale.ROOT).contains(needleLower);
            case NULL -> false;
            case SEQ -> seq.stream().anyMatch(c -> c.containsText(needleLower));
            case MAP -> map.entrySet().stream().anyMatch(e ->
                    e.getKey().toLowerCase(Locale.ROOT).contains(needleLower) || e.getValue().containsText(needleLower));
        };
    }

    /** Deep merge: overlay wins for scalars and sequences, maps are merged recursively. */
    public static YNode deepMerge(YNode base, YNode overlay) {
        if (overlay == null) return base;
        if (base == null || !base.isMap() || !overlay.isMap()) return overlay;
        YNode out = YNode.map(base.line);
        base.map.forEach((k, v) -> out.put(k, base.keyLine(k), v));
        overlay.map.forEach((k, v) -> out.put(k, overlay.keyLine(k), deepMerge(out.get(k), v)));
        return out;
    }

    /** Single-line JSON (valid YAML flow syntax) so line numbers are preserved when inlined into templates. */
    public String toFlowJson() {
        StringBuilder sb = new StringBuilder();
        writeJson(sb);
        return sb.toString();
    }

    private void writeJson(StringBuilder sb) {
        switch (kind) {
            case NULL -> sb.append("null");
            case SCALAR -> sb.append(jsonString(value));
            case SEQ -> {
                sb.append('[');
                for (int i = 0; i < seq.size(); i++) {
                    if (i > 0) sb.append(", ");
                    seq.get(i).writeJson(sb);
                }
                sb.append(']');
            }
            case MAP -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, YNode> e : map.entrySet()) {
                    if (!first) sb.append(", ");
                    first = false;
                    sb.append(jsonString(e.getKey())).append(": ");
                    e.getValue().writeJson(sb);
                }
                sb.append('}');
            }
        }
    }

    public static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
