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
   Give each side a **label** (e.g. `NON-PROD`, `PROD`); without one the folder name is used. When an environment reads
   secrets from AKeyless, also choose its **AKeyless values JSON** — one file per environment with the actual value of
   each path:

   ```json
   { "/Platform/KPS/APM0007705/common/API_KEY": "value", "/Platform/KPS/APM0007705/dev/azure/assist-dev/CLIENT_ID": "…" }
   ```

   Nested folders (`{ "Platform": { "KPS": { … } } }`) and lists (`[{ "path": "…", "value": "…" }]`) also work. The values
   are stored with the comparison (`data/folder-compares/{id}/secret-values-left|right.json`) and deleted with it.
2. The comparison lists the **first-level (microservice) folders** of both sides with their status. Double-click a folder
   (or **OPEN ↗**) to open its complete Helm chart tree in a **new tab**; **ENV ↗** opens its environment variables.
3. In the chart tab, click a file to open the **file-to-file view**. Each pane is titled `LABEL\relative\path`.
   **Show logical differences** lists what really changed for Kubernetes; the panel can be collapsed / expanded,
   maximised, or opened in a **separate tab** (selecting a difference there highlights it in the file tab).
   **Code only** shows just the two files, with previous / next difference buttons.
   Keyboard: `n` / `p` next / previous difference, `Esc` leave maximised panel or code only, `Backspace` back.
4. **Env variables & secrets** (new tab): one row per variable. `envVars` (plain text), `envSecrets`
   (`name` ← `secretName`/`secretKey`) and `externalsecrets.akeyless.secretItems` (key → AKeyless `path`) are linked, and
   AKeyless paths are resolved with the JSON of that side. Tick **Injected via** and/or **Value** to choose the columns.
   Each row is **Missing in …**, **Different value**, **Can't verify** or **Same value**; plain ↔ AKeyless changes,
   similar names and variables defined twice are noted. Secret values stay masked (also in exports) unless
   *Reveal secret values* is ticked; unknown paths can be copied as a JSON template.
5. Export any comparison to Excel, HTML or CSV.

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
