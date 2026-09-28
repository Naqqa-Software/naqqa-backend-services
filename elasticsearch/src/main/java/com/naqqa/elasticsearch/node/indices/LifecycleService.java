package com.naqqa.elasticsearch.node.indices;

import com.naqqa.elasticsearch.cluster.service.ClusterChangedEvent;
import com.naqqa.elasticsearch.cluster.service.ClusterStateListener;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.cluster.state.MapCustom;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.indices.ilm.IlmRunner;
import com.naqqa.elasticsearch.indices.ilm.IndexLifecycleActionExecutor;
import com.naqqa.elasticsearch.indices.ilm.LifecycleAction;
import com.naqqa.elasticsearch.indices.ilm.LifecycleExecutionState;
import com.naqqa.elasticsearch.indices.ilm.LifecyclePolicy;
import com.naqqa.elasticsearch.indices.ilm.Phase;
import com.naqqa.elasticsearch.indices.ilm.PhaseName;
import com.naqqa.elasticsearch.node.cluster.ClusterStateManager;
import com.naqqa.elasticsearch.node.support.SettingsMaps;
import com.naqqa.elasticsearch.rest.support.RestApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

public final class LifecycleService implements ClusterStateListener {

    public static final String POLICY_SETTING = "index.lifecycle.name";
    public static final String POLICIES_CUSTOM = "index_lifecycle_policies";
    public static final String ILM_CUSTOM_DATA_KEY = "ilm";

    private final IlmRunner runner;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Map<String, Object>> policySources = new ConcurrentHashMap<>();
    private final Map<String, String> attached = new ConcurrentHashMap<>();
    private volatile boolean running = true;
    private volatile Map<String, IndexMetadata> latestIndices;
    private volatile ClusterStateManager clusterStateManager;
    private volatile Metadata pendingPolicySync;

    private final java.util.function.Supplier<com.naqqa.elasticsearch.cluster.state.ClusterState> stateSupplier;

    public LifecycleService(IndexLifecycleActionExecutor executor,
                            java.util.function.Supplier<com.naqqa.elasticsearch.cluster.state.ClusterState> stateSupplier) {
        this.runner = new IlmRunner(executor);
        this.stateSupplier = stateSupplier;
    }

    public void bind(ClusterStateManager clusterStateManager) {
        this.clusterStateManager = clusterStateManager;
        lock.lock();
        try {
            applyPolicySyncLocked(clusterStateManager.state().getMetadata());
        } finally {
            lock.unlock();
        }
        clusterStateManager.addListener(this);
    }

    private boolean isMasterEligibleToRun() {
        return clusterStateManager == null || !clusterStateManager.isMultiNode() || clusterStateManager.isLeader();
    }

    /**
     * Applies a pending cluster-state policy sync while the caller already holds {@link #lock}.
     * Cluster-state listener notifications can fire synchronously from within a thread that is
     * itself blocked waiting on a cluster-state submission made while holding {@link #lock} (e.g.
     * from {@link #tick()} executing a rollover); acquiring {@link #lock} again from
     * {@link #clusterChanged} in that situation would deadlock, so that path only records the
     * latest metadata and every method that legitimately holds the lock drains it first.
     */
    private void drainPendingPolicySync() {
        Metadata metadata = pendingPolicySync;
        if (metadata != null) {
            pendingPolicySync = null;
            applyPolicySyncLocked(metadata);
        }
    }

    private void applyPolicySyncLocked(Metadata metadata) {
        MapCustom custom = metadata.mapCustom(POLICIES_CUSTOM);
        for (String name : custom.ids()) {
            Map<String, Object> body = custom.get(name);
            if (body.equals(policySources.get(name))) {
                continue;
            }
            try {
                applyPolicyLocally(name, body);
            } catch (RuntimeException e) {
                System.err.println("[ilm] failed to apply lifecycle policy [" + name + "] from cluster state: " + e);
            }
        }
        for (String name : new ArrayList<>(policySources.keySet())) {
            if (!custom.contains(name)) {
                policySources.remove(name);
                runner.deletePolicy(name);
            }
        }
    }

