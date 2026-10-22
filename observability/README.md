# Naqqa Observability (`com.naqqa:naqqa-observability`)

Observability backend for Naqqa apps. Serves every panel the naqqa-os observability micro-app reads: metrics/pulse, logs, issues, a product-analytics overview, plus APM/traces, infrastructure, SLOs, alerts, incidents, synthetics, the service catalog and AIOps root-cause. Mirrors the OS dev-data contracts in `naqqa-os-fe/src/app/core/dev-data/devdata-observability.ts` (metrics/logs/issues/analytics) and `devdata-obs-breadth.ts` (APM/infra/SLOs/alerts/incidents/synthetics/catalog/AIOps). The OS client is `features/portal/pages/observability/domain/obs-api.service.ts`, which calls `${apiUrl}/observability/...`.

Spring Boot 3.4.x library jar, own pom, Java 21. Auto-configures on a servlet web app unless `naqqa.observability.enabled=false`. Real telemetry is ingested and stored (see "Real telemetry pipeline"); for projects without data the demo generators are deterministic per `projectId` (ported LCG + FNV-1a seeding from the OS mock), so shapes and value ranges match the dev-data exactly.

## Endpoints

Base path `naqqa.observability.path` (default `/api/observability`). All take `?projectId=<id>`.

| Method | Path | Response shape (top level) | Mirrors |
| --- | --- | --- | --- |
| GET | `/metrics` | `{ projectId, resolutionMs, range{fromMs,toMs}, series: [ {name, unit, labels, samples:[[ts,val]]} ] }` | `devdata-observability.ts` metricsBody |
| GET | `/logs` | `{ projectId, entries: [LogEntry], total, nextCursor }` | logsBody |
| GET | `/issues` | `{ projectId, issues: [Issue], total, nextCursor }` | issuesBody |
| POST | `/issues/{id}/status` | `{ ok: true }` (body `{projectId, status}`) | issues status route |
| POST | `/issues/{id}/assign` | `{ ok: true }` (body `{projectId, assignee}`) | issues assign route |
| GET | `/analytics/overview` | large product-analytics object (see below) | analyticsBody |
| GET | `/apm` | `{ services:[RedService], edges:[{from,to,calls,errorRate,latencyMs}], traces:[Trace] }` | `devdata-obs-breadth.ts` obsApm |
| GET | `/infra` | `{ hosts:[Host], totals:{nodes,cpuAvg,memAvg,alerts} }` | obsInfra |
| GET | `/slos` | `{ slos:[Slo] }` | obsSlos |
| GET | `/alerts` | `{ rules:[AlertRule], alerts:[Alert] }` | obsAlerts |
| GET | `/incidents` | `{ incidents:[Incident], schedule:[OnCallShift] }` | obsIncidents |
| GET | `/synthetics` | `{ checks:[SynthCheck] }` | obsSynthetics |
| GET | `/catalog` | `{ services:[CatalogService] }` | obsCatalog |
| GET | `/aiops` | `{ problems:[Problem] }` | obsAiops |

`/analytics/overview` also accepts `fromMs`, `toMs`, `heatmapMetric` (`sessions`|`scroll`), `funnelSteps` (comma list) and any number of `d_<dimension>=<value>` filter params (e.g. `d_device=mobile`); dimension filters scale the whole report down deterministically, matching the mock.

### Key record shapes (`com.naqqa.observability.dto.ObsDtos`)

- `LogEntry`: `id, tsNs, level, service, message, traceId, spanId, attributes{}, resource{}`
- `Issue`: `id, fingerprint, type, message, culprit, level, status, count, userCount, firstSeenMs, lastSeenMs, release, assignee?, stack:[{function,filename,lineNo,colNo,inApp}]` (`assignee` omitted when null)
- `RedService`: RED metrics + `sparkThroughput/Latency/Errors` + `deps`; `Trace`/`TraceSpan` model the span tree with `tags`
- `Host`: cpu/mem/disk/net gauges + sparklines; `InfraTotals` the rollup
- `Slo`: objective/current/budget/burn-rate + `budgetSpark`/`burnSpark`
- `Alert`/`AlertRule`: firing + resolved alerts with labels; `Incident`: timeline + optional `postmortem`
- `SynthCheck`: uptime/latency per region + sparklines; `CatalogService`: ownership + deps + SLO/incident status
- `Problem` (AIOps): `metrics:[AnomalyMetric]`, `rootCause{headline,confidence,suspectDeploy,chain}`, `whatChanged[]`, `evidence[]`, `correlation[]`

The `/analytics/overview` body is produced as a faithful ordered map (`ObsAnalyticsBuilder`) rather than a record, because it mixes fixed sections (traffic totals, web vitals, funnels, flows, cohort, heatmap, sessions, events, chat, commerce, search, auth, content, realtime) with a dynamic `segments` map keyed by arbitrary dimension; keys and value types match `analyticsBody` one-for-one.

## Real telemetry pipeline (0.0.2)

Everything below is written from scratch on the JDK and Jackson's JSON tree; no OpenTelemetry, Sentry or Prometheus library is used.

When a project has ingested any telemetry, `/metrics`, `/logs`, `/issues`, `/apm`, `/traces` answer from the store; projects with no data fall back to the deterministic demo generators above while `naqqa.observability.demo-data=true` (`GET /source?projectId=` tells the UI which one it sees).

