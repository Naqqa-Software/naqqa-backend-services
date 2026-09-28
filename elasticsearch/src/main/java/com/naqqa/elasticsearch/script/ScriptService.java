package com.naqqa.elasticsearch.script;

import com.naqqa.elasticsearch.cluster.service.ClusterChangedEvent;
import com.naqqa.elasticsearch.cluster.service.ClusterStateListener;
import com.naqqa.elasticsearch.cluster.state.Metadata;
import com.naqqa.elasticsearch.node.cluster.ClusterStateManager;
import com.naqqa.elasticsearch.rest.support.RestApiException;
import com.naqqa.elasticsearch.script.expression.ExpressionScriptEngine;
import com.naqqa.elasticsearch.script.mustache.MustacheScriptEngine;
import com.naqqa.elasticsearch.script.painless.PainlessScriptEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

public final class ScriptService implements ClusterStateListener {

    public static final String STORED_SCRIPTS_CUSTOM = "stored_scripts";

    private record CacheKey(String lang, String context, String source) {}

    private record CacheEntry(CompiledScript script, long compiledAtNanos) {}

    private final ScriptSettings settings;
    private final Map<String, ScriptEngine> engines = new ConcurrentHashMap<>();
    private final Map<String, StoredScriptSource> storedScripts = new ConcurrentHashMap<>();
    private final Map<String, CompilationRateLimiter> rateLimiters = new ConcurrentHashMap<>();
    private final LinkedHashMap<CacheKey, CacheEntry> cache;
    private final ReentrantLock cacheLock = new ReentrantLock();
    private long compilationCount;
    private volatile ClusterStateManager clusterStateManager;

