package com.naqqa.elasticsearch.common.inject;

import com.naqqa.elasticsearch.common.lifecycle.LifecycleComponent;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Injector {

    private final Map<Class<?>, Object> instances = new LinkedHashMap<>();
    private final List<Class<?>> bindingOrder = new ArrayList<>();

    public <T> Injector bindInstance(Class<T> type, T instance) {
        instances.put(type, instance);
        if (!bindingOrder.contains(type)) {
            bindingOrder.add(type);
        }
        return this;
    }

    public <T> T getInstance(Class<T> type) {
        Object existing = instances.get(type);
        if (existing != null) {
            return type.cast(existing);
        }
        for (Object v : instances.values()) {
            if (type.isInstance(v)) {
                return type.cast(v);
            }
        }
        T created = create(type);
        instances.put(type, created);
        bindingOrder.add(type);
        return created;
    }

    private <T> T create(Class<T> type) {
        Constructor<?>[] ctors = type.getDeclaredConstructors();
        Constructor<?> chosen = ctors[0];
        for (Constructor<?> c : ctors) {
            if (c.getParameterCount() > chosen.getParameterCount()) {
                chosen = c;
            }
        }
        chosen.setAccessible(true);
        Class<?>[] paramTypes = chosen.getParameterTypes();
        Object[] args = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            args[i] = getInstance(paramTypes[i]);
        }
        try {
            return type.cast(chosen.newInstance(args));
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Failed to instantiate " + type, e);
        }
    }

    public void startAll() {
        for (Class<?> type : bindingOrder) {
            Object instance = instances.get(type);
            if (instance instanceof LifecycleComponent lc) {
                lc.start();
            }
        }
    }

    public void stopAll() {
        for (int i = bindingOrder.size() - 1; i >= 0; i--) {
            Object instance = instances.get(bindingOrder.get(i));
            if (instance instanceof LifecycleComponent lc) {
                lc.stop();
            }
        }
    }

    public void closeAll() {
        for (int i = bindingOrder.size() - 1; i >= 0; i--) {
            Object instance = instances.get(bindingOrder.get(i));
            if (instance instanceof LifecycleComponent lc) {
                lc.close();
            }
        }
    }
}