### Ingestion (`naqqa.observability.ingest-path`, default `/api/public/observability`)

| Method | Path | Scope | Accepted bodies |
| --- | --- | --- | --- |
| POST | `/v1/logs` | `logs:write` | OTLP/JSON `resourceLogs` (severityNumber/Text, AnyValue attributes, hex or base64 ids) or `{service, resource, records:[{tsMs\|tsNs, level, message, attributes, traceId, spanId}]}` or a bare array |
| POST | `/v1/metrics` | `metrics:write` | OTLP/JSON `resourceMetrics` (gauge, sum, histogram -> `.count`, `.avg`, `.p95`), `{series:[{name, unit, labels, samples:[[tsMs, value]]}]}` or `{points:[{name, value, tsMs, labels}]}` |
| POST | `/v1/traces` | `traces:write` | OTLP/JSON `resourceSpans` (kind, status code) or `{spans:[{traceId, spanId, parentSpanId, service, name, kind, startMs, endMs, status, attributes}]}` |
| POST | `/v1/errors` | `errors:write` | Sentry-style events (`exception.values[].stacktrace.frames`, `release`, `user`, `tags`, `contexts.trace.trace_id`, `fingerprint`) or `{type, message, stack, release, userId, tags, traceId}`; single, array or `{events:[...]}` |

Authentication: `Authorization: Bearer obs_ing_<env>_<id16>_<secret40>_<crc32>` or `X-Obs-Key`. The token format and CRC-32 match `naqqa-os-fe` `obs-token.ts`; only an HMAC-SHA256(pepper) hash is stored. The project's origin allowlist is enforced when a browser sends `Origin`; ingestion can be switched off per project. Limits: body `max-body-bytes` (1 MB, 413), `max-records-per-batch` (1000), attribute/message/frame caps, timestamps within 30 days back and 5 minutes ahead, `ingest-requests-per-minute` per token (1200, 429). Response `202 {accepted, rejected}`.

Errors are grouped into issues by fingerprint: explicit `fingerprint` (supports `{{ default }}`), otherwise the type plus the top five in-app frames (function and file, not line numbers), otherwise the type plus a normalised message (numbers, hex ids and UUIDs masked). Lifecycle: `new` -> `ongoing`; `resolved` -> `regressed` on a new event; users, releases, tags (bounded) and up to 20 trace ids are tracked per issue.

### Query additions

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/logs` | `levels`, `services`, `traceId`, `search`, `fromMs`, `toMs`, `limit` (max 500), `cursor` (search-after) |
| GET | `/issues` | `statuses`, `levels`, `search`, `limit`, `cursor` |
| GET | `/metrics` | `names`, `fromMs`, `toMs`, `stepMs` (bucket average; auto step, max 1000 points per series) |
| GET | `/traces`, `/traces/{traceId}` | assembled span trees with depth, first error span, duration |
| GET | `/issues/{id}/root-cause` | `{summary, suspects:[{kind, key, value, share, lift, evidence}], firstRelease, traces, logs, sampleSize}` - dominant tags against the rest of the project (lift), release regression, first failing and slowest span of related traces, correlated error logs |
| GET/PUT | `/config` | project settings `{projectId, env, ingest, management, originAllowlist, retention{logsDays, metricsDays, tracesDays, issuesDays}, ingestEnabled}` (the frontend `ObsConfig` shape); stored tokens are returned masked |
| POST | `/config/tokens/{ing\|sk}/rotate` | mints a new token, returns it once, the old one stops working immediately |

Reads are authorised by the host `ObservabilityAccess` port or by a management token (`obs_sk_...`, scopes `logs:read`, `metrics:read`, `issues:read`, `issues:write`, `analytics:read`, `settings:manage`).

### Ports

| Port | Default |
| --- | --- |
| `TelemetryStore` | `MongoTelemetryStore` when a `MongoOperations` bean exists (`<prefix>logs`, `metric_points`, `spans`, `error_events`, `issues`; per-project retention through `expireAt` TTL indexes), else a bounded `InMemoryTelemetryStore` |
| `ObsConfigStore` | `MongoObsConfigStore` (`<prefix>configs`) or in-memory |
| `ObservabilityAccess` | allow-all (logged as a warning); hosts must provide their policy |

A `RetentionJob` also sweeps every `retention-interval-minutes` with each project's retention.

### Properties

`enabled`, `path`, `ingest-path`, `demo-data` (true), `token-pepper` (base64, at least 16 bytes; required for stable tokens), `token-env` (`live`), `max-body-bytes`, `ingest-requests-per-minute`, `max-records-per-batch`, `max-logs`, `max-points`, `max-spans`, `max-events-per-issue`, `retention-interval-minutes`, `collection-prefix` (`obs_`).

### Tests

`OtlpJsonTest`, `ErrorEventsAndGroupingTest`, `ObsTokensTest` (CRC-32 check vector), `ObsConfigServiceTest`, `TelemetryPipelineTest` (paging, facets, buckets, traces, APM, root cause, retention, capacity), `ObservabilityWebTest` (status codes, authorisation, config, end-to-end issue flow), `ObservabilityAutoConfigurationTest`.

## Build

```
cd naqqa-backend-services/observability
mvn -q -o install
```
