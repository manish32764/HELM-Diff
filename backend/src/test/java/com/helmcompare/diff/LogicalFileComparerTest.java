package com.helmcompare.diff;

import com.helmcompare.diff.LogicalFileComparer.Diff;
import com.helmcompare.diff.LogicalFileComparer.Result;
import com.helmcompare.diff.LogicalFileComparer.Status;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogicalFileComparerTest {

    private static Result compare(String name, String left, String right) {
        return LogicalFileComparer.compare(name, left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void identicalBytes() {
        assertEquals(Status.IDENTICAL, compare("a.yaml", "a: 1\n", "a: 1\n").status());
    }

    @Test
    void reorderedKeysCommentsAndQuotingAreLogicallyIdentical() {
        String left = """
                apiVersion: v1
                kind: Service
                metadata:
                  name: web
                spec:
                  type: ClusterIP
                  ports:
                    - name: http
                      port: 80
                """;
        String right = """
                # service definition
                kind: Service
                apiVersion: "v1"
                spec:
                  ports:
                  - port: 80
                    name: http
                  type:   ClusterIP
                metadata: {name: web}
                """;
        Result r = compare("service.yaml", left, right);
        assertEquals(Status.LOGICALLY_IDENTICAL, r.status(), () -> r.diffs().toString());
    }

    @Test
    void reorderedEnvListAndTemplateWhitespaceAreLogicallyIdentical() {
        String left = """
                env:
                  - name: A
                    value: {{ .Values.a | quote }}
                  - name: B
                    value: "2"
                {{- if .Values.probes.enabled }}
                livenessProbe:
                  {{- toYaml .Values.probe | nindent 2 }}
                {{- end }}
                """;
        String right = """
                {{ if .Values.probes.enabled -}}
                livenessProbe:
                  {{ toYaml .Values.probe   |   nindent 2 }}
                {{ end }}
                env:
                  - name: B
                    value: 2
                  - name: A
                    value: {{- .Values.a | quote }}
                """;
        Result r = compare("deployment.yaml", left, right);
        assertEquals(Status.LOGICALLY_IDENTICAL, r.status(), () -> r.diffs().toString());
    }

    @Test
    void changedValueReportsPathAndLines() {
        String left = "spec:\n  replicas: 1\n  strategy: Recreate\n";
        String right = "spec:\n  strategy: Recreate\n\n  replicas: 3\n";
        Result r = compare("deployment.yaml", left, right);
        assertEquals(Status.DIFFERS, r.status());
        assertEquals(1, r.diffs().size());
        Diff d = r.diffs().get(0);
        assertEquals("CHANGED", d.kind());
        assertEquals("spec.replicas", d.path());
        assertEquals(2, d.leftStart());
        assertEquals(4, d.rightStart());
    }

    @Test
    void addedBlockHasRightRange() {
        String left = "spec:\n  containers:\n    - name: app\n      image: x\n";
        String right = "spec:\n  containers:\n    - name: app\n      image: x\n      readinessProbe:\n        httpGet:\n          path: /ready\n";
        Result r = compare("deployment.yaml", left, right);
        assertEquals(1, r.diffs().size(), () -> r.diffs().toString());
        Diff d = r.diffs().get(0);
        assertEquals("ADDED", d.kind());
        assertTrue(d.path().endsWith("containers[app].readinessProbe"), d.path());
        assertEquals(5, d.rightStart());
        assertEquals(7, d.rightEnd());
    }

    @Test
    void templateLogicDifference() {
        String left = "{{- if .Values.enabled }}\nkind: Service\n{{- end }}\n";
        String right = "{{- if .Values.other }}\nkind: Service\n{{- end }}\n";
        Result r = compare("svc.yaml", left, right);
        assertEquals(Status.DIFFERS, r.status());
        assertEquals(2, r.diffs().size());
    }

    @Test
    void textFilesIgnoreSpacingAndOrder() {
        assertEquals(Status.LOGICALLY_IDENTICAL, compare("NOTES.txt", "a  b\n\nc\n", "c\n a b\n").status());
        Result r = compare("_helpers.tpl", "one\ntwo\nthree\n", "one\nTWO\nthree\n");
        assertEquals(Status.DIFFERS, r.status());
        assertEquals(1, r.diffs().size());
        assertEquals(2, r.diffs().get(0).leftStart());
    }
}
