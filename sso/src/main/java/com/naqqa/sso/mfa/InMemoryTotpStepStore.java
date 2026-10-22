package com.naqqa.sso.mfa;

import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryTotpStepStore implements TotpStepStore {
    private final ConcurrentHashMap<String, Long> steps = new ConcurrentHashMap<>();

    @Override
    public long lastStep(String userId) {
        return steps.getOrDefault(userId, -1L);
    }

    @Override
    public boolean advance(String userId, long step) {
        boolean[] advanced = {false};
        steps.compute(userId, (k, old) -> {
            if (old == null || step > old) {
                advanced[0] = true;
                return step;
            }
            return old;
        });
        return advanced[0];
    }

    @Override
    public void clear(String userId) {
        steps.remove(userId);
    }
}
