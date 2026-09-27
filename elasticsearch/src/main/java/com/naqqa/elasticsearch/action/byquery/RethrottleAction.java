package com.naqqa.elasticsearch.action.byquery;

import com.naqqa.elasticsearch.monitor.tasks.Task;
import com.naqqa.elasticsearch.monitor.tasks.TaskManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class RethrottleAction {

    private final TaskManager taskManager;
    private final ThrottleRegistry throttleRegistry;

    public RethrottleAction(TaskManager taskManager, ThrottleRegistry throttleRegistry) {
        this.taskManager = taskManager;
        this.throttleRegistry = throttleRegistry;
    }

    public Map<String, Object> rethrottle(String taskId, double newRequestsPerSecond) {
        long id = parseTaskId(taskId);
        Optional<Task> task = taskManager.get(id);
        if (task.isEmpty()) {
            throw new IllegalArgumentException("no such task [" + taskId + "]");
        }
        ThrottleController controller = throttleRegistry.get(id);
        if (controller == null) {
            throw new IllegalArgumentException("task [" + taskId + "] does not support rethrottling");
        }
        controller.rethrottle(newRequestsPerSecond);

        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, Object> nodes = new LinkedHashMap<>();
        Map<String, Object> nodeEntry = new LinkedHashMap<>();
        Map<String, Object> tasks = new LinkedHashMap<>();
        tasks.put(task.get().taskId(), task.get().toMap());
        nodeEntry.put("tasks", tasks);
        nodes.put(task.get().node(), nodeEntry);
        response.put("nodes", nodes);
        return response;
    }

    static long parseTaskId(String taskId) {
        int idx = taskId.lastIndexOf(':');
        if (idx < 0) {
            return Long.parseLong(taskId.trim());
        }
        return Long.parseLong(taskId.substring(idx + 1).trim());
    }
}
