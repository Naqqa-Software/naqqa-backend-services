package com.naqqa.sso.ratelimit;

public record Decision(Type type, long delayMillis) {
    public enum Type {
        ALLOW, DELAY, CHALLENGE
    }

    public static Decision allow() {
        return new Decision(Type.ALLOW, 0);
    }

    public static Decision delay(long millis) {
        return new Decision(Type.DELAY, millis);
    }

    public static Decision challenge() {
        return new Decision(Type.CHALLENGE, 0);
    }
}
