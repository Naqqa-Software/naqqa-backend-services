package com.naqqa.elasticsearch.indices.ilm;

public final class LifecycleExecutionState {

    public static final String STEP_EXECUTE = "execute";
    public static final String STEP_ACTION_COMPLETE = "action_complete";
    public static final String STEP_PHASE_COMPLETE = "phase_complete";
    public static final String STEP_ERROR = "ERROR";

    public static final LifecycleExecutionState INITIAL = new LifecycleExecutionState(
        null, null, null, null, -1, null, null, null, null, 0);

    private final PhaseName phase;
    private final Long phaseTime;
    private final String action;
    private final Long actionTime;
    private final int actionIndex;
    private final String step;
    private final Long stepTime;
    private final String failedStep;
    private final String stepInfo;
    private final int retryCount;

    private LifecycleExecutionState(PhaseName phase, Long phaseTime, String action, Long actionTime, int actionIndex,
                                     String step, Long stepTime, String failedStep, String stepInfo, int retryCount) {
        this.phase = phase;
        this.phaseTime = phaseTime;
        this.action = action;
        this.actionTime = actionTime;
        this.actionIndex = actionIndex;
        this.step = step;
        this.stepTime = stepTime;
        this.failedStep = failedStep;
        this.stepInfo = stepInfo;
        this.retryCount = retryCount;
    }

    public PhaseName getPhase() {
        return phase;
    }

    public Long getPhaseTime() {
        return phaseTime;
    }

    public String getAction() {
        return action;
    }

    public Long getActionTime() {
        return actionTime;
    }

    public int getActionIndex() {
        return actionIndex;
    }

    public String getStep() {
        return step;
    }

    public Long getStepTime() {
        return stepTime;
    }

    public String getFailedStep() {
        return failedStep;
    }

    public String getStepInfo() {
        return stepInfo;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public boolean isInErrorStep() {
        return STEP_ERROR.equals(step);
    }

    public boolean isPhaseComplete() {
        return STEP_PHASE_COMPLETE.equals(step);
    }

    public LifecycleExecutionState enterPhase(PhaseName newPhase, long now) {
        return new LifecycleExecutionState(newPhase, now, null, null, -1, null, now, null, null, 0);
    }

    public LifecycleExecutionState startAction(String actionName, int index, long now) {
        return new LifecycleExecutionState(phase, phaseTime, actionName, now, index, STEP_EXECUTE, now, null, null, retryCount);
    }

    public LifecycleExecutionState actionComplete(long now) {
        return new LifecycleExecutionState(phase, phaseTime, action, actionTime, actionIndex, STEP_ACTION_COMPLETE,
            now, null, null, 0);
    }

    public LifecycleExecutionState phaseComplete(long now) {
        return new LifecycleExecutionState(phase, phaseTime, action, actionTime, actionIndex, STEP_PHASE_COMPLETE,
            now, null, null, 0);
    }

    public LifecycleExecutionState error(String info, long now) {
        return new LifecycleExecutionState(phase, phaseTime, action, actionTime, actionIndex, STEP_ERROR, now,
            action, info, retryCount);
    }

    public LifecycleExecutionState retry(long now) {
        if (!isInErrorStep()) {
            throw new IllegalStateException("cannot retry an index that is not in the ERROR step");
        }
        return new LifecycleExecutionState(phase, phaseTime, action, actionTime, actionIndex, STEP_EXECUTE, now,
            null, null, retryCount + 1);
    }

    public static LifecycleExecutionState of(PhaseName phase, Long phaseTime, String action, Long actionTime, int actionIndex,
                                              String step, Long stepTime, String failedStep, String stepInfo, int retryCount) {
        return new LifecycleExecutionState(phase, phaseTime, action, actionTime, actionIndex, step, stepTime, failedStep,
            stepInfo, retryCount);
    }
}
