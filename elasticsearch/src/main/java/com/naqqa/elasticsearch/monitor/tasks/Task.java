package com.naqqa.elasticsearch.monitor.tasks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class Task {

    private final long id;
    private final String node;
    private final String type;
    private final String action;
    private final String description;
    private final long startTimeMillis;
    private final boolean cancellable;
    private final Long parentTaskId;
    private final TaskCancellationSignal cancellationSignal;
    private final CompletableFuture<Object> completionFuture = new CompletableFuture<>();

    public Task(long id, String node, String type, String action, String description, long startTimeMillis,
            boolean cancellable, Long parentTaskId) {
        this.id = id;
        this.node = node;
        this.type = type;
        this.action = action;
        this.description = description;
        this.startTimeMillis = startTimeMillis;
        this.cancellable = cancellable;
        this.parentTaskId = parentTaskId;
        this.cancellationSignal = cancellable ? new TaskCancellationSignal() : null;
    }

    public long id() {
        return id;
    }

    public String node() {
        return node;
    }

    public String type() {
        return type;
    }

    public String action() {
        return action;
    }

    public String description() {
        return description;
    }

    public long startTimeMillis() {
        return startTimeMillis;
    }

    public boolean cancellable() {
        return cancellable;
    }

    public Long parentTaskId() {
        return parentTaskId;
    }

    public TaskCancellationSignal cancellationSignal() {
        return cancellationSignal;
    }

    public boolean isCancelled() {
        return cancellationSignal != null && cancellationSignal.isCancelled();
    }

    public CompletableFuture<Object> future() {
        return completionFuture;
    }

    public void complete(Object result) {
        completionFuture.complete(result);
    }

    public void completeExceptionally(Throwable error) {
        completionFuture.completeExceptionally(error);
    }

    public String taskId() {
        return node + ":" + id;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("node", node);
        map.put("id", id);
        map.put("type", type);
        map.put("action", action);
        map.put("description", description);
        map.put("start_time_in_millis", startTimeMillis);
        map.put("running_time_in_nanos", (System.currentTimeMillis() - startTimeMillis) * 1_000_000L);
        map.put("cancellable", cancellable);
        map.put("cancelled", isCancelled());
        if (parentTaskId != null) {
            map.put("parent_task_id", node + ":" + parentTaskId);
        }
        return map;
    }
}
