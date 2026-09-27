package com.naqqa.elasticsearch.script;

import com.naqqa.elasticsearch.script.functions.ScoreScriptFunctions;

import java.util.function.LongSupplier;

public final class CompilationRateLimiter {

    public record Rate(int count, long periodNanos) {
        public boolean unlimited() {
            return count <= 0;
        }
    }

    private final Rate rate;
    private final String rateString;
    private final LongSupplier clock;
    private double tokens;
    private long lastNanos;
    private long triggered;

    public CompilationRateLimiter(String rateString, LongSupplier clock) {
        this.rate = parseRate(rateString);
        this.rateString = rateString;
        this.clock = clock;
        this.tokens = rate.count();
        this.lastNanos = clock.getAsLong();
    }

    public static Rate parseRate(String value) {
        if (value == null) {
            throw new IllegalArgumentException("compilation rate must not be null");
        }
        String v = value.trim();
        if (v.equals("unlimited") || v.equals("use-context")) {
            return new Rate(0, 1);
        }
        int slash = v.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("parameter must contain a positive integer and a timevalue, i.e. 10/1m, but was [" + value + "]");
        }
        int count;
        try {
            count = Integer.parseInt(v.substring(0, slash).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("could not parse [" + v.substring(0, slash) + "] as integer in value [" + value + "]", e);
        }
        if (count < 0) {
            throw new IllegalArgumentException("rate [" + count + "] must be positive");
        }
        double millis = ScoreScriptFunctions.parseTimeMillis(v.substring(slash + 1));
        if (millis <= 0) {
            throw new IllegalArgumentException("time value must be positive in [" + value + "]");
        }
        return new Rate(count == 0 ? -1 : count, (long) (millis * 1_000_000L));
    }

    public synchronized void check() {
        if (rate.unlimited() && rate.count() == 0) {
            return;
        }
        long now = clock.getAsLong();
        long elapsed = now - lastNanos;
        lastNanos = now;
        int max = Math.max(rate.count(), 0);
        tokens = Math.min(max, tokens + (double) elapsed * max / rate.periodNanos());
        if (tokens < 1.0) {
            triggered++;
            throw new ScriptCircuitBreakingException("[script] Too many dynamic script compilations within, max: [" + rateString
                + "]; please use indexed, or scripts with parameters instead; this limit can be changed by the [script.max_compilations_rate] setting", "TRANSIENT");
        }
        tokens -= 1.0;
    }

    public synchronized long triggered() {
        return triggered;
    }

    public Rate rate() {
        return rate;
    }
}
