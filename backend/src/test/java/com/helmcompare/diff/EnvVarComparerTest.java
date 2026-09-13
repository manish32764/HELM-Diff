package com.helmcompare.diff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.helmcompare.diff.EnvVarComparer.Result;
import com.helmcompare.diff.EnvVarComparer.Row;
import com.helmcompare.diff.EnvVarExtractor.EnvVar;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals("UNVERIFIED", db.comparison());
        assertTrue(db.sourceChanged());
        assertEquals("AKEYLESS", db.right().source());
        assertEquals("/prod/app/db-password", db.right().akeylessPath());
        assertTrue(db.right().reference().startsWith("/prod/app/db-password"), db.right().reference());

        Row token = row(r, "API_TOKEN");
        assertNotNull(token.right(), "API_TOKEN should match PROD_API_TOKEN_AKEYLESS");
        assertEquals("SIMILAR_NAME", token.match());
        assertEquals("/prod/app/api-token", token.right().akeylessPath());

        assertEquals("VALUE_DIFFERS", row(r, "LOG_LEVEL").comparison());
        assertEquals("LEFT_ONLY", row(r, "ONLY_NP").status());
        assertEquals(0, r.summary().rightOnly(), r.rows().toString());

        AkeylessValues values = new AkeylessValues(Map.of("/prod/app/db-password", "np-secret", "/prod/app/api-token", "other"));
        Result resolved = EnvVarComparer.compare(values.apply(EnvVarExtractor.resolve(nonProd)), values.apply(EnvVarExtractor.resolve(prod)));
        assertEquals("SAME", row(resolved, "DB_PASSWORD").comparison());
        assertEquals("VALUE_DIFFERS", row(resolved, "API_TOKEN").comparison());
    }

    @Test
    void envVarsEnvSecretsAndAkeylessSecretItems() throws Exception {
        List<EnvVar> nonProd = extract("poBackend/helm/values.yaml", """
                externalsecrets:
                  enabled: true
                  refreshInterval: 5m
                  akeyless:
                    enabled: true
                    secretStoreName: akeyless-secret-store
                    secretItems:
                      KEYCLOAK_SERVER_URL:
                        path: "/Platform/dev/KEYCLOAK_SERVER_URL"
                envVars:
                  JIRA_BASE_URL: https://jira.example.com
                  DB_NAME: psqldb-d
                  KEYCLOAK_SERVER_URL: "https://kc"
                  LANGFUSE_SECRET_KEY: abc
                  VAULT_PROVIDER: akeyless
                  ONLY_NP: x
                """);
        List<EnvVar> prod = extract("poBackend/helm/values.yaml", """
                externalsecrets:
                  enabled: true
                  akeyless:
                    enabled: true
                    secretStoreName: akeyless-secret-store
                    secretItems:
                      JIRA_BASE_URL:
                        path: "/Platform/prod/JIRA_BASE_URL"
                      LANGFUSE_SECRET_KEY_PO:
                        path: "/Platform/prod/LANGFUSE_SECRET_KEY_PO"
                      KEYCLOAK_SERVER_URL:
                        path: "/Platform/prod/KEYCLOAK_SERVER_URL"
                envVars:
                  DB_NAME: psqldb-p
                  VAULT_PROVIDER: akeyless
                envSecrets:
                  - name: JIRA_BASE_URL
                    secretName: attlasian-mcp-server
                    secretKey: JIRA_BASE_URL
                  - name: LANGFUSE_SECRET_KEY
                    secretName: ai-assist-po
                    secretKey: LANGFUSE_SECRET_KEY_PO
                """);
        AkeylessValues values = new AkeylessValues(AkeylessValues.parse(new ObjectMapper().readTree("""
                {
                  "/Platform/prod/JIRA_BASE_URL": "https://jira.example.com",
                  "Platform": { "prod": { "LANGFUSE_SECRET_KEY_PO": "xyz" } },
                  "secrets": [ { "path": "Platform/dev/KEYCLOAK_SERVER_URL", "value": "https://kc-other" } ]
                }
                """)));
        Result r = EnvVarComparer.compare(values.apply(EnvVarExtractor.resolve(nonProd)), values.apply(EnvVarExtractor.resolve(prod)));

        Row jira = row(r, "JIRA_BASE_URL");
        assertEquals("SAME", jira.comparison(), jira.toString());
        assertTrue(jira.sourceChanged());
        assertEquals("RESOLVED", jira.right().valueState());
        assertTrue(jira.right().injection().startsWith("envSecrets › Secret attlasian-mcp-server · key JIRA_BASE_URL › "
                + "externalsecrets.akeyless.secretItems @ poBackend/helm/values.yaml:"), jira.right().injection());

        assertEquals("VALUE_DIFFERS", row(r, "LANGFUSE_SECRET_KEY").comparison());
        assertNull(row(r, "LANGFUSE_SECRET_KEY_PO"), "a secret item consumed by envSecrets is shown on the env row");
        assertEquals("VALUE_DIFFERS", row(r, "DB_NAME").comparison());
        assertEquals("SAME", row(r, "VAULT_PROVIDER").comparison());
        assertFalse(row(r, "VAULT_PROVIDER").sourceChanged());

        Row keycloak = row(r, "KEYCLOAK_SERVER_URL");
        assertEquals("LITERAL", keycloak.left().valueState());
        assertEquals(1, keycloak.leftOthers().size());
        assertTrue(keycloak.duplicateConflict(), "envVars https://kc vs AKeyless https://kc-other");
        assertEquals("NOT_IN_JSON", keycloak.right().valueState());
        assertEquals("UNVERIFIED", keycloak.comparison());

        assertEquals("LEFT_ONLY", row(r, "ONLY_NP").status());
        assertNull(row(r, "secretStoreName"));
        assertNull(row(r, "enabled"));
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
