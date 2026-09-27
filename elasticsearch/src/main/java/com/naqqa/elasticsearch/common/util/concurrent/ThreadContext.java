package com.naqqa.elasticsearch.common.util.concurrent;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

public final class ThreadContext implements AutoCloseable {

    private static final ThreadContextStruct EMPTY = new ThreadContextStruct(Collections.emptyMap(), Collections.emptyMap());
    private final ThreadLocal<ThreadContextStruct> threadLocal = ThreadLocal.withInitial(() -> EMPTY);

    public ThreadContext.StoredContext stashContext() {
        ThreadContextStruct current = threadLocal.get();
        threadLocal.set(EMPTY);
        return () -> threadLocal.set(current);
    }

    public ThreadContext.StoredContext newStoredContext() {
        ThreadContextStruct current = threadLocal.get();
        return () -> threadLocal.set(current);
    }

    public void putHeader(String key, String value) {
        threadLocal.set(threadLocal.get().putRequest(key, value));
    }

    public String getHeader(String key) {
        return threadLocal.get().requestHeaders.get(key);
    }

    public Map<String, String> getHeaders() {
        return Collections.unmodifiableMap(threadLocal.get().requestHeaders);
    }

    public void addResponseHeader(String key, String value) {
        threadLocal.set(threadLocal.get().putResponse(key, value));
    }

    public Set<String> getResponseHeaders(String key) {
        Set<String> values = threadLocal.get().responseHeaders.get(key);
        return values == null ? Collections.emptySet() : Collections.unmodifiableSet(values);
    }

    public Map<String, Set<String>> getResponseHeaders() {
        return Collections.unmodifiableMap(threadLocal.get().responseHeaders);
    }

    public <T> Callable<T> preserveContext(Callable<T> callable) {
        ThreadContextStruct snapshot = threadLocal.get();
        return () -> {
            ThreadContextStruct previous = threadLocal.get();
            threadLocal.set(snapshot);
            try {
                return callable.call();
            } finally {
                threadLocal.set(previous);
            }
        };
    }

    public Runnable preserveContext(Runnable runnable) {
        ThreadContextStruct snapshot = threadLocal.get();
        return () -> {
            ThreadContextStruct previous = threadLocal.get();
            threadLocal.set(snapshot);
            try {
                runnable.run();
            } finally {
                threadLocal.set(previous);
            }
        };
    }

    @Override
    public void close() {
        threadLocal.remove();
    }

    @FunctionalInterface
    public interface StoredContext extends AutoCloseable {
        @Override
        void close();

        default void restore() {
            close();
        }
    }

    private static final class ThreadContextStruct {
        final Map<String, String> requestHeaders;
        final Map<String, Set<String>> responseHeaders;

        ThreadContextStruct(Map<String, String> requestHeaders, Map<String, Set<String>> responseHeaders) {
            this.requestHeaders = requestHeaders;
            this.responseHeaders = responseHeaders;
        }

        ThreadContextStruct putRequest(String key, String value) {
            Map<String, String> newMap = new HashMap<>(requestHeaders);
            newMap.put(key, value);
            return new ThreadContextStruct(newMap, responseHeaders);
        }

        ThreadContextStruct putResponse(String key, String value) {
            Map<String, Set<String>> newMap = new HashMap<>(responseHeaders);
            Set<String> values = new LinkedHashSet<>(newMap.getOrDefault(key, Collections.emptySet()));
            values.add(value);
            newMap.put(key, Collections.unmodifiableSet(values));
            return new ThreadContextStruct(requestHeaders, newMap);
        }
    }
}
