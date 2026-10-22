package com.naqqa.sso.billing;

import java.util.Objects;

public record QuotaDecision(Level level, String reason) {

    public enum Level {
        ALLOW("allow"),
        SOFT_LIMIT("soft_limit"),
        HARD_LIMIT("hard_limit");

        private final String wire;

        Level(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    public QuotaDecision {
        Objects.requireNonNull(level, "level");
        reason = reason == null ? "" : reason;
    }

    public static QuotaDecision allow() {
        return new QuotaDecision(Level.ALLOW, "");
    }

    public static QuotaDecision soft(String reason) {
        return new QuotaDecision(Level.SOFT_LIMIT, reason);
    }

    public static QuotaDecision hard(String reason) {
        return new QuotaDecision(Level.HARD_LIMIT, reason);
    }

    public boolean blocks() {
        return level == Level.HARD_LIMIT;
    }
}
