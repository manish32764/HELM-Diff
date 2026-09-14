package com.helmcompare.diff;

import com.helmcompare.diff.MultiFileComparer.SideDiff;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MultiFileComparerTest {

    private static List<LogicalFileComparer.Diff> diffs(String name, String a, String b) {
        return LogicalFileComparer.compare(name, a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)).diffs();
    }

    private static List<SideDiff> merge(String name, String... files) {
        return MultiFileComparer.merge(3, 0, 1, 2, diffs(name, files[0], files[1]), diffs(name, files[0], files[2]),
                diffs(name, files[1], files[2]));
    }

    private static SideDiff row(List<SideDiff> rows, String path) {
        return rows.stream().filter(d -> d.path().equals(path)).findFirst().orElse(null);
    }

    @Test
    void pairKeepsValuesAndLinesOnTheirSides() {
        List<SideDiff> rows = MultiFileComparer.pair(3, 0, 2, diffs("v.yaml", "replicas: 1\n", "replicas: 3\n"));
        assertEquals(1, rows.size());
        SideDiff d = rows.get(0);
        assertEquals(List.of(0, 2), d.sides());
        assertEquals(Arrays.asList("1", null, "3"), d.values());
        assertEquals(List.of(1, 0, 1), d.starts());
        assertEquals("CHANGED", d.kind());
    }

    @Test
    void oneRowPerSettingWithTheValueOfEveryFolder() {
        List<SideDiff> rows = merge("values.yaml",
                "replicas: 1\nimage: app:1\n",
                "replicas: 2\nimage: app:1\n",
                "replicas: 3\nimage: app:2\n");
        SideDiff replicas = row(rows, "replicas");
        assertNotNull(replicas, rows::toString);
        assertEquals(List.of("1", "2", "3"), replicas.values());
        assertEquals(List.of(0, 1, 2), replicas.sides());
        SideDiff image = row(rows, "image");
        assertNotNull(image, rows::toString);
        assertEquals(List.of("app:1", "app:1", "app:2"), image.values());
        assertEquals(2, rows.size(), rows::toString);
    }

    @Test
    void settingMissingInOneFolderIsMissingRow() {
        List<SideDiff> rows = merge("values.yaml",
                "a: 1\n",
                "a: 1\nb: 2\n",
                "a: 1\nb: 2\n");
        assertEquals(1, rows.size(), rows::toString);
        SideDiff b = rows.get(0);
        assertEquals("MISSING", b.kind());
        assertNull(b.values().get(0));
        assertEquals("2", b.values().get(1));
        assertEquals("2", b.values().get(2));
        assertEquals(2, b.starts().get(1));
        assertEquals(2, b.starts().get(2));
    }

    @Test
    void settingInsideABlockMissingInOneFolderIsAbsentThere() {
        List<SideDiff> rows = merge("values.yaml",
                "name: x\n",
                "name: x\nresources:\n  limits:\n    cpu: 1\n    memory: 1Gi\n",
                "name: x\nresources:\n  limits:\n    cpu: 1\n");
        SideDiff memory = row(rows, "resources.limits.memory");
        assertNotNull(memory, rows::toString);
        assertEquals(List.of(0, 1, 2), memory.sides());
        assertNull(memory.values().get(0));
        assertEquals("1Gi", memory.values().get(1));
        assertNull(memory.values().get(2));
    }
}
