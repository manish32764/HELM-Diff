# Helm Compare

Intelligent Helm chart comparison across environments and application versions.
It compares charts by **logical configuration and meaning** rather than line position, correlates PROD
differences across versions, validates newer PROD charts and applies PROD expectations across a portfolio.

```
HELM-Compare/
├── backend/    Java 21 · Spring Boot 3.5 · Maven   (REST API, parsing, analysis engines, export)
└── frontend/   React 19 · TypeScript · Vite 7      (light, Apple-inspired UI)
```

## Compare two folders (home screen)

1. Choose the **left** and **right** parent folders. Each contains one sub-folder per microservice with its Helm chart.
2. Sub-folders with the same name are matched and every file is compared.
3. The **tree view** shows both folders side by side (border in the middle, each side scrolls horizontally on its own):
   `IDENTICAL`, `LOGICALLY SAME` (only ordering / spacing / quoting / comments differ), `DIFFERS`, `FILE EMPTY`,
   `LEFT ONLY` / `RIGHT ONLY`, and `— does not exist —` on the missing side. Folders are **green** when every file is
   identical, otherwise **yellow**.
4. Click a file to open the **file-to-file view** (same window, *Back* returns to the tree). Both files are shown with
   VS Code-style YAML / Helm colours. **Show logical differences** lists what really changed for Kubernetes; selecting a
   difference highlights the exact lines on both sides (green = added, red = removed, amber = changed).
   Keyboard: `n` / `p` next / previous difference, `Backspace` back, `[` collapse / expand the sidebar.
5. Export any comparison to Excel, HTML or CSV (folders, files and every logical difference with line numbers).
6. **ENV** (on a file or folder) opens *Environment variables & secrets*: one row per variable with how it is injected
   and the value the container receives on each side. `envVars` (plain text), `envSecrets`
   (`name` ← `secretName`/`secretKey`) and `externalsecrets.akeyless.secretItems` (key → AKeyless `path`) are linked,
   so `JIRA_API_TOKEN ← envSecrets › Secret attlasian-mcp-server · key JIRA_API_TOKEN › secretItems /Platform/…` is
   one row. Each row is **Missing in right / left**, **Different value**, **Can't verify** or **Same value**; plain ↔
   AKeyless changes, similar names and variables defined twice (e.g. in `envVars` *and* as a secret item) are flagged.
   Because PROD reads AKeyless while NON-PROD is plain text, upload a **values JSON** with the actual value of each
   AKeyless path; the paths still missing can be copied as a JSON template:

   ```json
   { "/Platform/KPS/APM0007705/common/API_KEY": "value", "/Platform/KPS/APM0007705/dev/azure/assist-dev/CLIENT_ID": "…" }
   ```

   Nested folders (`{ "Platform": { "KPS": { … } } }`) and lists (`[{ "path": "…", "value": "…" }]`) also work. The values
   are stored with the comparison (`data/folder-compares/{id}/secret-values.json`), are masked on screen and in exports
   unless *Show secret values* is on, and are removed with *Clear* or when the comparison is deleted.

Hidden folders (such as `.git`), `node_modules`, `target` and files over 10 MB are skipped.

## Run with Docker (recommended)

Requires Docker Desktop (running).

```powershell
docker compose up -d --build
```

| Service | URL | Container |
|---|---|---|
| Frontend (nginx, proxies `/api` to the backend) | http://localhost:5173 | `helm-compare-frontend` |
| Backend API | http://localhost:32764/api/health | `helm-compare-backend` |

Data is kept in the Docker volume `helm-compare-data`, so it survives restarts.

```powershell
docker compose logs -f          # follow logs
docker compose down             # stop (keeps data)
docker compose down -v          # stop and delete data
```

The frontend waits until the backend health check passes. The backend heap is capped at 768 MB
(`JAVA_OPTS` in `docker-compose.yml`).

## Run without Docker

**Backend** (port 32764)

```powershell
cd backend
mvn spring-boot:run
# or: mvn package; java -jar target/helm-compare-1.0.0.jar
```

**Frontend** (port 5173, proxies `/api` to the backend)

```powershell
cd frontend
npm install
npm run dev
```

Open http://localhost:5173 and click **Load sample data** on the home page. It loads
`payments-api` 3.0.4 / 3.1.3 NON-PROD / PROD charts, a generated 40-application portfolio,
and one analysis of every kind.

Data (charts, analyses, audit history) is stored as JSON under `backend/data/`
(change with `--helmcompare.data-dir=...`). No database is required.

## Analysis modes

| Mode | Input | Answers |
|---|---|---|
| 1 · Two-chart diff | A ↔ B | Common, only-in-A, only-in-B, changed, PROD-specific, security and review items. Saved as a reusable **Difference Set**. |
| 2 · Two-diff comparison | (A ↔ B) ↔ (C ↔ D) | Carried forward, changed implementation, missing, new PROD change, no longer applicable — with *same / similar / different / unable to determine* similarity. |
| 3 · Four-chart analysis | A + B + C (+ D) | Evolution matrix, NON-PROD assessment for PROD preparation, and — when D exists — PROD validation with a verdict. |
| 4 · Portfolio | Difference Set → many charts | Compliant / requires changes / requires review per application and per expectation, unexpected and unusual PROD changes, similar changes across charts. |

Every difference can be opened to see both configurations, the setting-level changes, and a link to the
exact line in the Helm source. Reviewers record decisions (Accepted, Change required, Not applicable) which
are kept in the analysis audit trail. Analyses can be **re-run** with newer chart revisions.

## Export

Every analysis can be exported from the **Export** button (or `GET /api/analyses/{id}/export?format=`):

- `xlsx` — Excel workbook, one sheet per table, status colours, filters and frozen headers
- `html` — standalone report; print or save as PDF
- `csv` — main table
- `json` — complete data

## What is recognised

Charts can be uploaded as values files, templates, rendered manifests, a chart folder, `.zip`, `.tgz` or `.tar`.
Simple `{{ .Values.x }}`, `{{ toYaml .Values.x | nindent N }}` and `{{ with }}` scopes are resolved against
`values.yaml`; anything else becomes a placeholder, and unparseable template lines are skipped with a warning.

| Area | Recognised as |
|---|---|
| Environment variables | `env` lists and `env` maps (values style), `envFrom` |
| Secrets | plain text vs K8s `secretKeyRef` vs AKeyless (`akeyless:` values, AKeyless secret stores / ExternalSecrets, `akeyless` annotations) vs Vault / external secrets. Sensitive values are masked and compared by fingerprint only. |
| Health probes | `livenessProbe`, `readinessProbe`, `startupProbe`, or `probes.liveness` … in values |
| TSC | `topologySpreadConstraints` (or keys named `tsc` / `topologySpread*`) |
| Other | resources, volumes & mounts, services / ingress / ports, image, replicas & autoscaling, security context, scheduling, labels & annotations, ConfigMap data (embedded YAML / properties are compared per setting), and every remaining value |

Portfolio uploads are grouped automatically: `<app>/nonprod|prod/…`, `nonprod|prod/<app>/…` or
`<app>-nonprod.yaml` / `<app>-prod.yaml` (also `np, dev, qa, uat, sit, test, staging, preprod` / `prd, production, live`).

## Principle

The tool never assumes that every historical PROD difference is required in the new version.
Historical PROD behaviour + new-version changes + current PROD implementation → **assessment**, with items that
cannot be determined confidently marked for human review. In portfolio analysis each derived expectation can
be deselected or limited to charts where the configuration is present.
