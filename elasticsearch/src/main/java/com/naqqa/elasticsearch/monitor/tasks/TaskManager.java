package com.naqqa.elasticsearch.monitor.tasks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

public final class TaskManager {

    private final String node;
    private final Map<Long, Task> tasks = new ConcurrentHashMap<>();
    private final Map<Long, Set<Long>> childrenByParent = new ConcurrentHashMap<>();
    private final AtomicLong idGenerator = new AtomicLong();

    public TaskManager(String node) {
        this.node = node;
    }

    public Task register(String type, String action, String description, boolean cancellable, Long parentTaskId) {
        long id = idGenerator.incrementAndGet();
        Task task = new Task(id, node, type, action, description, System.currentTimeMillis(), cancellable,
                parentTaskId);
        tasks.put(id, task);
        if (parentTaskId != null) {
            childrenByParent.computeIfAbsent(parentTaskId, key -> new CopyOnWriteArraySet<>()).add(id);
        }
        return task;
    }

    public void unregister(long id) {
        Task task = tasks.remove(id);
        if (task != null && task.parentTaskId() != null) {
            Set<Long> siblings = childrenByParent.get(task.parentTaskId());
            if (siblings != null) {
                siblings.remove(id);
            }
        }
        childrenByParent.remove(id);
    }

    public Optional<Task> get(long id) {
        return Optional.ofNullable(tasks.get(id));
    }

    public List<Task> list(String action, String nodeFilter, Long parentTaskId) {
        List<Task> result = new ArrayList<>();
        for (Task task : tasks.values()) {
            if (action != null && !task.action().equals(action)) {
                continue;
            }
            if (nodeFilter != null && !task.node().equals(nodeFilter)) {
                continue;
            }
            if (parentTaskId != null && !parentTaskId.equals(task.parentTaskId())) {
                continue;
            }
            result.add(task);
        }
        return result;
    }

    public boolean cancel(long id, String reason) {
        Task task = tasks.get(id);
        if (task == null || !task.cancellable()) {
            return false;
        }
        boolean cancelled = task.cancellationSignal().cancel(reason);
        for (long childId : childrenByParent.getOrDefault(id, Set.of())) {
            cancel(childId, reason);
        }
        return cancelled;
    }

    public CompletableFuture<Object> waitForCompletion(long id, long timeoutMillis) {
        Task task = tasks.get(id);
        if (task == null) {
            CompletableFuture<Object> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalArgumentException("no such task: " + id));
            return failed;
        }
        CompletableFuture<Object> future = task.future();
        if (timeoutMillis <= 0) {
            return future;
        }
        CompletableFuture<Object> withTimeout = new CompletableFuture<>();
        future.whenComplete((result, error) -> {
            if (error != null) {
                withTimeout.completeExceptionally(error);
            } else {
                withTimeout.complete(result);
            }
        });
        return withTimeout.orTimeout(timeoutMillis, TimeUnit.MILLISECONDS).exceptionallyCompose(error -> {
            if (error instanceof TimeoutException) {
                CompletableFuture<Object> timeoutFuture = new CompletableFuture<>();
                timeoutFuture.completeExceptionally(error);
                return timeoutFuture;
            }
            CompletableFuture<Object> other = new CompletableFuture<>();
            other.completeExceptionally(error);
            return other;
        });
    }

    public Map<String, Object> toTasksApiMap(String nodeName, String action, Long parentTaskId) {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> nodes = new LinkedHashMap<>();
        Map<String, Object> nodeEntry = new LinkedHashMap<>();
        nodeEntry.put("name", nodeName);
        Map<String, Object> tasksMap = new LinkedHashMap<>();
        for (Task task : list(action, node, parentTaskId)) {
            tasksMap.put(task.taskId(), task.toMap());
        }
        nodeEntry.put("tasks", tasksMap);
        nodes.put(node, nodeEntry);
        root.put("nodes", nodes);
        return root;
    }

    public int size() {
        return tasks.size();
    }
}
