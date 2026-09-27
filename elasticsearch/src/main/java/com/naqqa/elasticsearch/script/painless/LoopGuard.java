package com.naqqa.elasticsearch.script.painless;

public final class LoopGuard {

    private static final ThreadLocal<int[]> STATE = ThreadLocal.withInitial(() -> new int[]{0, Integer.MAX_VALUE});

    private LoopGuard() {
    }

    public static void reset(int max) {
        int[] state = STATE.get();
        state[0] = 0;
        state[1] = max;
    }

    public static void tick() {
        int[] state = STATE.get();
        state[0]++;
        if (state[0] > state[1]) {
            throw new PainlessRuntimeError("The maximum number of statements that can be executed in a loop has been reached: " + state[1]);
        }
    }
}