    public ScriptService(ScriptSettings settings) {
        this.settings = settings == null ? ScriptSettings.defaults() : settings;
        this.cache = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, CacheEntry> eldest) {
                return size() > ScriptService.this.settings.cacheMaxSize();
            }
        };
        registerEngine(new PainlessScriptEngine(this.settings));
        registerEngine(new ExpressionScriptEngine());
        registerEngine(new MustacheScriptEngine());
    }

    public static ScriptService defaults() {
        return new ScriptService(ScriptSettings.defaults());
    }

    public ScriptSettings settings() {
        return settings;
    }

    public void registerEngine(ScriptEngine engine) {
        engines.put(engine.type(), engine);
    }

    public void bind(ClusterStateManager clusterStateManager) {
        this.clusterStateManager = clusterStateManager;
        syncFromClusterState(clusterStateManager.state().getMetadata());
        clusterStateManager.addListener(this);
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        if (event.state().getMetadata() != event.previousState().getMetadata()) {
            syncFromClusterState(event.state().getMetadata());
        }
    }

    private void syncFromClusterState(Metadata metadata) {
        com.naqqa.elasticsearch.cluster.state.MapCustom custom = metadata.mapCustom(STORED_SCRIPTS_CUSTOM);
        for (String id : new ArrayList<>(storedScripts.keySet())) {
            if (!custom.contains(id)) {
                storedScripts.remove(id);
            }
        }
        for (String id : custom.ids()) {
            StoredScriptSource parsed = StoredScriptSource.parse(Map.of("script", custom.get(id)));
            if (parsed.equals(storedScripts.get(id))) {
                continue;
            }
            try {
                putStoredScriptLocal(id, parsed);
            } catch (RuntimeException e) {
                System.err.println("[script] failed to apply stored script [" + id + "] from cluster state: " + e);
            }
        }
    }

    private void putStoredScriptLocal(String id, StoredScriptSource source) {
        if (!engines.containsKey(source.lang())) {
            throw new IllegalArgumentException("unable to put stored script with unsupported lang [" + source.lang() + "]");
        }
        engines.get(source.lang()).validate(source.source());
        storedScripts.put(id, source);
    }

    public void putStoredScript(String id, StoredScriptSource source) {
        putStoredScriptLocal(id, source);
        mutate("put-stored-script [" + id + "]", md -> md.toBuilder()
            .mutateMapCustom(STORED_SCRIPTS_CUSTOM, mc -> mc.with(id, source.toMap())).build());
    }

    public StoredScriptSource getStoredScript(String id) {
        StoredScriptSource s = storedScripts.get(id);
        if (s == null) {
            throw new IllegalArgumentException("stored script [" + id + "] does not exist");
        }
        return s;
    }

    public boolean hasStoredScript(String id) {
        return storedScripts.containsKey(id);
    }

    public void deleteStoredScript(String id) {
        if (storedScripts.remove(id) == null) {
            throw new IllegalArgumentException("stored script [" + id + "] does not exist");
        }
        mutate("delete-stored-script [" + id + "]", md -> md.toBuilder()
            .mutateMapCustom(STORED_SCRIPTS_CUSTOM, mc -> mc.without(id)).build());
    }

    private void mutate(String source, java.util.function.UnaryOperator<Metadata> op) {
        if (clusterStateManager == null) {
            return;
        }
        try {
            clusterStateManager.submit(source, cs -> cs.builder().metadata(op.apply(cs.getMetadata())).build())
                .get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RestApiException(500, cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw new RestApiException(503, "timed out waiting for cluster state update [" + source + "]");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RestApiException(500, "interrupted");
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RestApiException(500, cause.getMessage(), cause);
        }
    }

    public long compilationCount() {
        cacheLock.lock();
        try {
            return compilationCount;
        } finally {
            cacheLock.unlock();
        }
    }

    public CompiledScript compile(Script script, ScriptContext context) {
        if (!settings.isContextAllowed(context)) {
            throw new IllegalArgumentException("[" + context.name() + "] scripts cannot be executed");
        }
        String lang;
        String source;
        if (script.getType() == ScriptType.STORED) {
            if (!settings.isTypeAllowed(script.getType())) {
                throw new IllegalArgumentException("[stored] scripts cannot be executed");
            }
            StoredScriptSource stored = getStoredScript(script.getIdOrCode());
            lang = stored.lang();
            source = stored.source();
        } else {
            lang = script.getLang();
            source = script.getIdOrCode();
            if (!settings.isTypeAllowed(script.getType())) {
                throw new IllegalArgumentException("[inline] scripts cannot be executed");
            }
        }
        ScriptEngine engine = engines.get(lang);
        if (engine == null) {
            throw new IllegalArgumentException("script_lang not supported [" + lang + "]");
        }
        if (!engine.supports(context)) {
            throw new IllegalArgumentException("script context [" + context.name() + "] not supported by lang [" + lang + "]");
        }
        CacheKey key = new CacheKey(lang, context.name(), source);
        cacheLock.lock();
        try {
            CacheEntry cached = cache.get(key);
            if (cached != null && !isExpired(cached)) {
                return cached.script();
            }
            CompilationRateLimiter limiter = rateLimiters.computeIfAbsent(context.name(),
                c -> new CompilationRateLimiter(settings.maxCompilationsRate(), settings.nanoClock()));
            limiter.check();
            CompiledScript compiled = engine.compile("inline_" + compilationCount, source, context, script.getOptions());
            compilationCount++;
            cache.put(key, new CacheEntry(compiled, settings.nanoClock().getAsLong()));
            return compiled;
        } finally {
            cacheLock.unlock();
        }
    }

    private boolean isExpired(CacheEntry entry) {
        if (settings.cacheExpire().isZero() || settings.cacheExpire().isNegative()) {
            return false;
        }
        long ageNanos = settings.nanoClock().getAsLong() - entry.compiledAtNanos();
        return ageNanos > settings.cacheExpire().toNanos();
    }

    public Object execute(Script script, ScriptContext context, Map<String, Object> variables) {
        return execute(script, context, variables, null);
    }

    public Object execute(Script script, ScriptContext context, Map<String, Object> variables, Emitter emitter) {
        CompiledScript compiled = compile(script, context);
        Map<String, Object> vars = new java.util.LinkedHashMap<>(variables == null ? Map.of() : variables);
        vars.put("params", script.getParams());
        return compiled.execute(vars, emitter);
    }
}
