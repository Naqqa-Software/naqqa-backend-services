package com.naqqa.elasticsearch.snapshots.slm;

import com.naqqa.elasticsearch.snapshots.model.SnapshotInfo;
import com.naqqa.elasticsearch.snapshots.repository.CreateSnapshotRequest;
import com.naqqa.elasticsearch.snapshots.repository.Repository;
import com.naqqa.elasticsearch.snapshots.repository.SnapshotException;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SlmRunner {

    private final Map<String, Repository> repositories;
    private final Map<String, PolicyState> states = new LinkedHashMap<>();

    public SlmRunner(Map<String, Repository> repositories) {
        this.repositories = repositories;
    }

    public void addPolicy(SlmPolicy policy, SlmSnapshotSource snapshotSource, Instant now) {
        PolicyState state = new PolicyState();
        state.policy = policy;
        state.snapshotSource = snapshotSource;
        state.schedule = SlmSchedule.parse(policy.schedule());
        state.nextFireTime = state.schedule.nextFireTime(now);
        states.put(policy.id(), state);
    }

    public void removePolicy(String policyId) {
        states.remove(policyId);
    }

    public Instant nextFireTime(String policyId) {
        PolicyState state = states.get(policyId);
        return state == null ? null : state.nextFireTime;
    }

    public List<String> createdSnapshots(String policyId) {
        PolicyState state = states.get(policyId);
        return state == null ? List.of() : List.copyOf(state.createdSnapshots);
    }

    public List<SlmTriggerResult> tick(Instant now) {
        List<SlmTriggerResult> triggered = new ArrayList<>();
        for (PolicyState state : states.values()) {
            int guard = 0;
            while (!state.nextFireTime.isAfter(now) && guard++ < 10000) {
                SnapshotInfo created = execute(state, state.nextFireTime);
                List<String> deleted = enforceRetention(state, now);
                triggered.add(new SlmTriggerResult(state.policy.id(), created.name(), deleted));
                state.nextFireTime = state.schedule.nextFireTime(state.nextFireTime);
            }
        }
        return triggered;
    }

    private SnapshotInfo execute(PolicyState state, Instant fireTime) {
        SlmPolicy policy = state.policy;
        Repository repository = repositories.get(policy.repository());
        if (repository == null) {
            throw new SnapshotException("unknown repository for policy " + policy.id() + ": " + policy.repository());
        }
        String snapshotName = DateMathResolver.resolve(policy.snapshotNamePattern(), fireTime, ZoneOffset.UTC);
        SlmSnapshotSource source = state.snapshotSource;
        CreateSnapshotRequest request = new CreateSnapshotRequest(
                snapshotName,
                source.indices(),
                source.shardSources(),
                source.metadataSource(),
                policy.id(),
                null,
                fireTime);
        try {
            SnapshotInfo info = repository.createSnapshot(request);
            state.createdSnapshots.add(info.name());
            return info;
        } catch (IOException e) {
            throw new SnapshotException("SLM policy " + policy.id() + " failed to create snapshot", e);
        }
    }

    private List<String> enforceRetention(PolicyState state, Instant now) {
        SlmPolicy policy = state.policy;
        if (policy.retention() == null) {
            return List.of();
        }
        Repository repository = repositories.get(policy.repository());
        List<SnapshotInfo> policySnapshots = repository.listSnapshots().stream()
                .filter(s -> policy.id().equals(s.policyId()))
                .toList();
        List<String> toDelete = policy.retention().namesToDelete(policySnapshots, now);
        for (String name : toDelete) {
            try {
                repository.deleteSnapshot(name);
            } catch (IOException e) {
                throw new SnapshotException("SLM retention failed to delete snapshot " + name, e);
            }
        }
        return toDelete;
    }

    public record SlmTriggerResult(String policyId, String snapshotName, List<String> retentionDeletions) {
    }

    private static final class PolicyState {
        SlmPolicy policy;
        SlmSnapshotSource snapshotSource;
        SlmSchedule schedule;
        Instant nextFireTime;
        List<String> createdSnapshots = new ArrayList<>();
    }
}
