package com.naqqa.elasticsearch.indices.ilm;

import com.naqqa.elasticsearch.common.exception.ElasticsearchException;
import com.naqqa.elasticsearch.indices.tier.DataTier;
import com.naqqa.elasticsearch.indices.tier.DataTierAllocation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class IlmRunner {

    private static final class Attachment {
        final String policyName;
        final long originationTimeMillis;
        LifecycleExecutionState state;

        Attachment(String policyName, long originationTimeMillis, LifecycleExecutionState state) {
            this.policyName = policyName;
            this.originationTimeMillis = originationTimeMillis;
            this.state = state;
        }
    }

    private final IndexLifecycleActionExecutor executor;
    private final Map<String, LifecyclePolicy> policies = new LinkedHashMap<>();
    private final Map<String, Attachment> attachments = new LinkedHashMap<>();

    public IlmRunner(IndexLifecycleActionExecutor executor) {
        this.executor = executor;
    }

    public void putPolicy(LifecyclePolicy policy) {
        policies.put(policy.getName(), policy);
    }

    public LifecyclePolicy getPolicy(String name) {
        return policies.get(name);
    }

    public void deletePolicy(String name) {
        policies.remove(name);
    }

    public void attachPolicy(String index, String policyName, long originationTimeMillis) {
        if (!policies.containsKey(policyName)) {
            throw new ElasticsearchException("lifecycle policy [{}] not found", policyName);
        }
        attachments.put(index, new Attachment(policyName, originationTimeMillis, LifecycleExecutionState.INITIAL));
    }

    public void detachPolicy(String index) {
        attachments.remove(index);
    }

    public LifecycleExecutionState getExecutionState(String index) {
        Attachment attachment = attachments.get(index);
        return attachment == null ? null : attachment.state;
    }

    public void restoreState(String index, LifecycleExecutionState state) {
        Attachment attachment = attachments.get(index);
        if (attachment != null && state != null) {
            attachment.state = state;
        }
    }

    public void retry(String index, long now) {
        Attachment attachment = requireAttachment(index);
        attachment.state = attachment.state.retry(now);
    }

    private Attachment requireAttachment(String index) {
        Attachment attachment = attachments.get(index);
        if (attachment == null) {
            throw new ElasticsearchException("index [{}] has no attached lifecycle policy", index);
        }
        return attachment;
    }

    public void tick(long now) {
        for (Map.Entry<String, Attachment> entry : attachments.entrySet()) {
            String index = entry.getKey();
            Attachment attachment = entry.getValue();
            LifecyclePolicy policy = policies.get(attachment.policyName);
            if (policy == null || attachment.state.isInErrorStep()) {
                continue;
            }
            attachment.state = advance(index, attachment, policy, now);
        }
    }

    private LifecycleExecutionState advance(String index, Attachment attachment, LifecyclePolicy policy, long now) {
        LifecycleExecutionState state = attachment.state;
        List<PhaseName> order = policy.orderedPhaseNames();
        if (order.isEmpty()) {
            return state;
        }
        if (state.getPhase() == null) {
            state = state.enterPhase(order.get(0), now);
        } else if (state.isPhaseComplete()) {
            int idx = order.indexOf(state.getPhase());
            if (idx < 0 || idx + 1 >= order.size()) {
                return state;
            }
            PhaseName next = order.get(idx + 1);
            long age = now - attachment.originationTimeMillis;
            long minAgeMillis = policy.getPhase(next).getMinAge().millis();
            if (age < minAgeMillis) {
                return state;
            }
            state = state.enterPhase(next, now);
        }

        Phase phase = policy.getPhase(state.getPhase());
        List<LifecycleAction> actions = phase.getActions();
        if (actions.isEmpty()) {
            return state.phaseComplete(now);
        }
        int startIndex = LifecycleExecutionState.STEP_EXECUTE.equals(state.getStep()) ? state.getActionIndex() : 0;
        for (int i = startIndex; i < actions.size(); i++) {
            LifecycleAction action = actions.get(i);
            state = state.startAction(action.name(), i, now);
            try {
                executeAction(index, action, phase.getName());
            } catch (Exception e) {
                return state.error(e.getMessage(), now);
            }
            if (i == actions.size() - 1) {
                state = state.phaseComplete(now);
            } else {
                state = state.actionComplete(now);
            }
        }
        return state;
    }

    private void executeAction(String index, LifecycleAction action, PhaseName phase) {
        switch (action) {
            case LifecycleAction.RolloverAction rollover -> executor.rolloverIndex(index);
            case LifecycleAction.ShrinkAction shrink -> executor.shrinkIndex(index, shrink.numberOfShards());
            case LifecycleAction.ForceMergeAction forceMerge -> executor.forceMergeIndex(index, forceMerge.maxNumSegments());
            case LifecycleAction.ReadOnlyAction readOnly -> executor.setIndexReadOnly(index, true);
            case LifecycleAction.AllocateAction allocate -> {
                Map<String, String> settings = new LinkedHashMap<>();
                allocate.include().forEach((k, v) -> settings.put("index.routing.allocation.include." + k, v));
                allocate.exclude().forEach((k, v) -> settings.put("index.routing.allocation.exclude." + k, v));
                allocate.require().forEach((k, v) -> settings.put("index.routing.allocation.require." + k, v));
                executor.allocateIndex(index, settings, allocate.numberOfReplicas());
            }
            case LifecycleAction.MigrateAction migrate -> {
                if (migrate.enabled()) {
                    DataTier tier = tierForPhase(phase);
                    executor.allocateIndex(index, DataTierAllocation.preferenceSettings(tier), null);
                }
            }
            case LifecycleAction.DeleteAction delete -> executor.deleteIndex(index);
            case LifecycleAction.SetPriorityAction setPriority -> {
                if (setPriority.priority() != null) {
                    executor.setIndexPriority(index, setPriority.priority());
                }
            }
        }
    }

    private static DataTier tierForPhase(PhaseName phase) {
        return switch (phase) {
            case HOT -> DataTier.DATA_HOT;
            case WARM -> DataTier.DATA_WARM;
            case COLD -> DataTier.DATA_COLD;
            case FROZEN -> DataTier.DATA_FROZEN;
            case DELETE -> DataTier.DATA_FROZEN;
        };
    }
}
