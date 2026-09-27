package com.naqqa.elasticsearch.script;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

public final class ScriptSettings {

    public static final int DEFAULT_MAX_LOOP_COUNTER = 1_000_000;
    public static final String DEFAULT_MAX_COMPILATIONS_RATE = "150/5m";

    public enum RegexMode {
        ENABLED, DISABLED, LIMITED
    }

    private String maxCompilationsRate = DEFAULT_MAX_COMPILATIONS_RATE;
    private int cacheMaxSize = 3000;
    private Duration cacheExpire = Duration.ZERO;
    private Set<String> allowedTypes;
    private Set<String> allowedContexts;
    private int maxLoopCounter = DEFAULT_MAX_LOOP_COUNTER;
    private RegexMode regexMode = RegexMode.LIMITED;
    private int regexLimitFactor = 6;
    private int maxSizeInBytes = 65535;
    private boolean bytecodeEnabled = true;
    private LongSupplier nanoClock = System::nanoTime;

    public static ScriptSettings defaults() {
        return new ScriptSettings();
    }

    public static ScriptSettings fromMap(Map<String, ?> settings) {
        ScriptSettings s = new ScriptSettings();
        for (Map.Entry<String, ?> e : settings.entrySet()) {
            Object v = e.getValue();
            switch (e.getKey()) {
                case "script.max_compilations_rate", "script.context.*.max_compilations_rate" -> s.maxCompilationsRate(String.valueOf(v));
                case "script.cache.max_size" -> s.cacheMaxSize(Integer.parseInt(String.valueOf(v)));
                case "script.cache.expire" -> s.cacheExpire(Duration.ofMillis((long) com.naqqa.elasticsearch.script.functions.ScoreScriptFunctions.parseTimeMillis(String.valueOf(v))));
                case "script.allowed_types" -> s.allowedTypes(toSet(v));
                case "script.allowed_contexts" -> s.allowedContexts(toSet(v));
                case "script.painless.regex.enabled" -> s.regexMode(parseRegexMode(String.valueOf(v)));
                case "script.painless.regex.limit-factor" -> s.regexLimitFactor(Integer.parseInt(String.valueOf(v)));
                case "script.max_size_in_bytes" -> s.maxSizeInBytes(Integer.parseInt(String.valueOf(v)));
                case "script.painless.max_loop_counter", "script.max_loop_counter" -> s.maxLoopCounter(Integer.parseInt(String.valueOf(v)));
                case "script.painless.bytecode" -> s.bytecodeEnabled(Boolean.parseBoolean(String.valueOf(v)));
                default -> {
                }
            }
        }
        return s;
    }

    public static RegexMode parseRegexMode(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "true" -> RegexMode.ENABLED;
            case "false" -> RegexMode.DISABLED;
            case "limited" -> RegexMode.LIMITED;
            default -> throw new IllegalArgumentException("invalid value [" + v + "] for [script.painless.regex.enabled], must be one of [true, false, limited]");
        };
    }

    private static Set<String> toSet(Object v) {
        Set<String> out = new LinkedHashSet<>();
        if (v instanceof Iterable<?> it) {
            for (Object o : it) {
                out.add(String.valueOf(o).trim());
            }
        } else {
            for (String p : String.valueOf(v).split(",")) {
                if (!p.isBlank()) {
                    out.add(p.trim());
                }
            }
        }
        return out;
    }

    public String maxCompilationsRate() {
        return maxCompilationsRate;
    }

    public ScriptSettings maxCompilationsRate(String rate) {
        CompilationRateLimiter.parseRate(rate);
        this.maxCompilationsRate = rate;
        return this;
    }

    public int cacheMaxSize() {
        return cacheMaxSize;
    }

    public ScriptSettings cacheMaxSize(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("script.cache.max_size must be >= 0");
        }
        this.cacheMaxSize = size;
        return this;
    }

    public Duration cacheExpire() {
        return cacheExpire;
    }

    public ScriptSettings cacheExpire(Duration expire) {
        this.cacheExpire = expire;
        return this;
    }

    public Set<String> allowedTypes() {
        return allowedTypes;
    }

    public ScriptSettings allowedTypes(Set<String> types) {
        if (types != null) {
            for (String t : types) {
                if (!t.equals("none")) {
                    ScriptType.fromName(t);
                }
            }
        }
        this.allowedTypes = types == null ? null : Set.copyOf(types);
        return this;
    }

    public ScriptSettings allowedTypes(String... types) {
        return allowedTypes(new LinkedHashSet<>(List.of(types)));
    }

    public Set<String> allowedContexts() {
        return allowedContexts;
    }

    public ScriptSettings allowedContexts(Set<String> contexts) {
        this.allowedContexts = contexts == null ? null : Set.copyOf(contexts);
        return this;
    }

    public ScriptSettings allowedContexts(String... contexts) {
        return allowedContexts(new LinkedHashSet<>(List.of(contexts)));
    }

    public int maxLoopCounter() {
        return maxLoopCounter;
    }

    public ScriptSettings maxLoopCounter(int max) {
        this.maxLoopCounter = max;
        return this;
    }

    public RegexMode regexMode() {
        return regexMode;
    }

    public ScriptSettings regexMode(RegexMode mode) {
        this.regexMode = mode;
        return this;
    }

    public int regexLimitFactor() {
        return regexLimitFactor;
    }

    public ScriptSettings regexLimitFactor(int factor) {
        this.regexLimitFactor = factor;
        return this;
    }

    public int maxSizeInBytes() {
        return maxSizeInBytes;
    }

    public ScriptSettings maxSizeInBytes(int max) {
        this.maxSizeInBytes = max;
        return this;
    }

    public boolean bytecodeEnabled() {
        return bytecodeEnabled;
    }

    public ScriptSettings bytecodeEnabled(boolean enabled) {
        this.bytecodeEnabled = enabled;
        return this;
    }

    public LongSupplier nanoClock() {
        return nanoClock;
    }

    public ScriptSettings nanoClock(LongSupplier clock) {
        this.nanoClock = clock;
        return this;
    }

    public boolean isTypeAllowed(ScriptType type) {
        return allowedTypes == null || allowedTypes.contains(type.parseName());
    }

    public boolean isContextAllowed(ScriptContext context) {
        return allowedContexts == null || allowedContexts.contains(context.name());
    }
}
