package com.naqqa.elasticsearch.node.indices;

import com.naqqa.elasticsearch.cluster.service.ClusterChangedEvent;
import com.naqqa.elasticsearch.cluster.service.ClusterStateListener;
import com.naqqa.elasticsearch.cluster.state.IndexMetadata;
import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.indices.ilm.IlmRunner;
import com.naqqa.elasticsearch.indices.ilm.IndexLifecycleActionExecutor;
import com.naqqa.elasticsearch.indices.ilm.LifecycleAction;
import com.naqqa.elasticsearch.indices.ilm.LifecycleExecutionState;
import com.naqqa.elasticsearch.indices.ilm.LifecyclePolicy;
import com.naqqa.elasticsearch.indices.ilm.Phase;
import com.naqqa.elasticsearch.indices.ilm.PhaseName;
import com.naqqa.elasticsearch.node.support.SettingsMaps;
import com.naqqa.elasticsearch.rest.support.RestApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

public final class LifecycleService implements ClusterStateListener {

    public static final String POLICY_SETTING = "index.lifecycle.name";

    private final IlmRunner runner;
    private final Object lock = new Object();
    private final Map<String, Map<String, Object>> policySources = new ConcurrentHashMap<>();
    private final Map<String, String> attached = new ConcurrentHashMap<>();
    private volatile boolean running = true;
    private volatile Map<String, IndexMetadata> latestIndices;

    private final java.util.function.Supplier<com.naqqa.elasticsearch.cluster.state.ClusterState> stateSupplier;

    public LifecycleService(IndexLifecycleActionExecutor executor,
                            java.util.function.Supplier<com.naqqa.elasticsearch.cluster.state.ClusterState> stateSupplier) {
        this.runner = new IlmRunner(executor);
        this.stateSupplier = stateSupplier;
    }

    public void putPolicy(String name, Map<String, Object> body) {
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
        synchronized (lock) {
            runner.putPolicy(builder.build());
        }
        policySources.put(name, new LinkedHashMap<>(body));
        reconcile(stateSupplier.get().getMetadata().getIndices());
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
        synchronized (lock) {
            runner.deletePolicy(name);
        }
    }

    public Map<String, Object> explain(List<String> indices) {
        Map<String, Object> out = new LinkedHashMap<>();
        synchronized (lock) {
            reconcile(stateSupplier.get().getMetadata().getIndices());
            for (String index : indices) {
                LifecycleExecutionState state = runner.getExecutionState(index);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("index", index);
                m.put("managed", state != null);
                if (state != null) {
                    m.put("policy", attached.get(index));
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
        }
        return Map.of("indices", out);
    }

    public void retry(String index) {
        synchronized (lock) {
            runner.retry(index, System.currentTimeMillis());
        }
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public boolean running() {
        return running;
    }

    public void tick() {
        if (!running) {
            return;
        }
        synchronized (lock) {
            reconcile(stateSupplier.get().getMetadata().getIndices());
            try {
                runner.tick(System.currentTimeMillis());
            } catch (RuntimeException e) {
                System.err.println("[ilm] tick failed: " + e);
            }
        }
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        latestIndices = event.state().getMetadata().getIndices();
    }

    private void reconcile(Map<String, IndexMetadata> indices) {
        synchronized (lock) {
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
                attached.put(imd.getIndex(), policy);
            }
        }
    }
}
