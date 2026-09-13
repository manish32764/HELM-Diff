package com.helmcompare.engine;

import com.helmcompare.model.ConfigItem;
import com.helmcompare.model.DiffEntry.FieldChange;
import com.helmcompare.model.ValueSource.SourceClass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Name and state similarity used to recognise the same logical configuration or change. */
public final class Similarity {

    public enum Match { SAME, SIMILAR, DIFFERENT }

    public static final double NAME_THRESHOLD = 0.8;

    private static final Map<String, String> SYNONYMS = Map.ofEntries(
            Map.entry("DATABASE", "DB"), Map.entry("PASS", "PASSWORD"), Map.entry("PWD", "PASSWORD"),
            Map.entry("PASSWD", "PASSWORD"), Map.entry("USR", "USER"), Map.entry("USERNAME", "USER"),
            Map.entry("HOSTNAME", "HOST"), Map.entry("SERVER", "HOST"), Map.entry("CONNECTION", "CONN"),
            Map.entry("CONFIG", "CFG"), Map.entry("CONFIGURATION", "CFG"), Map.entry("ENVIRONMENT", "ENV"),
            Map.entry("TKN", "TOKEN"), Map.entry("APIKEY", "API_KEY"), Map.entry("SECRETS", "SECRET"),
            Map.entry("URI", "URL"), Map.entry("ENDPOINT", "URL"));

    private Similarity() {
    }

    public static String normalize(String name) {
        if (name == null) return "";
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    public static double nameSimilarity(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) return 0;
        if (na.equals(nb)) return 1;
        int max = Math.max(na.length(), nb.length());
        double lev = 1.0 - (double) levenshtein(na, nb) / max;

        List<String> ta = tokens(na);
        List<String> tb = tokens(nb);
        Set<String> union = new LinkedHashSet<>(ta);
        union.addAll(tb);
        int matched = 0;
        for (String x : ta) {
            for (String y : tb) {
                if (x.equals(y) || (Math.min(x.length(), y.length()) >= 3 && (x.startsWith(y) || y.startsWith(x)))) {
                    matched++;
                    break;
                }
            }
        }
        double soft = (double) matched / Math.max(ta.size(), tb.size());
        if (new LinkedHashSet<>(ta).equals(new LinkedHashSet<>(tb))) soft = 1;
        return Math.max(lev, soft * 0.9);
    }

    private static List<String> tokens(String normalized) {
        return Arrays.stream(normalized.split("_"))
                .filter(t -> !t.isEmpty())
                .map(t -> SYNONYMS.getOrDefault(t, t))
                .toList();
    }

    private static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** SAME: equivalent; SIMILAR: same kind of configuration with different details; DIFFERENT otherwise. */
    public static Match compareStates(ConfigItem x, ConfigItem y) {
        if (x == null && y == null) return Match.SAME;
        if (x == null || y == null) return Match.DIFFERENT;
        if (Objects.equals(x.canonical, y.canonical) && x.enabled == y.enabled) return Match.SAME;
        if (x.enabled != y.enabled) return Match.DIFFERENT;
        if (x.block && y.block) return Match.SIMILAR;
        if (sourceClass(x) == sourceClass(y) && x.source.isSecretMechanism()) return Match.SIMILAR;
        return Match.DIFFERENT;
    }

    public static SourceClass sourceClass(ConfigItem item) {
        return item == null || item.source == null ? null : item.source.sourceClass;
    }

    public static boolean isSecretClass(ConfigItem item) {
        SourceClass c = sourceClass(item);
        return c == SourceClass.SECRET || c == SourceClass.AKEYLESS;
    }

    public static List<FieldChange> fieldChanges(ConfigItem left, ConfigItem right) {
        List<FieldChange> out = new ArrayList<>();
        if (left == null || right == null) return out;
        Set<String> keys = new LinkedHashSet<>(left.fields.keySet());
        keys.addAll(right.fields.keySet());
        for (String k : keys) {
            String l = left.fields.get(k);
            String r = right.fields.get(k);
            if (Objects.equals(l, r)) continue;
            String kind = l == null ? "ADDED" : r == null ? "REMOVED" : "CHANGED";
            out.add(new FieldChange(k, l, r, kind));
        }
        if (left.enabled != right.enabled) {
            out.add(0, new FieldChange("enabled", String.valueOf(left.enabled), String.valueOf(right.enabled), "CHANGED"));
        }
        return out;
    }
}
