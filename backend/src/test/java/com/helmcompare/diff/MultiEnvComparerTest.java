package com.helmcompare.diff;

import com.helmcompare.diff.EnvVarExtractor.EnvVar;
import com.helmcompare.diff.MultiEnvComparer.Result;
import com.helmcompare.diff.MultiEnvComparer.Row;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MultiEnvComparerTest {

    private static List<EnvVar> env(String yaml) {
        return EnvVarExtractor.resolve(EnvVarExtractor.extract("svc/values.yaml", yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static Row row(Result r, String name) {
        return r.rows().stream().filter(x -> x.vars().stream().filter(Objects::nonNull).anyMatch(v -> v.name().equals(name)))
                .findFirst().orElse(null);
    }

    @Test
    void threeFoldersInOneRowPerVariable() {
        Result r = MultiEnvComparer.compare(new int[]{0, 1, 2}, List.of(
                env("env:\n  - name: LOG_LEVEL\n    value: debug\n  - name: REGION\n    value: eu\n"),
                env("env:\n  - name: LOG_LEVEL\n    value: info\n  - name: REGION\n    value: eu\n"),
                env("env:\n  - name: LOG_LEVEL\n    value: info\n  - name: REGION\n    value: eu\n  - name: EXTRA\n    value: x\n")));

        assertEquals(3, r.rows().size(), r.rows()::toString);
        Row level = row(r, "LOG_LEVEL");
        assertNotNull(level);
        assertEquals("VALUE_DIFFERS", level.comparison());
        assertEquals(List.of(), level.missingIn());
        assertEquals("SAME", row(r, "REGION").comparison());

        Row extra = row(r, "EXTRA");
        assertEquals(List.of(0, 1), extra.missingIn());
        assertNull(extra.comparison());
        assertEquals(List.of(1, 1, 0), r.summary().missing());
        assertEquals(2, r.summary().complete());
    }

    @Test
    void hiddenMiddleFolderComparesTheOtherTwo() {
        Result r = MultiEnvComparer.compare(new int[]{0, 2}, List.of(
                env("env:\n  - name: A\n    value: '1'\n"),
                List.of(),
                env("env:\n  - name: A\n    value: '1'\n")));
        assertEquals(1, r.rows().size());
        Row a = r.rows().get(0);
        assertEquals("SAME", a.comparison());
        assertNull(a.vars().get(1));
        assertEquals(List.of(), a.missingIn());
    }
}
