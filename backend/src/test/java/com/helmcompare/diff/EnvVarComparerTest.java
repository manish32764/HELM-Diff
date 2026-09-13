package com.helmcompare.diff;

import com.helmcompare.diff.EnvVarComparer.Result;
import com.helmcompare.diff.EnvVarComparer.Row;
import com.helmcompare.diff.EnvVarExtractor.EnvVar;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvVarComparerTest {

    private static List<EnvVar> extract(String file, String yaml) {
        return EnvVarExtractor.extract(file, yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static Row row(Result r, String name) {
        return r.rows().stream()
                .filter(x -> (x.left() != null && x.left().name().equals(name)) || (x.right() != null && x.right().name().equals(name)))
                .findFirst().orElse(null);
    }

    @Test
    void plainNonProdAgainstAkeylessProd() {
        List<EnvVar> nonProd = extract("values.yaml", """
                env:
                  - name: DB_PASSWORD
                    value: "np-secret"
                  - name: API_TOKEN
                    value: "np-token"
                  - name: LOG_LEVEL
                    value: DEBUG
                  - name: ONLY_NP
                    value: x
                """);
        List<EnvVar> prod = new ArrayList<>(extract("values.yaml", """
                env:
                  - name: DB_PASSWORD
                    valueFrom:
                      secretKeyRef:
                        name: app-secrets
                        key: db-password
                  - name: PROD_API_TOKEN_AKEYLESS
                    value: "akeyless:/prod/app/api-token"
                  - name: LOG_LEVEL
                    value: INFO
                """));
        prod.addAll(extract("templates/externalsecret.yaml", """
                apiVersion: external-secrets.io/v1beta1
                kind: ExternalSecret
                metadata:
                  name: app-secrets
                spec:
                  secretStoreRef:
                    name: akeyless-store
                  target:
                    name: app-secrets
                  data:
                    - secretKey: db-password
                      remoteRef:
                        key: /prod/app/db-password
                """));
        Result r = EnvVarComparer.compare(EnvVarExtractor.resolve(nonProd), EnvVarExtractor.resolve(prod));

        Row db = row(r, "DB_PASSWORD");
        assertEquals("COMMON", db.status());
        assertEquals("SOURCE_CHANGED", db.comparison());
        assertEquals("AKEYLESS", db.right().source());
        assertTrue(db.right().reference().startsWith("/prod/app/db-password"), db.right().reference());

        Row token = row(r, "API_TOKEN");
        assertNotNull(token.right(), "API_TOKEN should match PROD_API_TOKEN_AKEYLESS");
        assertEquals("SIMILAR_NAME", token.match());

        assertEquals("VALUE_DIFFERS", row(r, "LOG_LEVEL").comparison());
        assertEquals("LEFT_ONLY", row(r, "ONLY_NP").status());
        assertEquals(0, r.summary().rightOnly(), r.rows().toString());
    }

    @Test
    void prefixedAndSuffixedNamesMatch() {
        assertTrue(EnvVarComparer.nameScore("DB_PASSWORD", "PAYMENTS_DB_PASSWORD") >= 0.8);
        assertTrue(EnvVarComparer.nameScore("db-password", "DB_PASSWORD") >= 0.9);
        assertTrue(EnvVarComparer.nameScore("dbPassword", "DB_PASSWORD_PROD") >= 0.8);
        assertTrue(EnvVarComparer.nameScore("DB_HOST", "API_TOKEN") < 0.8);
    }

    @Test
    void valuesStyleMapsAndTemplates() {
        List<EnvVar> vars = extract("templates/deployment.yaml", """
                spec:
                  containers:
                    - name: app
                      env:
                        - name: REGION
                          value: {{ .Values.region | quote }}
                        {{- toYaml .Values.extraEnv | nindent 8 }}
                """);
        assertEquals(1, vars.size(), vars.toString());
        assertEquals("TEMPLATE", vars.get(0).source());
        assertTrue(vars.get(0).value().contains("{{"), vars.get(0).value());
    }
}
