package com.naqqa.elasticsearch.node.action;

import com.naqqa.elasticsearch.cluster.service.ClusterChangedEvent;
import com.naqqa.elasticsearch.cluster.service.ClusterStateListener;
import com.naqqa.elasticsearch.cluster.state.MapCustom;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.common.logging.ESLogger;
import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.node.cluster.ClusterStateManager;
import com.naqqa.elasticsearch.node.snapshots.SnapshotsService;
import com.naqqa.elasticsearch.node.support.SettingsMaps;
import com.naqqa.elasticsearch.rest.support.RestApiException;
import com.naqqa.elasticsearch.snapshots.model.SnapshotInfo;
import com.naqqa.elasticsearch.snapshots.slm.DateMathResolver;
import com.naqqa.elasticsearch.snapshots.slm.Retention;
import com.naqqa.elasticsearch.snapshots.slm.SlmPolicy;
import com.naqqa.elasticsearch.snapshots.slm.SlmSchedule;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

public final class NodeSlmActionService implements ClusterStateListener {

    public static final String POLICIES_CUSTOM = "slm_policies";

    private static final ESLogger LOG = ESLogger.getLogger(NodeSlmActionService.class);

    private static final ScheduledExecutorService SHARED_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "es-slm");
        t.setDaemon(true);
        return t;
    });

    private static final class PolicyRuntime {
        SlmPolicy policy;
        SlmSchedule schedule;
        Instant nextFireTime;
        List<String> createdSnapshots = new ArrayList<>();
        long snapshotsTaken;
        long snapshotsFailed;
        long snapshotsDeleted;
        long snapshotDeletionFailures;
        long retentionRuns;
    }

    private final SnapshotsService snapshotsService;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Map<String, Object>> policySources = new ConcurrentHashMap<>();
    private final Map<String, PolicyRuntime> runtimes = new ConcurrentHashMap<>();
    private volatile boolean running = true;
    private volatile ClusterStateManager clusterStateManager;
    private volatile Metadata pendingSync;
    private volatile ScheduledFuture<?> scheduledTask;

    public NodeSlmActionService(SnapshotsService snapshotsService) {
        this.snapshotsService = snapshotsService;
    }

    public void bind(ClusterStateManager clusterStateManager) {
        this.clusterStateManager = clusterStateManager;
        lock.lock();
        try {
            applySyncLocked(clusterStateManager.state().getMetadata());
        } finally {
            lock.unlock();
        }
        clusterStateManager.addListener(this);
        scheduledTask = SHARED_SCHEDULER.scheduleWithFixedDelay(this::tickSafely, 1000L, 1000L, TimeUnit.MILLISECONDS);
    }

    public void close() {
        ScheduledFuture<?> task = scheduledTask;
        if (task != null) {
            task.cancel(false);
        }
    }

    private boolean isMasterEligibleToRun() {
        return clusterStateManager == null || !clusterStateManager.isMultiNode() || clusterStateManager.isLeader();
    }

    private void tickSafely() {
        try {
            tick();
        } catch (Throwable t) {
            LOG.warn("SLM tick failed", t);
        }
    }

    private void drainPendingSync() {
        Metadata metadata = pendingSync;
        if (metadata != null) {
            pendingSync = null;
            applySyncLocked(metadata);
        }
    }

    private void applySyncLocked(Metadata metadata) {
        MapCustom custom = metadata.mapCustom(POLICIES_CUSTOM);
        for (String id : custom.ids()) {
            Map<String, Object> body = custom.get(id);
            if (body.equals(policySources.get(id))) {
                continue;
            }
            try {
                applyPolicyLocally(id, body);
            } catch (RuntimeException e) {
                LOG.warn("failed to apply SLM policy [{}] from cluster state", e, id);
            }
        }
        for (String id : new ArrayList<>(policySources.keySet())) {
            if (!custom.contains(id)) {
                policySources.remove(id);
                runtimes.remove(id);
            }
        }
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        if (event.state().getMetadata() == event.previousState().getMetadata()) {
            return;
        }
        Metadata metadata = event.state().getMetadata();
        if (lock.tryLock()) {
            try {
                applySyncLocked(metadata);
            } finally {
                lock.unlock();
            }
        } else {
            pendingSync = metadata;
        }
    }

    private static Long numberOrNull(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Retention parseRetention(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        Duration expireAfter = body.get("expire_after") == null ? null
            : Duration.ofMillis(TimeValue.parseTimeValue(String.valueOf(body.get("expire_after")), "expire_after").millis());
        Integer minCount = body.get("min_count") == null ? null : Integer.valueOf(String.valueOf(body.get("min_count")));
        Integer maxCount = body.get("max_count") == null ? null : Integer.valueOf(String.valueOf(body.get("max_count")));
        return new Retention(expireAfter, minCount, maxCount);
    }

    private void applyPolicyLocally(String id, Map<String, Object> storedBody) {
        Map<String, Object> policyMap = SettingsMaps.asMap(storedBody.get("policy"));
        if (policyMap == null) {
            throw new RestApiException(400, "[" + id + "] missing [policy]");
        }
        String schedule = policyMap.get("schedule") == null ? null : String.valueOf(policyMap.get("schedule"));
        String namePattern = policyMap.get("name") == null ? null : String.valueOf(policyMap.get("name"));
        String repository = policyMap.get("repository") == null ? null : String.valueOf(policyMap.get("repository"));
        if (schedule == null || namePattern == null || repository == null) {
            throw new RestApiException(400, "[" + id + "] requires [schedule], [name] and [repository]");
        }
        Map<String, Object> config = SettingsMaps.asMap(policyMap.get("config"));
        List<String> indices = config == null || config.get("indices") == null
            ? List.of("*") : SettingsMaps.asStringList(config.get("indices"));
        Retention retention = parseRetention(SettingsMaps.asMap(policyMap.get("retention")));
        SlmPolicy policy = new SlmPolicy(id, schedule, namePattern, repository, indices, retention);
        SlmSchedule scheduleObj;
        try {
            scheduleObj = SlmSchedule.parse(schedule);
        } catch (RuntimeException e) {
            throw new RestApiException(400, "[" + id + "] invalid schedule [" + schedule + "]: " + e.getMessage(), e);
        }
        PolicyRuntime rt = runtimes.computeIfAbsent(id, k -> new PolicyRuntime());
        rt.policy = policy;
        rt.schedule = scheduleObj;
        Long persistedNext = numberOrNull(storedBody.get("next_execution_millis"));
        rt.nextFireTime = persistedNext != null ? Instant.ofEpochMilli(persistedNext) : scheduleObj.nextFireTime(Instant.now());
        rt.createdSnapshots = new ArrayList<>(SettingsMaps.asStringList(storedBody.get("created_snapshots")));
        Map<String, Object> stats = SettingsMaps.asMap(storedBody.get("stats"));
        if (stats != null) {
            rt.snapshotsTaken = stats.get("snapshots_taken") == null ? 0 : ((Number) stats.get("snapshots_taken")).longValue();
            rt.snapshotsFailed = stats.get("snapshots_failed") == null ? 0 : ((Number) stats.get("snapshots_failed")).longValue();
            rt.snapshotsDeleted = stats.get("snapshots_deleted") == null ? 0 : ((Number) stats.get("snapshots_deleted")).longValue();
            rt.snapshotDeletionFailures = stats.get("snapshot_deletion_failures") == null
                ? 0 : ((Number) stats.get("snapshot_deletion_failures")).longValue();
            rt.retentionRuns = stats.get("retention_runs") == null ? 0 : ((Number) stats.get("retention_runs")).longValue();
        }
        policySources.put(id, new LinkedHashMap<>(storedBody));
    }

    private Map<String, Object> mergeRuntimeIntoStored(String id, PolicyRuntime rt) {
        Map<String, Object> stored = new LinkedHashMap<>(policySources.getOrDefault(id, Map.of()));
        stored.put("next_execution_millis", rt.nextFireTime.toEpochMilli());
        stored.put("created_snapshots", new ArrayList<>(rt.createdSnapshots));
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("snapshots_taken", rt.snapshotsTaken);
        stats.put("snapshots_failed", rt.snapshotsFailed);
        stats.put("snapshots_deleted", rt.snapshotsDeleted);
        stats.put("snapshot_deletion_failures", rt.snapshotDeletionFailures);
        stats.put("retention_runs", rt.retentionRuns);
        stored.put("stats", stats);
        return stored;
    }

    private void mutate(String source, java.util.function.UnaryOperator<Metadata> op) {
        if (clusterStateManager == null) {
            return;
        }
        try {
            clusterStateManager.submit(source, cs -> cs.builder().metadata(op.apply(cs.getMetadata())).build())
                .get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RestApiException(500, cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw new RestApiException(503, "timed out waiting for cluster state update [" + source + "]");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RestApiException(500, "interrupted");
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RestApiException(500, cause.getMessage(), cause);
        }
    }

    public void putPolicy(String id, Map<String, Object> requestBody) {
        Map<String, Object> stored;
        lock.lock();
        try {
            drainPendingSync();
            Map<String, Object> existing = policySources.get(id);
            long version = existing == null || existing.get("version") == null
                ? 1L : ((Number) existing.get("version")).longValue() + 1L;
            stored = new LinkedHashMap<>();
            stored.put("policy", requestBody);
            stored.put("version", version);
            stored.put("modified_date_millis", System.currentTimeMillis());
            if (existing != null) {
                if (existing.get("created_snapshots") != null) {
                    stored.put("created_snapshots", existing.get("created_snapshots"));
                }
                if (existing.get("stats") != null) {
                    stored.put("stats", existing.get("stats"));
                }
            }
            applyPolicyLocally(id, stored);
            stored = policySources.get(id);
        } finally {
            lock.unlock();
        }
        Map<String, Object> body = stored;
        mutate("put-slm-policy [" + id + "]", md -> md.toBuilder().mutateMapCustom(POLICIES_CUSTOM, mc -> mc.with(id, body)).build());
    }

    public void deletePolicy(String id) {
        lock.lock();
        try {
            drainPendingSync();
            if (policySources.remove(id) == null) {
                throw new RestApiException(404, "snapshot lifecycle policy not found: " + id);
            }
            runtimes.remove(id);
        } finally {
            lock.unlock();
        }
        mutate("delete-slm-policy [" + id + "]", md -> md.toBuilder().mutateMapCustom(POLICIES_CUSTOM, mc -> mc.without(id)).build());
    }

    private Map<String, Object> buildPolicyView(String id, Map<String, Object> stored) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("version", stored.getOrDefault("version", 1L));
        view.put("modified_date_millis", stored.getOrDefault("modified_date_millis", 0L));
        view.put("policy", stored.get("policy"));
        PolicyRuntime rt = runtimes.get(id);
        if (rt != null) {
            view.put("next_execution_millis", rt.nextFireTime.toEpochMilli());
            view.put("stats", policyStatsView(id, rt));
        }
        return view;
    }

    private static Map<String, Object> policyStatsView(String id, PolicyRuntime rt) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("policy", id);
        stats.put("snapshots_taken", rt.snapshotsTaken);
        stats.put("snapshots_failed", rt.snapshotsFailed);
        stats.put("snapshots_deleted", rt.snapshotsDeleted);
        stats.put("snapshot_deletion_failures", rt.snapshotDeletionFailures);
        return stats;
    }

    public Map<String, Object> getPolicies(String id) {
        Map<String, Object> out = new TreeMap<>();
        lock.lock();
        try {
            drainPendingSync();
            for (Map.Entry<String, Map<String, Object>> e : policySources.entrySet()) {
                if (id != null && !id.equals(e.getKey())) {
                    continue;
                }
                out.put(e.getKey(), buildPolicyView(e.getKey(), e.getValue()));
            }
        } finally {
            lock.unlock();
        }
        if (id != null && out.isEmpty()) {
            throw new RestApiException(404, "snapshot lifecycle policy not found: " + id);
        }
        return out;
    }

    private SnapshotInfo doExecute(PolicyRuntime rt, Instant fireTime) {
        String snapshotName = DateMathResolver.resolve(rt.policy.snapshotNamePattern(), fireTime, ZoneOffset.UTC);
        SnapshotInfo info;
        try {
            info = snapshotsService.createSnapshot(rt.policy.repository(), snapshotName, rt.policy.indices());
        } catch (RuntimeException e) {
            rt.snapshotsFailed++;
            throw e;
        }
        rt.snapshotsTaken++;
        rt.createdSnapshots.add(info.name());
        if (rt.createdSnapshots.size() > 1000) {
            rt.createdSnapshots.remove(0);
        }
        enforceRetention(rt);
        return info;
    }

    private void enforceRetention(PolicyRuntime rt) {
        if (rt.policy.retention() == null) {
            return;
        }
        SnapshotsService.RepositoryEntry entry;
        try {
            entry = snapshotsService.repository(rt.policy.repository());
        } catch (RuntimeException e) {
            return;
        }
        List<SnapshotInfo> policySnapshots = new ArrayList<>();
        for (String name : rt.createdSnapshots) {
            entry.repository().getSnapshot(name).ifPresent(policySnapshots::add);
        }
        rt.retentionRuns++;
        List<String> toDelete = rt.policy.retention().namesToDelete(policySnapshots, Instant.now());
        for (String name : toDelete) {
            try {
                entry.repository().deleteSnapshot(name);
                rt.createdSnapshots.remove(name);
                rt.snapshotsDeleted++;
            } catch (IOException | RuntimeException e) {
                rt.snapshotDeletionFailures++;
                LOG.warn("SLM retention failed to delete snapshot [{}]", e, name);
            }
        }
    }

    public Map<String, Object> executeNow(String id) {
        SnapshotInfo info;
        Map<String, Object> body;
        lock.lock();
        try {
            drainPendingSync();
            PolicyRuntime rt = runtimes.get(id);
            if (rt == null) {
                throw new RestApiException(404, "snapshot lifecycle policy not found: " + id);
            }
            info = doExecute(rt, Instant.now());
            body = mergeRuntimeIntoStored(id, rt);
            policySources.put(id, body);
        } finally {
            lock.unlock();
        }
        mutate("slm-execute [" + id + "]", md -> md.toBuilder().mutateMapCustom(POLICIES_CUSTOM, mc -> mc.with(id, body)).build());
        return Map.of("snapshot_name", info.name());
    }

    public Map<String, Object> executeRetentionNow() {
        List<Map.Entry<String, Map<String, Object>>> changed = new ArrayList<>();
        lock.lock();
        try {
            drainPendingSync();
            for (Map.Entry<String, PolicyRuntime> e : runtimes.entrySet()) {
                PolicyRuntime rt = e.getValue();
                if (rt.policy.retention() == null) {
                    continue;
                }
                enforceRetention(rt);
                Map<String, Object> body = mergeRuntimeIntoStored(e.getKey(), rt);
                policySources.put(e.getKey(), body);
                changed.add(Map.entry(e.getKey(), body));
            }
        } finally {
            lock.unlock();
        }
        for (Map.Entry<String, Map<String, Object>> e : changed) {
            String id = e.getKey();
            Map<String, Object> body = e.getValue();
            mutate("slm-execute-retention [" + id + "]",
                md -> md.toBuilder().mutateMapCustom(POLICIES_CUSTOM, mc -> mc.with(id, body)).build());
        }
        return Map.of("acknowledged", true);
    }

    public void tick() {
        if (!running || !isMasterEligibleToRun()) {
            return;
        }
        List<Map.Entry<String, Map<String, Object>>> changed = new ArrayList<>();
        lock.lock();
        try {
            drainPendingSync();
            Instant now = Instant.now();
            for (Map.Entry<String, PolicyRuntime> e : runtimes.entrySet()) {
                String id = e.getKey();
                PolicyRuntime rt = e.getValue();
                boolean fired = false;
                int guard = 0;
                while (!rt.nextFireTime.isAfter(now) && guard++ < 1000) {
                    try {
                        doExecute(rt, rt.nextFireTime);
                    } catch (RuntimeException ex) {
                        LOG.warn("SLM policy [{}] failed to execute", ex, id);
                    }
                    rt.nextFireTime = rt.schedule.nextFireTime(rt.nextFireTime);
                    fired = true;
                }
                if (fired) {
                    Map<String, Object> body = mergeRuntimeIntoStored(id, rt);
                    policySources.put(id, body);
                    changed.add(Map.entry(id, body));
                }
            }
        } finally {
            lock.unlock();
        }
        for (Map.Entry<String, Map<String, Object>> e : changed) {
            String id = e.getKey();
            Map<String, Object> body = e.getValue();
            mutate("slm-tick [" + id + "]", md -> md.toBuilder().mutateMapCustom(POLICIES_CUSTOM, mc -> mc.with(id, body)).build());
        }
    }

    public Map<String, Object> stats() {
        lock.lock();
        try {
            drainPendingSync();
            long taken = 0;
            long failed = 0;
            long deleted = 0;
            long deletionFailures = 0;
            long retentionRuns = 0;
            List<Object> perPolicy = new ArrayList<>();
            for (Map.Entry<String, PolicyRuntime> e : runtimes.entrySet()) {
                PolicyRuntime rt = e.getValue();
                taken += rt.snapshotsTaken;
                failed += rt.snapshotsFailed;
                deleted += rt.snapshotsDeleted;
                deletionFailures += rt.snapshotDeletionFailures;
                retentionRuns += rt.retentionRuns;
                perPolicy.add(policyStatsView(e.getKey(), rt));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("retention_runs", retentionRuns);
            out.put("retention_failed", 0L);
            out.put("retention_timed_out", 0L);
            out.put("retention_deletion_time", "0s");
            out.put("retention_deletion_time_millis", 0L);
            out.put("total_snapshots_taken", taken);
            out.put("total_snapshots_failed", failed);
            out.put("total_snapshots_deleted", deleted);
            out.put("total_snapshot_deletion_failures", deletionFailures);
            out.put("policy_stats", perPolicy);
            return out;
        } finally {
            lock.unlock();
        }
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public boolean running() {
        return running;
    }
}
