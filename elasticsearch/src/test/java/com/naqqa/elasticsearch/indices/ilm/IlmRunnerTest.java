package com.naqqa.elasticsearch.indices.ilm;

import com.naqqa.elasticsearch.common.unit.TimeValue;
import com.naqqa.elasticsearch.indices.rollover.RolloverConditions;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.List;
import java.util.Map;

public final class IlmRunnerTest {

    private LifecyclePolicy buildPolicy() {
        Phase hot = new Phase(PhaseName.HOT, TimeValue.ZERO, List.of(
            new LifecycleAction.RolloverAction(RolloverConditions.builder().maxDocs(1000L).build()),
            new LifecycleAction.SetPriorityAction(100)));
        Phase warm = new Phase(PhaseName.WARM, TimeValue.timeValueHours(168), List.of(
            new LifecycleAction.ForceMergeAction(1),
            new LifecycleAction.ReadOnlyAction()));
        Phase delete = new Phase(PhaseName.DELETE, TimeValue.timeValueHours(720), List.of(
            new LifecycleAction.DeleteAction(false)));
        return LifecyclePolicy.builder("logs-policy").phase(hot).phase(warm).phase(delete).build();
    }

    @Test
    public void advancesThroughHotWarmDeleteWithErrorAndRetry() {
        FakeIndexLifecycleActionExecutor executor = new FakeIndexLifecycleActionExecutor();
        IlmRunner runner = new IlmRunner(executor);
        runner.putPolicy(buildPolicy());
        String index = "logs-000001";
        runner.attachPolicy(index, "logs-policy", 0L);

        runner.tick(0L);
        LifecycleExecutionState afterHot = runner.getExecutionState(index);
        Assert.assertEquals(PhaseName.HOT, afterHot.getPhase());
        Assert.assertTrue(afterHot.isPhaseComplete());
        Assert.assertTrue(executor.calls.contains("rollover:" + index));
        Assert.assertTrue(executor.calls.contains("priority:" + index + ":100"));

        long earlyWarmTime = TimeValue.timeValueHours(2).millis();
        runner.tick(earlyWarmTime);
        Assert.assertEquals(PhaseName.HOT, runner.getExecutionState(index).getPhase());

        executor.failNextForceMerge(index);
        long warmEntryTime = TimeValue.timeValueHours(168).millis() + 1;
        runner.tick(warmEntryTime);
        LifecycleExecutionState errored = runner.getExecutionState(index);
        Assert.assertTrue(errored.isInErrorStep());
        Assert.assertEquals(PhaseName.WARM, errored.getPhase());
        Assert.assertEquals("forcemerge", errored.getFailedStep());
        Assert.assertFalse(executor.readOnlyIndices.contains(index));

        runner.tick(warmEntryTime + 1000);
        Assert.assertTrue(runner.getExecutionState(index).isInErrorStep());

        runner.retry(index, warmEntryTime + 2000);
        Assert.assertFalse(runner.getExecutionState(index).isInErrorStep());

        runner.tick(warmEntryTime + 3000);
        LifecycleExecutionState afterWarm = runner.getExecutionState(index);
        Assert.assertEquals(PhaseName.WARM, afterWarm.getPhase());
        Assert.assertTrue(afterWarm.isPhaseComplete());
        Assert.assertTrue(executor.calls.contains("forcemerge:" + index + ":1"));
        Assert.assertTrue(executor.readOnlyIndices.contains(index));

        long deleteEntryTime = TimeValue.timeValueHours(720).millis() + 1;
        runner.tick(deleteEntryTime);
        LifecycleExecutionState afterDelete = runner.getExecutionState(index);
        Assert.assertEquals(PhaseName.DELETE, afterDelete.getPhase());
        Assert.assertTrue(afterDelete.isPhaseComplete());
        Assert.assertTrue(executor.deletedIndices.contains(index));
    }

    @Test
    public void migrateActionResolvesTierPreference() {
        FakeIndexLifecycleActionExecutor executor = new FakeIndexLifecycleActionExecutor();
        IlmRunner runner = new IlmRunner(executor);
        Phase hot = new Phase(PhaseName.HOT, TimeValue.ZERO, List.of(new LifecycleAction.MigrateAction(true)));
        LifecyclePolicy policy = LifecyclePolicy.builder("migrate-policy").phase(hot).build();
        runner.putPolicy(policy);
        runner.attachPolicy("idx-1", "migrate-policy", 0L);
        runner.tick(0L);
        boolean sawTierPreference = executor.calls.stream().anyMatch(c -> c.contains("data_hot,data_content"));
        Assert.assertTrue(sawTierPreference);
    }
}
