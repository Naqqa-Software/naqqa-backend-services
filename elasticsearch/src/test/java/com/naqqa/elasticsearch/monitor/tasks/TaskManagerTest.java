package com.naqqa.elasticsearch.monitor.tasks;

import com.naqqa.elasticsearch.test.Test;
import java.util.List;
import java.util.Map;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertFalse;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class TaskManagerTest {

    @Test
    public void registersListsAndUnregistersTasks() {
        TaskManager manager = new TaskManager("node-a");
        Task parent = manager.register("transport", "indices:data/write/bulk", "bulk request", true, null);
        Task child = manager.register("transport", "indices:data/write/bulk[s]", "shard bulk", true, parent.id());

        List<Task> all = manager.list(null, null, null);
        assertEquals(2, all.size());

        List<Task> children = manager.list(null, null, parent.id());
        assertEquals(1, children.size());
        assertEquals(child.id(), children.get(0).id());

        assertTrue(manager.get(parent.id()).isPresent());
        manager.unregister(child.id());
        assertFalse(manager.get(child.id()).isPresent());
        assertEquals(1, manager.size());
    }

    @Test
    public void cancelCascadesToChildTasks() {
        TaskManager manager = new TaskManager("node-a");
        Task parent = manager.register("transport", "action.parent", "parent", true, null);
        Task child = manager.register("transport", "action.child", "child", true, parent.id());
        Task grandchild = manager.register("transport", "action.grandchild", "grandchild", true, child.id());

        boolean cancelled = manager.cancel(parent.id(), "user requested");

        assertTrue(cancelled);
        assertTrue(parent.isCancelled());
        assertTrue(child.isCancelled());
        assertTrue(grandchild.isCancelled());
        assertEquals("user requested", grandchild.cancellationSignal().reason());
    }

    @Test
    public void nonCancellableTaskCannotBeCancelled() {
        TaskManager manager = new TaskManager("node-a");
        Task task = manager.register("transport", "action.get", "get", false, null);
        boolean cancelled = manager.cancel(task.id(), "reason");
        assertFalse(cancelled);
        assertFalse(task.isCancelled());
    }

    @Test
    public void tasksApiMapHasNodesShape() {
        TaskManager manager = new TaskManager("node-a");
        Task task = manager.register("direct", "cluster:monitor/tasks/list", "task list", false, null);

        Map<String, Object> map = manager.toTasksApiMap("node-one", null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> nodes = (Map<String, Object>) map.get("nodes");
        assertTrue(nodes.containsKey("node-a"));
        @SuppressWarnings("unchecked")
        Map<String, Object> nodeEntry = (Map<String, Object>) nodes.get("node-a");
        assertEquals("node-one", nodeEntry.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> tasksMap = (Map<String, Object>) nodeEntry.get("tasks");
        assertTrue(tasksMap.containsKey(task.taskId()));
    }

    @Test
    public void waitForCompletionResolvesWhenTaskCompletes() throws Exception {
        TaskManager manager = new TaskManager("node-a");
        Task task = manager.register("direct", "action.async", "async op", false, null);
        task.complete("done");
        Object result = manager.waitForCompletion(task.id(), 1000).get();
        assertEquals("done", result);
    }
}
