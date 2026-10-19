package com.naqqa.observability.service;

import com.naqqa.observability.dto.ObsDtos.ActionItem;
import com.naqqa.observability.dto.ObsDtos.Alert;
import com.naqqa.observability.dto.ObsDtos.AlertRule;
import com.naqqa.observability.dto.ObsDtos.AlertsResult;
import com.naqqa.observability.dto.ObsDtos.AnomalyMetric;
import com.naqqa.observability.dto.ObsDtos.ApmEdge;
import com.naqqa.observability.dto.ObsDtos.ApmResult;
import com.naqqa.observability.dto.ObsDtos.AiopsResult;
import com.naqqa.observability.dto.ObsDtos.CatalogResult;
import com.naqqa.observability.dto.ObsDtos.CatalogService;
import com.naqqa.observability.dto.ObsDtos.Correlation;
import com.naqqa.observability.dto.ObsDtos.Host;
import com.naqqa.observability.dto.ObsDtos.Incident;
import com.naqqa.observability.dto.ObsDtos.IncidentEvent;
import com.naqqa.observability.dto.ObsDtos.IncidentsResult;
import com.naqqa.observability.dto.ObsDtos.InfraResult;
import com.naqqa.observability.dto.ObsDtos.InfraTotals;
import com.naqqa.observability.dto.ObsDtos.OnCallShift;
import com.naqqa.observability.dto.ObsDtos.Postmortem;
import com.naqqa.observability.dto.ObsDtos.Problem;
import com.naqqa.observability.dto.ObsDtos.ProblemEvidence;
import com.naqqa.observability.dto.ObsDtos.RedService;
import com.naqqa.observability.dto.ObsDtos.RootCause;
import com.naqqa.observability.dto.ObsDtos.RootCauseStep;
import com.naqqa.observability.dto.ObsDtos.Slo;
import com.naqqa.observability.dto.ObsDtos.SlosResult;
import com.naqqa.observability.dto.ObsDtos.SuspectDeploy;
import com.naqqa.observability.dto.ObsDtos.SynthCheck;
import com.naqqa.observability.dto.ObsDtos.SynthRegion;
import com.naqqa.observability.dto.ObsDtos.SyntheticsResult;
import com.naqqa.observability.dto.ObsDtos.Trace;
import com.naqqa.observability.dto.ObsDtos.TraceSpan;
import com.naqqa.observability.dto.ObsDtos.WhatChangedRow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ObsBreadthService {

    private record ServiceDef(String id, String name, String team, String tier, String lang,
                              List<String> deps, boolean datastore) {
    }

    private static final List<ServiceDef> SERVICE_DEFS = List.of(
            new ServiceDef("web", "web", "Frontend", "edge", "TypeScript", List.of("api-gateway"), false),
            new ServiceDef("api-gateway", "api-gateway", "Platform", "gateway", "Go", List.of("auth", "billing", "search", "renderer"), false),
            new ServiceDef("auth", "auth", "Identity", "core", "Go", List.of("postgres", "redis"), false),
            new ServiceDef("billing", "billing", "Payments", "core", "Java", List.of("postgres", "stripe-egress"), false),
            new ServiceDef("worker", "worker", "Platform", "async", "Python", List.of("postgres", "redis", "search"), false),
            new ServiceDef("renderer", "renderer", "Frontend", "core", "Node", List.of("search", "redis"), false),
            new ServiceDef("search", "search", "Data", "core", "Rust", List.of("elasticsearch"), false),
            new ServiceDef("postgres", "postgres", "Data", "datastore", "SQL", List.of(), true),
            new ServiceDef("redis", "redis", "Data", "datastore", "-", List.of(), true),
            new ServiceDef("elasticsearch", "elasticsearch", "Data", "datastore", "Java", List.of(), true),
            new ServiceDef("stripe-egress", "stripe-egress", "Payments", "external", "-", List.of(), false)
    );

    private static final String INCIDENT_SERVICE = "billing";
    private static final String INCIDENT_DEPLOY_VERSION = "2.4.0";
    private static final String INCIDENT_COMMIT = "a9f3c1e";
    private static final String INCIDENT_ENDPOINT = "POST /v1/charge";
    private static final int INCIDENT_WINDOW_MIN = 42;

    private static double round(double value, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(value * f) / f;
    }

    private static double round(double value) {
        return round(value, 2);
    }

    private static <T> T pick(Rng rand, List<T> list) {
        return list.get((int) Math.floor(rand.next() * list.size()));
    }

    private static List<Double> waveSeries(Rng rand, int count, double base, double amp, double jitter, double trend) {
        List<Double> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double wave = Math.sin((i / (double) count) * Math.PI * 4) * amp;
            double v = Math.max(0, base + wave + (rand.next() - 0.5) * jitter + i * trend);
            out.add(round(v));
        }
        return out;
    }

    private static List<Double> waveSeries(Rng rand, int count, double base, double amp, double jitter) {
        return waveSeries(rand, count, base, amp, jitter, 0);
    }

    private static List<Double> spike(List<Double> series, int from, int to, double factor) {
        List<Double> out = new ArrayList<>(series.size());
        for (int i = 0; i < series.size(); i++) {
            double v = series.get(i);
            out.add(i >= from && i <= to ? round(v * factor) : v);
        }
        return out;
    }

    private static String health(double errorRate, double p95, double p95Budget) {
        if (errorRate > 8 || p95 > p95Budget * 2.2) {
            return "down";
        }
        if (errorRate > 2.5 || p95 > p95Budget * 1.4) {
            return "degraded";
        }
        return "healthy";
    }

    public ApmResult apm(String projectId) {
        long now = System.currentTimeMillis();
        List<RedService> services = new ArrayList<>();
        for (ServiceDef def : SERVICE_DEFS) {
            if (def.datastore() || def.tier().equals("external")) {
                continue;
            }
            Rng r = new Rng(Rng.seedFor(projectId + def.id()) ^ 0x1234L);
            boolean degraded = def.id().equals(INCIDENT_SERVICE);
            boolean cascaded = def.id().equals("api-gateway") || def.id().equals("web");
            double baseLat = 60 + (Rng.seedFor(def.id()) % 140);
            double errFactor = degraded ? 9.5 : cascaded ? 3.2 : 0.4 + r.next() * 1.4;
            double latFactor = degraded ? 3.1 : cascaded ? 1.6 : 1;
            int rpm = (int) Math.round((200 + r.next() * 1800) * (def.tier().equals("gateway") ? 2.2 : 1));
            double errorRate = round(errFactor * (0.6 + r.next() * 0.8), 2);
            int p50 = (int) Math.round(baseLat * latFactor * 0.55);
            int p95 = (int) Math.round(baseLat * latFactor * (1.8 + r.next() * 0.5));
            int p99 = (int) Math.round(p95 * (1.4 + r.next() * 0.4));
            double apdex = round(Math.max(0.4, 1 - errorRate / 100 - (p95 > 400 ? 0.2 : 0.04)), 3);
            List<Double> sparkLatency = spike(waveSeries(r, 48, baseLat, baseLat * 0.25, baseLat * 0.3, 0),
                    degraded || cascaded ? 36 : 60, 47, degraded ? 3.2 : cascaded ? 1.7 : 1);
            List<Double> sparkErrors = spike(waveSeries(r, 48, degraded ? 1 : 0.4, 0.3, 0.4, 0),
                    degraded || cascaded ? 36 : 60, 47, degraded ? 9 : cascaded ? 4 : 1);
            List<Double> sparkThroughput = waveSeries(r, 48, rpm / 60.0, rpm / 180.0, rpm / 160.0, 0);
            services.add(new RedService(def.id(), def.name(), def.team(), def.tier(), def.lang(),
                    health(errorRate, p95, baseLat * 2), rpm, errorRate, p50, p95, p99, apdex,
                    sparkThroughput, sparkLatency, sparkErrors, def.deps()));
        }

        List<ApmEdge> edges = new ArrayList<>();
        for (ServiceDef def : SERVICE_DEFS) {
            for (String to : def.deps()) {
                Rng r = new Rng(Rng.seedFor(def.id() + to));
                boolean degradedEdge = def.id().equals("api-gateway") && to.equals(INCIDENT_SERVICE);
                edges.add(new ApmEdge(def.id(), to, (int) Math.round(100 + r.next() * 2000),
                        round(degradedEdge ? 9 + r.next() * 2 : r.next() * 1.6, 2),
                        (int) Math.round((degradedEdge ? 420 : 40) + r.next() * 120)));
            }
        }
        return new ApmResult(services, edges, traces(projectId, now));
    }

    private List<Trace> traces(String projectId, long now) {
        List<String> ops = List.of("GET /", "GET /promotions", "POST /v1/charge", "GET /api/offers", "POST /api/search", "GET /company/:id");
        int count = 14;
        List<Trace> traces = new ArrayList<>();
        for (int idx = 0; idx < count; idx++) {
            Rng r = new Rng(Rng.seedFor(projectId + "trace" + idx) ^ 0x55L);
            boolean slow = idx < 4;
            String rootOp = slow ? INCIDENT_ENDPOINT : pick(r, ops);
            long startMs = now - (long) Math.floor(r.next() * INCIDENT_WINDOW_MIN * 60 * 1000);
            List<TraceSpan> spans = new ArrayList<>();
            long rootDur = slow ? 1800 + (long) Math.floor(r.next() * 1400) : 120 + (long) Math.floor(r.next() * 400);
            String rootId = r.hex(16);
            String[] opParts = rootOp.split(" ");
            Map<String, String> rootTags = new LinkedHashMap<>();
            rootTags.put("http.method", opParts[0]);
            rootTags.put("http.route", opParts.length > 1 ? opParts[1] : "/");
            spans.add(new TraceSpan(rootId, null, "web", rootOp, startMs, rootDur, slow ? "error" : "ok", 0, "server", rootTags));
            String gwId = r.hex(16);
            long gwStart = startMs + 8;
            long gwDur = Math.round(rootDur * 0.94);
            Map<String, String> gwTags = new LinkedHashMap<>();
            gwTags.put("peer.service", slow ? INCIDENT_SERVICE : "search");
            spans.add(new TraceSpan(gwId, rootId, "api-gateway", "proxy " + rootOp, gwStart, gwDur, slow ? "error" : "ok", 1, "client", gwTags));
            String downstream = slow ? INCIDENT_SERVICE : pick(r, List.of("auth", "search", "renderer"));
            String dsId = r.hex(16);
            long dsStart = gwStart + 6;
            long dsDur = Math.round(gwDur * (slow ? 0.9 : 0.6));
            Map<String, String> dsTags = new LinkedHashMap<>();
            if (slow) {
                dsTags.put("service.version", INCIDENT_DEPLOY_VERSION);
                dsTags.put("error.type", "PoolTimeoutException");
            }
            spans.add(new TraceSpan(dsId, gwId, downstream,
                    downstream.equals(INCIDENT_SERVICE) ? "charge.capture" : downstream + ".handle",
                    dsStart, dsDur, slow ? "error" : "ok", 2, "server", dsTags));
            String dbId = r.hex(16);
            Map<String, String> dbTags = new LinkedHashMap<>();
            if (slow) {
                dbTags.put("db.system", "postgresql");
                dbTags.put("db.pool.wait_ms", String.valueOf(Math.round(dsDur * 0.8)));
            } else {
                dbTags.put("db.system", "redis");
            }
            spans.add(new TraceSpan(dbId, dsId, slow ? "postgres" : "redis",
                    slow ? "SELECT invoices FOR UPDATE" : "GET cache", dsStart + 4,
                    slow ? Math.round(dsDur * 0.86) : 6 + (long) Math.floor(r.next() * 20),
                    slow ? "error" : "ok", 3, "client", dbTags));
            traces.add(new Trace(r.hex(32), "web", rootOp, startMs, rootDur, spans.size(),
                    slow ? "error" : "ok", slow ? dsId : null, spans));
        }
        traces.sort((a, b) -> Long.compare(b.durationMs(), a.durationMs()));
        return traces;
    }

    public InfraResult infra(String projectId) {
        Rng rand = new Rng(Rng.seedFor(projectId) ^ 0x1f7aL);
        List<String> regions = List.of("eu-central-1", "eu-west-1", "us-east-1");
        List<String> providers = List.of("AWS", "Hetzner", "GCP");
        List<String> kinds = List.of("pod", "pod", "container", "host");
        List<Host> hosts = new ArrayList<>();
        for (ServiceDef def : SERVICE_DEFS) {
            if (def.tier().equals("external")) {
                continue;
            }
            int replicas = def.datastore() ? 2 : 2 + (int) Math.floor(rand.next() * 3);
            for (int r = 0; r < replicas; r++) {
                Rng rr = new Rng(Rng.seedFor(projectId + def.id() + r) ^ 0xabcL);
                boolean hot = def.id().equals(INCIDENT_SERVICE);
                double cpuBase = hot ? 82 : 28 + rr.next() * 38;
                double memBase = hot ? 88 : 40 + rr.next() * 30;
                double cpuPct = round(Math.min(99, cpuBase + rr.next() * 8));
                double memPct = round(Math.min(99, memBase + rr.next() * 6));
                double diskPct = round(30 + rr.next() * 50);
                String status = cpuPct > 90 || memPct > 92 ? "degraded" : diskPct > 88 ? "degraded" : "healthy";
                hosts.add(new Host("node-" + rr.hex(6), def.id() + "-" + (r + 1),
                        def.datastore() ? "host" : pick(rr, kinds), def.id(), pick(rr, regions), pick(rr, providers),
                        hot && r == 0 ? "degraded" : status, cpuPct, memPct, diskPct, round(2 + rr.next() * 90),
                        (int) Math.round(10 + rr.next() * 2000), def.datastore() ? 1 : 1 + (int) Math.floor(rr.next() * 6),
                        spike(waveSeries(rr, 40, cpuBase, 10, 8), hot ? 30 : 60, 39, hot ? 1.15 : 1),
                        spike(waveSeries(rr, 40, memBase, 6, 5), hot ? 30 : 60, 39, hot ? 1.08 : 1),
                        waveSeries(rr, 40, 20, 14, 10), pick(rr, List.of(2, 4, 4, 8, 16)), pick(rr, List.of(4, 8, 8, 16, 32))));
            }
        }
        double cpuAvg = round(hosts.stream().mapToDouble(Host::cpuPct).sum() / hosts.size());
        double memAvg = round(hosts.stream().mapToDouble(Host::memPct).sum() / hosts.size());
        int alerts = (int) hosts.stream().filter(h -> !h.status().equals("healthy")).count();
        return new InfraResult(hosts, new InfraTotals(hosts.size(), cpuAvg, memAvg, alerts));
    }

    private record SloDef(String id, String name, String service, double objective, int window, String indicator, boolean target) {
    }

    public SlosResult slos(String projectId) {
        List<SloDef> defs = List.of(
                new SloDef("slo-web-avail", "Web availability", "web", 99.9, 30, "success rate", true),
                new SloDef("slo-api-latency", "API latency < 300ms", "api-gateway", 99.0, 30, "p95 under 300ms", true),
                new SloDef("slo-billing-success", "Billing success rate", "billing", 99.95, 30, "charge success", false),
                new SloDef("slo-auth-latency", "Auth latency < 150ms", "auth", 99.5, 7, "p95 under 150ms", true),
                new SloDef("slo-search-avail", "Search availability", "search", 99.9, 30, "success rate", true),
                new SloDef("slo-checkout-journey", "Checkout journey", "web", 99.0, 30, "journey success", true)
        );
        List<Slo> out = new ArrayList<>();
        for (SloDef d : defs) {
            Rng r = new Rng(Rng.seedFor(projectId + d.id()) ^ 0x5101L);
            boolean breached = !d.target();
            double errorBudgetPct = round(100 - d.objective(), 3);
            double consumed = breached ? 1.35 + r.next() * 0.3 : 0.2 + r.next() * 0.6;
            double budgetRemainingPct = round(Math.max(-40, (1 - consumed) * 100));
            double currentPct = round(Math.min(100, d.objective() + (budgetRemainingPct / 100) * errorBudgetPct), 4);
            double burnRate1h = round(breached ? 8 + r.next() * 6 : 0.3 + r.next() * 1.2, 2);
            double burnRate6h = round(breached ? 4 + r.next() * 3 : 0.4 + r.next() * 0.9, 2);
            String status = budgetRemainingPct < 0 ? "breached" : (burnRate1h > 2 || budgetRemainingPct < 20) ? "at_risk" : "ok";
            List<Double> budgetSpark = new ArrayList<>();
            double rem = 100;
            for (int i = 0; i < 40; i++) {
                rem = Math.max(breached ? -35 : 10, rem - (breached && i > 28 ? 5 + r.next() * 3 : r.next() * 1.2));
                budgetSpark.add(round(rem));
            }
            List<Double> burnSpark = spike(waveSeries(r, 40, breached ? 1.2 : 0.6, 0.3, 0.3), breached ? 28 : 60, 39, breached ? 7 : 1);
            out.add(new Slo(d.id(), d.name(), d.service(), d.objective(), d.window(), currentPct,
                    budgetRemainingPct, burnRate1h, burnRate6h, status, d.indicator(), budgetSpark, burnSpark));
        }
        return new SlosResult(out);
    }

    public AlertsResult alerts(String projectId) {
        long now = System.currentTimeMillis();
        Rng r = new Rng(Rng.seedFor(projectId) ^ 0xa1e2L);
        List<AlertRule> rules = List.of(
                new AlertRule("r-err", "High error rate", "billing", "critical", "rate(http_errors[5m]) > 5%", "2m", true, 1, "#payments-oncall"),
                new AlertRule("r-lat", "Latency p95 budget", "api-gateway", "high", "p95(http_latency) > 300ms", "5m", true, 1, "#platform"),
                new AlertRule("r-cpu", "Node CPU saturation", "billing", "high", "avg(cpu) > 85%", "10m", true, 1, "#platform"),
                new AlertRule("r-pool", "DB pool exhaustion", "postgres", "critical", "pg_pool_wait > 500ms", "1m", true, 1, "#data"),
                new AlertRule("r-cert", "TLS cert expiry", "web", "medium", "cert_expiry < 14d", "1h", true, 0, "#platform"),
                new AlertRule("r-disk", "Disk usage", "elasticsearch", "medium", "disk_used > 85%", "15m", true, 0, "#data"),
                new AlertRule("r-5xx", "Edge 5xx spike", "web", "high", "rate(5xx[1m]) > 10", "2m", true, 1, "#platform"),
                new AlertRule("r-queue", "Worker queue backlog", "worker", "low", "queue_depth > 5000", "10m", false, 0, "#platform"),
                new AlertRule("r-synthetic", "Synthetic checkout failing", "web", "high", "synthetic_success < 90%", "5m", true, 0, "#platform")
        );
        List<Alert> alerts = new ArrayList<>();
        Object[][] firing = {
                {rules.get(0), "billing error rate 9.4% over 5m", "http.error.rate", "> 5%", "9.4%", 40},
                {rules.get(1), "api-gateway p95 612ms", "http.latency.p95", "> 300ms", "612ms", 38},
                {rules.get(2), "billing-1 CPU 92%", "system.cpu.usage", "> 85%", "92%", 39},
                {rules.get(3), "postgres pool wait 780ms", "pg.pool.wait", "> 500ms", "780ms", 41},
                {rules.get(6), "web 5xx 14 req/s", "http.5xx.rate", "> 10", "14/s", 37}
        };
        for (Object[] d : firing) {
            AlertRule rule = (AlertRule) d[0];
            int agoMin = (int) d[5];
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("env", "production");
            labels.put("team", rule.channel().replace("#", ""));
            labels.put("version", rule.service().equals(INCIDENT_SERVICE) ? INCIDENT_DEPLOY_VERSION : "1.9.2");
            alerts.add(new Alert("alert-" + r.hex(8), rule.name(), rule.id(), rule.service(), rule.severity(),
                    "firing", (String) d[1], (String) d[2], (String) d[3], (String) d[4],
                    now - agoMin * 60L * 1000, null, false, labels));
        }
        Object[][] resolved = {
                {rules.get(4), "cert renewed for naqqa.dev", "cert.expiry", "< 14d", "12d", 240, 30},
                {rules.get(5), "elasticsearch disk 83%", "disk.used", "> 85%", "87%", 600, 120},
                {rules.get(1), "latency recovered after cache warmup", "http.latency.p95", "> 300ms", "318ms", 1440, 18},
                {rules.get(7), "worker backlog drained", "queue.depth", "> 5000", "6120", 2880, 45}
        };
        for (Object[] d : resolved) {
            AlertRule rule = (AlertRule) d[0];
            int agoMin = (int) d[5];
            int durMin = (int) d[6];
            long started = now - agoMin * 60L * 1000;
            Map<String, String> labels = new LinkedHashMap<>();
            labels.put("env", "production");
            labels.put("team", rule.channel().replace("#", ""));
            alerts.add(new Alert("alert-" + r.hex(8), rule.name(), rule.id(), rule.service(), rule.severity(),
                    "resolved", (String) d[1], (String) d[2], (String) d[3], (String) d[4],
                    started, started + durMin * 60L * 1000, false, labels));
        }
        alerts.sort((a, b) -> Long.compare(b.startedMs(), a.startedMs()));
        return new AlertsResult(rules, alerts);
    }

    public IncidentsResult incidents(String projectId) {
        long now = System.currentTimeMillis();
        Incident active = new Incident("INC-2041", "Elevated charge failures on billing", "critical", "active",
                "billing", "alice@naqqa.dev", now - INCIDENT_WINDOW_MIN * 60L * 1000, null, 3, null,
                "Checkout failing for ~12% of users; payment capture timing out.",
                List.of("billing", "api-gateway", "web", "postgres"),
                List.of(
                        new IncidentEvent(now - 42 * 60000L, "deploy", "ci-bot", "Deploy billing " + INCIDENT_DEPLOY_VERSION + " (" + INCIDENT_COMMIT + ") to production"),
                        new IncidentEvent(now - 40 * 60000L, "detected", "alertmanager", "High error rate on billing (9.4%)"),
                        new IncidentEvent(now - 39 * 60000L, "ack", "alice@naqqa.dev", "Acknowledged, investigating charge path"),
                        new IncidentEvent(now - 34 * 60000L, "escalate", "alice@naqqa.dev", "Paged secondary (Payments) and DBA on-call"),
                        new IncidentEvent(now - 28 * 60000L, "update", "bob@naqqa.dev", "Postgres connection pool saturated, waits > 700ms"),
                        new IncidentEvent(now - 12 * 60000L, "update", "alice@naqqa.dev", "Suspect " + INCIDENT_DEPLOY_VERSION + ": new synchronous FOR UPDATE lock on invoices")),
                null);

        Incident inc2038 = new Incident("INC-2038", "Search latency regression after index rebuild", "high", "resolved",
                "search", "carol@naqqa.dev", now - 3 * 86400000L, now - 3 * 86400000L + 74 * 60000L, 5, 74,
                "Search p95 up 3x for 1h10m; no data loss.", List.of("search", "elasticsearch", "web"),
                List.of(
                        new IncidentEvent(now - 3 * 86400000L, "detected", "alertmanager", "Search p95 > 800ms"),
                        new IncidentEvent(now - 3 * 86400000L + 5 * 60000L, "ack", "carol@naqqa.dev", "Ack, checking ES cluster"),
                        new IncidentEvent(now - 3 * 86400000L + 40 * 60000L, "mitigated", "carol@naqqa.dev", "Throttled background merge"),
                        new IncidentEvent(now - 3 * 86400000L + 74 * 60000L, "resolved", "carol@naqqa.dev", "Latency recovered")),
                new Postmortem("Forced merge on primary shards competed with query threadpool.",
                        "Scheduled index rebuild ran during peak traffic.",
                        "Throttled merges, moved rebuild to off-peak window.",
                        List.of(new ActionItem("Add merge-throttle guardrail", "Data", true),
                                new ActionItem("Move rebuild cron to 03:00 UTC", "Data", true),
                                new ActionItem("Alert on threadpool rejection", "Data", false))));

        Incident inc2036 = new Incident("INC-2036", "Auth token refresh storm", "medium", "resolved",
                "auth", "dan@naqqa.dev", now - 9 * 86400000L, now - 9 * 86400000L + 32 * 60000L, 2, 32,
                "Redis CPU spike; intermittent 401s for 30m.", List.of("auth", "redis"),
                List.of(
                        new IncidentEvent(now - 9 * 86400000L, "detected", "alertmanager", "Redis CPU 95%"),
                        new IncidentEvent(now - 9 * 86400000L + 32 * 60000L, "resolved", "dan@naqqa.dev", "Added jitter to refresh")),
                new Postmortem("Clients refreshed tokens on a synchronized schedule.", "Short token TTL change.",
                        "Added randomized jitter and increased TTL.",
                        List.of(new ActionItem("Add jitter to SDK", "Identity", true),
                                new ActionItem("Load test refresh path", "Identity", false))));

        List<OnCallShift> schedule = List.of(
                new OnCallShift("Platform", "alice@naqqa.dev", "bob@naqqa.dev", List.of("Team lead", "Eng manager", "VP Eng"), now - 6 * 3600000L, now + 18 * 3600000L),
                new OnCallShift("Payments", "dan@naqqa.dev", "erin@naqqa.dev", List.of("Payments lead", "Eng manager"), now - 6 * 3600000L, now + 18 * 3600000L),
                new OnCallShift("Data", "carol@naqqa.dev", "frank@naqqa.dev", List.of("Data lead", "Eng manager"), now - 6 * 3600000L, now + 18 * 3600000L)
        );
        return new IncidentsResult(List.of(active, inc2038, inc2036), schedule);
    }

    private record SynthDef(String id, String name, String type, String url, int interval, boolean good) {
    }

    public SyntheticsResult synthetics(String projectId) {
        long now = System.currentTimeMillis();
        List<String> regionNames = List.of("eu-central", "eu-west", "us-east", "us-west", "ap-south");
        List<SynthDef> defs = List.of(
                new SynthDef("syn-home", "Home page load", "browser", "https://naqqa.dev/", 60, true),
                new SynthDef("syn-checkout", "Checkout journey", "browser", "https://naqqa.dev/checkout", 300, false),
                new SynthDef("syn-api-health", "API health", "http", "https://api.naqqa.dev/health", 30, true),
                new SynthDef("syn-login", "Login API", "api", "POST /v1/login", 60, true),
                new SynthDef("syn-search", "Search API", "api", "POST /v1/search", 60, true),
                new SynthDef("syn-tcp", "Postgres TCP", "tcp", "db.naqqa.dev:5432", 60, true)
        );
        List<SynthCheck> out = new ArrayList<>();
        for (SynthDef d : defs) {
            Rng r = new Rng(Rng.seedFor(projectId + d.id()) ^ 0x595eL);
            double uptimePct = round(d.good() ? 99.6 + r.next() * 0.39 : 94.2 + r.next() * 2, 3);
            int p50 = (int) Math.round((d.type().equals("browser") ? 900 : 60) * (1 + r.next()));
            int p95 = (int) Math.round(p50 * (1.8 + r.next()));
            String status = uptimePct < 97 ? "degraded" : uptimePct < 90 ? "down" : "healthy";
            List<SynthRegion> regions = new ArrayList<>();
            for (String region : regionNames) {
                Rng rr = new Rng(Rng.seedFor(projectId + d.id() + region));
                boolean bad = !d.good() && region.equals("us-east");
                double up = round(bad ? 88 + rr.next() * 4 : 99.3 + rr.next() * 0.6, 3);
                regions.add(new SynthRegion(region, bad ? "degraded" : "healthy",
                        (int) Math.round(p50 * (1 + rr.next() * 0.6) + (bad ? 400 : 0)), up));
            }
            out.add(new SynthCheck(d.id(), d.name(), d.type(), d.url(), d.interval(), uptimePct, status, p50, p95,
                    now - (long) Math.floor(r.next() * d.interval() * 1000),
                    d.good() ? null : "Timeout waiting for selector [data-test=pay] after 10000ms", regions,
                    spike(waveSeries(r, 40, p50, p50 * 0.2, p50 * 0.2), d.good() ? 60 : 30, 39, d.good() ? 1 : 1.4),
                    waveSeries(r, 40, uptimePct, 0.2, 0.15)));
        }
        return new SyntheticsResult(out);
    }

    public CatalogResult catalog(String projectId) {
        Map<String, String> teams = Map.of("Frontend", "#frontend", "Platform", "#platform", "Identity", "#identity", "Payments", "#payments", "Data", "#data");
        Map<String, String> oncall = Map.of("Frontend", "alice@naqqa.dev", "Platform", "bob@naqqa.dev", "Identity", "dan@naqqa.dev", "Payments", "erin@naqqa.dev", "Data", "carol@naqqa.dev");
        List<CatalogService> out = new ArrayList<>();
        for (ServiceDef def : SERVICE_DEFS) {
            Rng rr = new Rng(Rng.seedFor(projectId + def.id()) ^ 0xf00dL);
            List<String> dependedBy = SERVICE_DEFS.stream().filter(s -> s.deps().contains(def.id())).map(ServiceDef::id).toList();
            boolean incident = def.id().equals(INCIDENT_SERVICE);
            out.add(new CatalogService(def.id(), def.name(), def.team(), def.tier(), def.lang(),
                    def.tier().equals("external") ? "production" : (rr.next() > 0.9 ? "experimental" : "production"),
                    incident ? "degraded" : rr.next() > 0.85 ? "degraded" : "healthy",
                    incident ? INCIDENT_DEPLOY_VERSION : "1." + (int) Math.floor(rr.next() * 9) + "." + (int) Math.floor(rr.next() * 9),
                    "naqqa/" + def.id(), teams.getOrDefault(def.team(), "#eng"), oncall.getOrDefault(def.team(), "oncall@naqqa.dev"),
                    def.deps(), dependedBy, incident ? 1 : 0, incident ? "breached" : rr.next() > 0.8 ? "at_risk" : "ok",
                    "https://docs.naqqa.dev/services/" + def.id()));
        }
        return new CatalogResult(out);
    }

    private AnomalyMetric anomalyMetric(String projectId, String id, String label, String service, String unit,
                                        double base, double amp, double jitter, double spikeFactor) {
        Rng r = new Rng(Rng.seedFor(projectId + id) ^ 0xa0a0L);
        List<Double> series = spike(waveSeries(r, 60, base, amp, jitter), 46, 59, spikeFactor);
        List<Double> normal = series.subList(0, 44);
        double baseline = round(normal.stream().mapToDouble(Double::doubleValue).sum() / normal.size(), 3);
        double variance = normal.stream().mapToDouble(v -> (v - baseline) * (v - baseline)).sum() / normal.size();
        double stddev = round(Math.sqrt(variance), 3);
        List<Double> sorted = new ArrayList<>(normal);
        sorted.sort(Double::compareTo);
        double med = sorted.get(sorted.size() / 2);
        List<Double> devs = new ArrayList<>();
        for (double v : normal) {
            devs.add(Math.abs(v - med));
        }
        devs.sort(Double::compareTo);
        double mad = round(devs.get(devs.size() / 2) * 1.4826, 3);
        return new AnomalyMetric(id, label, service, unit, series, baseline, stddev, mad);
    }

    public AiopsResult aiops(String projectId) {
        long now = System.currentTimeMillis();
        List<AnomalyMetric> metrics = List.of(
                anomalyMetric(projectId, "billing-err", "billing error rate", "billing", "%", 1, 0.4, 0.5, 9),
                anomalyMetric(projectId, "billing-lat", "billing p95 latency", "billing", "ms", 140, 30, 40, 3.2),
                anomalyMetric(projectId, "pg-pool", "postgres pool wait", "postgres", "ms", 60, 20, 25, 9),
                anomalyMetric(projectId, "gw-err", "api-gateway error rate", "api-gateway", "%", 0.6, 0.3, 0.3, 6)
        );
        long suspectTs = now - INCIDENT_WINDOW_MIN * 60000L;
        Problem problem = new Problem("PRB-118", "Charge failures correlated with billing 2.4.0 deploy", "critical",
                "investigating", now - 40 * 60000L, 0.91, List.of("billing", "api-gateway", "web", "postgres"), 12.4,
                "A spike in billing error rate and p95 latency began 2 minutes after billing 2.4.0 was deployed. Anomalies propagate upstream to api-gateway and the web edge, and downstream to postgres pool waits.",
                metrics,
                new RootCause("billing 2.4.0 introduced a synchronous SELECT ... FOR UPDATE on invoices, exhausting the postgres connection pool under load.",
                        0.91, new SuspectDeploy("billing", INCIDENT_DEPLOY_VERSION, INCIDENT_COMMIT, suspectTs, "erin@naqqa.dev"),
                        List.of(
                                new RootCauseStep("billing", "deploy", "2.4.0 (" + INCIDENT_COMMIT + ") rolled out to production", suspectTs),
                                new RootCauseStep("postgres", "resource", "Connection pool wait climbs 60ms to 780ms", suspectTs + 90000),
                                new RootCauseStep("billing", "latency", "charge.capture p95 140ms to 612ms", suspectTs + 120000),
                                new RootCauseStep("billing", "errors", "PoolTimeoutException, error rate 1% to 9.4%", suspectTs + 150000),
                                new RootCauseStep("api-gateway", "errors", "Upstream 5xx surface as gateway errors 3.2%", suspectTs + 180000),
                                new RootCauseStep("web", "impact", "Checkout fails for 12.4% of sessions", suspectTs + 210000))),
                List.of(
                        new WhatChangedRow("service.version", "2.4.0", 0, 97, 97, 0.86),
                        new WhatChangedRow("endpoint", INCIDENT_ENDPOINT, 22, 71, 49, 0.74),
                        new WhatChangedRow("error.type", "PoolTimeoutException", 1, 63, 62, 0.69),
                        new WhatChangedRow("region", "eu-central-1", 41, 58, 17, 0.21),
                        new WhatChangedRow("db.statement", "SELECT invoices FOR UPDATE", 0, 44, 44, 0.58)),
                List.of(
                        new ProblemEvidence("deploy", "billing", "Deploy billing 2.4.0", "commit " + INCIDENT_COMMIT + " by erin@naqqa.dev", suspectTs, 0.95),
                        new ProblemEvidence("metric", "billing", "Error rate anomaly", "z-score 8.7, MAD score 11.2", suspectTs + 150000, 0.92),
                        new ProblemEvidence("metric", "postgres", "Pool wait anomaly", "z-score 9.1 on pg.pool.wait", suspectTs + 90000, 0.88),
                        new ProblemEvidence("trace", "billing", "Exemplar slow trace", "charge.capture 2.1s, 86% in DB lock wait", suspectTs + 160000, 0.84),
                        new ProblemEvidence("log", "billing", "Log pattern burst", "\"PoolTimeoutException\" x2,140 in 5m (baseline 3)", suspectTs + 155000, 0.9),
                        new ProblemEvidence("infra", "billing", "CPU saturation", "billing-1 CPU 92% sustained", suspectTs + 170000, 0.61)),
                List.of(
                        new Correlation(suspectTs, "deploy", "billing", "billing " + INCIDENT_DEPLOY_VERSION + " deployed", "deploy"),
                        new Correlation(suspectTs + 90000, "metric spike", "postgres", "pg.pool.wait breaches baseline band", "metric"),
                        new Correlation(suspectTs + 150000, "error surge", "billing", "error rate crosses 5% SLO burn", "metric"),
                        new Correlation(suspectTs + 155000, "log burst", "billing", "PoolTimeoutException pattern emerges", "log"),
                        new Correlation(suspectTs + 160000, "trace exemplar", "billing", "slow trace pinned to DB lock wait", "trace"),
                        new Correlation(suspectTs + 210000, "user impact", "web", "checkout success drops to 87.6%", "infra")));

        Problem secondary = new Problem("PRB-117", "Elevated dead clicks on promotions carousel", "low", "open",
                now - 5 * 3600000L, 0.63, List.of("web"), 2.1,
                "Dead-click rate on the home promotions carousel rose above its 7-day baseline on mobile Safari after the 1.9.2 web release.",
                List.of(anomalyMetric(projectId, "ux-dead", "dead clicks", "web", "count", 9, 4, 4, 2.4)),
                new RootCause("A lazy-loaded carousel image blocks the tap target on mobile Safari for ~400ms after render.",
                        0.63, new SuspectDeploy("web", "1.9.2", "c21be44", now - 6 * 3600000L, "alice@naqqa.dev"),
                        List.of(
                                new RootCauseStep("web", "deploy", "1.9.2 changed carousel to lazy images", now - 6 * 3600000L),
                                new RootCauseStep("web", "ux", "dead_click rate 9 to 22 on mobile Safari", now - 5 * 3600000L))),
                List.of(
                        new WhatChangedRow("browser", "Mobile Safari", 28, 64, 36, 0.71),
                        new WhatChangedRow("page", "/", 40, 58, 18, 0.33)),
                List.of(
                        new ProblemEvidence("deploy", "web", "Deploy web 1.9.2", "commit c21be44", now - 6 * 3600000L, 0.6),
                        new ProblemEvidence("metric", "web", "Dead-click anomaly", "z-score 3.1 on mobile Safari", now - 5 * 3600000L, 0.64)),
                List.of(
                        new Correlation(now - 6 * 3600000L, "deploy", "web", "web 1.9.2 deployed", "deploy"),
                        new Correlation(now - 5 * 3600000L, "ux anomaly", "web", "dead clicks exceed baseline", "metric")));

        return new AiopsResult(List.of(problem, secondary));
    }
}
