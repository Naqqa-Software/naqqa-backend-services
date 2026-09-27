package com.naqqa.elasticsearch.action.byquery;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ThrottleRegistry {

    private final ConcurrentMap<Long, ThrottleController> controllers = new ConcurrentHashMap<>();

    public void put(long taskId, ThrottleController controller) {
        controllers.put(taskId, controller);
    }

    public void remove(long taskId) {
        controllers.remove(taskId);
    }

    public ThrottleController get(long taskId) {
        return controllers.get(taskId);
    }
}