    private void applyPolicyLocally(String name, Map<String, Object> body) {
        Map<String, Object> policy = SettingsMaps.asMap(body.get("policy"));
        if (policy == null) {
            throw new RestApiException(400, "[put_lifecycle_request] requires [policy]");
        }
        LifecyclePolicy.Builder builder = LifecyclePolicy.builder(name);
        Map<String, Object> phases = SettingsMaps.asMap(policy.get("phases"));
        if (phases != null) {
            for (Map.Entry<String, Object> e : phases.entrySet()) {
                PhaseName phaseName;
                try {
                    phaseName = PhaseName.valueOf(e.getKey().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    throw new RestApiException(400, "unknown phase [" + e.getKey() + "]");
                }
                Map<String, Object> phaseBody = SettingsMaps.asMap(e.getValue());
                TimeValue minAge = phaseBody != null && phaseBody.get("min_age") != null
                    ? TimeValue.parseTimeValue(String.valueOf(phaseBody.get("min_age")), "min_age") : TimeValue.ZERO;
                List<LifecycleAction> actions = new ArrayList<>();
                Map<String, Object> actionBodies = phaseBody == null ? null : SettingsMaps.asMap(phaseBody.get("actions"));
                if (actionBodies != null) {
                    for (Map.Entry<String, Object> a : actionBodies.entrySet()) {
                        actions.add(parseAction(a.getKey(), SettingsMaps.asMap(a.getValue())));
                    }
                }
                builder.phase(new Phase(phaseName, minAge, actions));
            }
        }
        runner.putPolicy(builder.build());
        policySources.put(name, new LinkedHashMap<>(body));
    }

    public void putPolicy(String name, Map<String, Object> body) {
        lock.lock();
        try {
            drainPendingPolicySync();
            applyPolicyLocally(name, body);
        } finally {
            lock.unlock();
        }
        mutate("put-ilm-policy [" + name + "]", md -> md.toBuilder().mutateMapCustom(POLICIES_CUSTOM, mc -> mc.with(name, body)).build());
        reconcile(stateSupplier.get().getMetadata().getIndices());
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

    private static Map<String, String> stringMap(Object value) {
        Map<String, Object> m = SettingsMaps.asMap(value);
        Map<String, String> out = new LinkedHashMap<>();
        if (m != null) {
            m.forEach((k, v) -> out.put(k, String.valueOf(v)));
        }
        return out;
    }

    private static LifecycleAction parseAction(String name, Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        return switch (name) {
            case "rollover" -> {
                Map<String, Object> conditions = new LinkedHashMap<>(b);
                yield new LifecycleAction.RolloverAction(com.naqqa.elasticsearch.node.action.NodeIndexAdminActionService.parseConditions(conditions));
            }
            case "shrink" -> new LifecycleAction.ShrinkAction(Integer.parseInt(String.valueOf(b.getOrDefault("number_of_shards", 1))));
            case "forcemerge" -> new LifecycleAction.ForceMergeAction(Integer.parseInt(String.valueOf(b.getOrDefault("max_num_segments", 1))));
            case "allocate" -> new LifecycleAction.AllocateAction(
                b.get("number_of_replicas") == null ? null : Integer.parseInt(String.valueOf(b.get("number_of_replicas"))),
                stringMap(b.get("include")), stringMap(b.get("exclude")), stringMap(b.get("require")));
            case "migrate" -> new LifecycleAction.MigrateAction(!Boolean.FALSE.equals(b.get("enabled")));
            case "readonly" -> new LifecycleAction.ReadOnlyAction();
            case "delete" -> new LifecycleAction.DeleteAction(!Boolean.FALSE.equals(b.get("delete_searchable_snapshot")));
            case "set_priority" -> new LifecycleAction.SetPriorityAction(
                b.get("priority") == null ? null : Integer.parseInt(String.valueOf(b.get("priority"))));
            default -> throw new RestApiException(400, "unknown lifecycle action [" + name + "]");
        };
    }

    public Map<String, Object> getPolicies(String name) {
        Map<String, Object> out = new TreeMap<>();
        for (Map.Entry<String, Map<String, Object>> e : policySources.entrySet()) {
            if (name == null || name.equals(e.getKey()) || com.naqqa.elasticsearch.common.regex.Regex.simpleMatch(name, e.getKey())) {
                out.put(e.getKey(), Map.of("version", 1, "policy", e.getValue().get("policy")));
            }
        }
        if (name != null && out.isEmpty()) {
            throw new RestApiException(404, "Lifecycle policy not found: " + name);
        }
        return out;
    }

    public void deletePolicy(String name) {
        if (policySources.remove(name) == null) {
            throw new RestApiException(404, "Lifecycle policy not found: " + name);
        }
        lock.lock();
        try {
            drainPendingPolicySync();
            runner.deletePolicy(name);
        } finally {
            lock.unlock();
        }
        mutate("delete-ilm-policy [" + name + "]", md -> md.toBuilder().mutateMapCustom(POLICIES_CUSTOM, mc -> mc.without(name)).build());
    }

    private static Map<String, String> toCustomData(LifecycleExecutionState s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (s.getPhase() != null) {
            m.put("phase", s.getPhase().name());
        }
        if (s.getPhaseTime() != null) {
            m.put("phase_time", String.valueOf(s.getPhaseTime()));
        }
        if (s.getAction() != null) {
            m.put("action", s.getAction());
        }
        if (s.getActionTime() != null) {
            m.put("action_time", String.valueOf(s.getActionTime()));
        }
        m.put("action_index", String.valueOf(s.getActionIndex()));
        if (s.getStep() != null) {
            m.put("step", s.getStep());
        }
        if (s.getStepTime() != null) {
            m.put("step_time", String.valueOf(s.getStepTime()));
        }
        if (s.getFailedStep() != null) {
            m.put("failed_step", s.getFailedStep());
        }
        if (s.getStepInfo() != null) {
            m.put("step_info", s.getStepInfo());
        }
        m.put("retry_count", String.valueOf(s.getRetryCount()));
        return m;
    }

    private static LifecycleExecutionState fromCustomData(Map<String, String> m) {
        if (m == null || m.isEmpty()) {
            return null;
        }
        PhaseName phase = m.get("phase") == null ? null : PhaseName.valueOf(m.get("phase"));
        Long phaseTime = m.get("phase_time") == null ? null : Long.valueOf(m.get("phase_time"));
        Long actionTime = m.get("action_time") == null ? null : Long.valueOf(m.get("action_time"));
        Long stepTime = m.get("step_time") == null ? null : Long.valueOf(m.get("step_time"));
        int actionIndex = m.get("action_index") == null ? -1 : Integer.parseInt(m.get("action_index"));
        int retryCount = m.get("retry_count") == null ? 0 : Integer.parseInt(m.get("retry_count"));
        return LifecycleExecutionState.of(phase, phaseTime, m.get("action"), actionTime, actionIndex, m.get("step"), stepTime,
            m.get("failed_step"), m.get("step_info"), retryCount);
    }

    private void persistExecutionStates(Map<String, IndexMetadata> indices) {
        Map<String, LifecycleExecutionState> toPersist = new LinkedHashMap<>();
        for (String index : attached.keySet()) {
            LifecycleExecutionState state = runner.getExecutionState(index);
            IndexMetadata imd = indices.get(index);
            if (state == null || imd == null) {
                continue;
            }
            LifecycleExecutionState persisted = fromCustomData(imd.getCustomData(ILM_CUSTOM_DATA_KEY));
            if (!statesEqual(state, persisted)) {
                toPersist.put(index, state);
            }
        }
        if (toPersist.isEmpty() || clusterStateManager == null) {
            return;
        }
        try {
            clusterStateManager.submit("ilm-execution-state", cs -> {
                Metadata.Builder mb = cs.getMetadata().toBuilder();
                boolean changed = false;
                for (Map.Entry<String, LifecycleExecutionState> e : toPersist.entrySet()) {
                    IndexMetadata imd = cs.getMetadata().index(e.getKey());
                    if (imd == null) {
                        continue;
                    }
                    mb.put(imd.builder().putCustomData(ILM_CUSTOM_DATA_KEY, toCustomData(e.getValue())).build());
                    changed = true;
                }
                return changed ? cs.builder().metadata(mb.build()).build() : cs;
            }).get(30, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            System.err.println("[ilm] failed to persist lifecycle execution state: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean statesEqual(LifecycleExecutionState a, LifecycleExecutionState b) {
        if (a == null || b == null) {
            return a == b;
        }
        return java.util.Objects.equals(a.getPhase(), b.getPhase()) && java.util.Objects.equals(a.getAction(), b.getAction())
            && java.util.Objects.equals(a.getStep(), b.getStep()) && a.getActionIndex() == b.getActionIndex()
            && a.getRetryCount() == b.getRetryCount() && java.util.Objects.equals(a.getFailedStep(), b.getFailedStep());
    }

    public Map<String, Object> explain(List<String> indices) {
        Map<String, Object> out = new LinkedHashMap<>();
        lock.lock();
        try {
            drainPendingPolicySync();
            reconcileLocked(stateSupplier.get().getMetadata().getIndices());
            for (String index : indices) {
                IndexMetadata imd = stateSupplier.get().getMetadata().index(index);
                LifecycleExecutionState state = runner.getExecutionState(index);
                if (state == null && imd != null) {
                    state = fromCustomData(imd.getCustomData(ILM_CUSTOM_DATA_KEY));
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", index);
                m.put("managed", state != null);
                if (state != null) {
                    m.put("policy", attached.getOrDefault(index, imd == null ? null : imd.getSettings().get(POLICY_SETTING)));
                    m.put("phase", state.getPhase() == null ? null : state.getPhase().name().toLowerCase(Locale.ROOT));
                    m.put("action", state.getAction());
                    m.put("step", state.getStep());
                    if (state.getFailedStep() != null) {
                        m.put("failed_step", state.getFailedStep());
                    }
                    if (state.getStepInfo() != null) {
                        m.put("step_info", Map.of("reason", state.getStepInfo()));
                    }
                }
                out.put(index, m);
            }
        } finally {
            lock.unlock();
        }
        return Map.of("indices", out);
    }

    public void retry(String index) {
        lock.lock();
        try {
            drainPendingPolicySync();
            runner.retry(index, System.currentTimeMillis());
        } finally {
            lock.unlock();
        }
        persistExecutionStates(stateSupplier.get().getMetadata().getIndices());
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public boolean running() {
        return running;
    }

    public void tick() {
        if (!running || !isMasterEligibleToRun()) {
            return;
        }
        Map<String, IndexMetadata> indices;
        lock.lock();
        try {
            drainPendingPolicySync();
            indices = stateSupplier.get().getMetadata().getIndices();
            reconcileLocked(indices);
            try {
                runner.tick(System.currentTimeMillis());
            } catch (RuntimeException e) {
                System.err.println("[ilm] tick failed: " + e);
            }
        } finally {
            lock.unlock();
        }
        persistExecutionStates(indices);
    }

    /**
     * Cluster-state listener notifications can be delivered synchronously from a thread that is
     * itself blocked inside a cluster-state submission issued while {@link #lock} is held (e.g.
     * from within {@link #tick()}); {@link ReentrantLock#tryLock()} lets that reentrant case fall
     * through without blocking, deferring the sync to the next call that legitimately holds the
     * lock instead of deadlocking against itself.
     */
    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        latestIndices = event.state().getMetadata().getIndices();
        if (event.state().getMetadata() == event.previousState().getMetadata()) {
            return;
        }
        Metadata metadata = event.state().getMetadata();
        if (lock.tryLock()) {
            try {
                applyPolicySyncLocked(metadata);
            } finally {
                lock.unlock();
            }
        } else {
            pendingPolicySync = metadata;
        }
    }

    private void reconcile(Map<String, IndexMetadata> indices) {
        lock.lock();
        try {
            reconcileLocked(indices);
        } finally {
            lock.unlock();
        }
    }

    private void reconcileLocked(Map<String, IndexMetadata> indices) {
        for (String index : Set.copyOf(attached.keySet())) {
            IndexMetadata imd = indices.get(index);
            String policy = imd == null ? null : imd.getSettings().get(POLICY_SETTING);
            if (policy == null || !policy.equals(attached.get(index))) {
                runner.detachPolicy(index);
                attached.remove(index);
            }
        }
        for (IndexMetadata imd : indices.values()) {
            String policy = imd.getSettings().get(POLICY_SETTING);
            if (policy == null || attached.containsKey(imd.getIndex()) || runner.getPolicy(policy) == null) {
                continue;
            }
            long origination = imd.getSettings().getAsLong("index.lifecycle.origination_date",
                imd.getSettings().getAsLong("index.creation_date", System.currentTimeMillis()));
            runner.attachPolicy(imd.getIndex(), policy, origination);
            LifecycleExecutionState persisted = fromCustomData(imd.getCustomData(ILM_CUSTOM_DATA_KEY));
            if (persisted != null) {
                runner.restoreState(imd.getIndex(), persisted);
            }
            attached.put(imd.getIndex(), policy);
        }
    }
}
