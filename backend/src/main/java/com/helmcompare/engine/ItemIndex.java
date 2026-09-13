package com.helmcompare.engine;

import com.helmcompare.model.ConfigItem;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lookup of a chart's items by logical key, with correlated and fuzzy matching for variables. */
public final class ItemIndex {

    public record Found(ConfigItem item, String matchType, double similarity) {
        public boolean exact() {
            return "KEY".equals(matchType) || "CORRELATED".equals(matchType);
        }
    }

    private final Map<String, ConfigItem> byKey = new LinkedHashMap<>();
    private final Map<String, List<ConfigItem>> variablesBySubject = new LinkedHashMap<>();

    public ItemIndex(List<ConfigItem> items) {
        for (ConfigItem it : items) {
            byKey.put(it.key, it);
            if ("variable".equals(it.family)) {
                variablesBySubject.computeIfAbsent(Similarity.normalize(it.subject), k -> new ArrayList<>()).add(it);
            }
        }
    }

    public ConfigItem get(String key) {
        return byKey.get(key);
    }

    public Collection<ConfigItem> all() {
        return byKey.values();
    }

    public java.util.Set<String> keys() {
        return byKey.keySet();
    }

    public Found find(ConfigItem ref) {
        return ref == null ? null : find(ref.key, ref.family, ref.subject);
    }

    public Found find(String key, String family, String subject) {
        ConfigItem exact = byKey.get(key);
        if (exact != null) return new Found(exact, "KEY", 1);
        if (key != null && key.contains("@")) {
            ConfigItem base = byKey.get(key.substring(0, key.indexOf('@')));
            if (base != null) return new Found(base, "KEY", 1);
        }
        if (!"variable".equals(family) || subject == null) return null;
        String normalized = Similarity.normalize(subject);
        List<ConfigItem> same = variablesBySubject.get(normalized);
        if (same != null && !same.isEmpty()) {
            ConfigItem preferred = same.stream().filter(i -> i.key.startsWith("env:")).findFirst().orElse(same.get(0));
            return new Found(preferred, "CORRELATED", 1);
        }
        ConfigItem best = null;
        double bestScore = 0;
        for (Map.Entry<String, List<ConfigItem>> e : variablesBySubject.entrySet()) {
            double score = Similarity.nameSimilarity(normalized, e.getKey());
            if (score > bestScore) {
                bestScore = score;
                best = e.getValue().get(0);
            }
        }
        return bestScore >= Similarity.NAME_THRESHOLD ? new Found(best, "SIMILAR_NAME", bestScore) : null;
    }
}
