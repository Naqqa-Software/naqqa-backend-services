package com.naqqa.elasticsearch.cluster.service;

import com.naqqa.elasticsearch.cluster.state.ClusterState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public final class MasterService {

    public interface PublishFunction {
        void publish(ClusterState newState, PublishCallback callback);
    }

    public interface PublishCallback {
        void onResponse(ClusterState committedState);

        void onFailure(Exception e);
    }

    private final Supplier<Boolean> isMasterSupplier;
    private final Supplier<ClusterState> currentStateSupplier;
    private final PublishFunction publishFunction;
    private final List<PendingTask<?>> queue = new ArrayList<>();
    private long sequence = 0L;

    public MasterService(Supplier<Boolean> isMasterSupplier, Supplier<ClusterState> currentStateSupplier,
                          PublishFunction publishFunction) {
        this.isMasterSupplier = isMasterSupplier;
        this.currentStateSupplier = currentStateSupplier;
        this.publishFunction = publishFunction;
    }

    public <T> void submitTask(String source, T task, ClusterStateTaskConfig config,
                                ClusterStateTaskExecutor<T> executor,
                                ClusterStateTaskExecutor.TaskListener<T> listener, long nowMillis) {
        synchronized (queue) {
            queue.add(new PendingTask<>(source, task, config, executor, listener, nowMillis, sequence++));
        }
    }

    public List<PendingTaskInfo> pendingTasks(long nowMillis) {
        List<PendingTaskInfo> result = new ArrayList<>();
        synchronized (queue) {
            for (PendingTask<?> task : queue) {
                result.add(new PendingTaskInfo(task.source, task.config.priority(), nowMillis - task.submittedAt));
            }
        }
        return result;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public void tick(long nowMillis) {
        List<PendingTask<?>> expired = new ArrayList<>();
        PendingTask<?> chosenTask;
        List<PendingTask<?>> batch = new ArrayList<>();
        synchronized (queue) {
            for (int i = queue.size() - 1; i >= 0; i--) {
                PendingTask<?> task = queue.get(i);
                if (task.config.timeoutMillis() >= 0 && nowMillis - task.submittedAt > task.config.timeoutMillis()) {
                    expired.add(task);
                    queue.remove(i);
                }
            }
            for (PendingTask<?> task : expired) {
                failTask(task, new IllegalStateException("cluster state update task timed out"));
            }
            if (queue.isEmpty() || !Boolean.TRUE.equals(isMasterSupplier.get())) {
                if (!queue.isEmpty() && !Boolean.TRUE.equals(isMasterSupplier.get())) {
                    List<PendingTask<?>> notMaster = new ArrayList<>(queue);
                    queue.clear();
                    for (PendingTask<?> task : notMaster) {
                        failTask(task, new IllegalStateException("not master"));
                    }
                }
                return;
            }
            chosenTask = queue.stream().min(Comparator.comparing((PendingTask<?> t) -> t.config.priority())
                .thenComparing(t -> t.sequence)).orElse(null);
            if (chosenTask == null) {
                return;
            }
            Object executor = chosenTask.executor;
            for (PendingTask<?> task : queue) {
                if (task.executor == executor) {
                    batch.add(task);
                }
            }
            queue.removeAll(batch);
        }
        runBatch((PendingTask) batch.get(0), (List) batch, nowMillis);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void runBatch(PendingTask<?> representative, List<PendingTask<Object>> batch, long nowMillis) {
        ClusterStateTaskExecutor<Object> executor = (ClusterStateTaskExecutor<Object>) representative.executor;
        ClusterState currentState = currentStateSupplier.get();
        List<Object> tasks = new ArrayList<>();
        for (PendingTask<Object> task : batch) {
            tasks.add(task.task);
        }
        ClusterState newState;
        try {
            newState = executor.execute(currentState, tasks);
        } catch (Exception e) {
            for (PendingTask<Object> task : batch) {
                failTask(task, e);
            }
            return;
        }
        if (newState == currentState || newState.getVersion() == currentState.getVersion()) {
            for (PendingTask<Object> task : batch) {
                succeedTask(task, currentState);
            }
            return;
        }
        publishFunction.publish(newState, new PublishCallback() {
            @Override
            public void onResponse(ClusterState committedState) {
                for (PendingTask<Object> task : batch) {
                    succeedTask(task, committedState);
                }
            }

            @Override
            public void onFailure(Exception e) {
                for (PendingTask<Object> task : batch) {
                    failTask(task, e);
                }
            }
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void succeedTask(PendingTask task, ClusterState state) {
        if (task.listener != null) {
            ((ClusterStateTaskExecutor.TaskListener) task.listener).onSuccess(task.task, state);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void failTask(PendingTask task, Exception e) {
        if (task.listener != null) {
            ((ClusterStateTaskExecutor.TaskListener) task.listener).onFailure(task.task, e);
        }
    }

    public record PendingTaskInfo(String source, Priority priority, long timeInQueueMillis) {
    }

    private static final class PendingTask<T> {
        final String source;
        final T task;
        final ClusterStateTaskConfig config;
        final ClusterStateTaskExecutor<T> executor;
        final ClusterStateTaskExecutor.TaskListener<T> listener;
        final long submittedAt;
        final long sequence;

        PendingTask(String source, T task, ClusterStateTaskConfig config, ClusterStateTaskExecutor<T> executor,
                    ClusterStateTaskExecutor.TaskListener<T> listener, long submittedAt, long sequence) {
            this.source = source;
            this.task = task;
            this.config = config;
            this.executor = executor;
            this.listener = listener;
            this.submittedAt = submittedAt;
            this.sequence = sequence;
        }
    }
}
