# Naqqa Observability (`com.naqqa:naqqa-observability`)

Observability backend for Naqqa apps. Serves every panel the naqqa-os observability micro-app reads: metrics/pulse, logs, issues, a product-analytics overview, plus APM/traces, infrastructure, SLOs, alerts, incidents, synthetics, the service catalog and AIOps root-cause. Mirrors the OS dev-data contracts in `naqqa-os-fe/src/app/core/dev-data/devdata-observability.ts` (metrics/logs/issues/analytics) and `devdata-obs-breadth.ts` (APM/infra/SLOs/alerts/incidents/synthetics/catalog/AIOps). The OS client is `features/portal/pages/observability/domain/obs-api.service.ts`, which calls `${apiUrl}/observability/...`.

Spring Boot 3.4.x library jar, own pom, Java 21. Auto-configures on a servlet web app unless `naqqa.observability.enabled=false`. All generators are deterministic per `projectId` (ported LCG + FNV-1a seeding from the OS mock), so shapes and value ranges match the dev-data exactly.

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

## Host integration

The default beans generate OS-parity data for any `projectId`, so the module is drop-in. A host that wants real telemetry can override `ObservabilityService` and/or `ObsBreadthService` beans (they are `@ConditionalOnMissingBean`) to read from its real metrics/log/trace/incident stores while keeping the same DTOs and routes.

## Properties (`naqqa.observability.*`)

- `enabled` (default `true`)
- `path` (default `/api/observability`)

## Build

```
cd naqqa-backend-services/observability
mvn -q -o package
```
