package com.naqqa.elasticsearch.script.painless;

import java.util.HashMap;
import java.util.Map;

public final class Environment {

    private final Environment parent;
    private final Map<String, Object> values = new HashMap<>();
    private final Map<String, String> types = new HashMap<>();

    public Environment(Environment parent) {
        this.parent = parent;
    }

    public Environment child() {
        return new Environment(this);
    }

    public void define(String name, String type, Object value) {
        values.put(name, coerceForType(type, value));
        types.put(name, type == null ? "def" : type);
    }

    private Object coerceForType(String type, Object value) {
        if (type == null || type.equals("def") || value == null) {
            return value;
        }
        return PainlessOps.castTo(type, value);
    }

    public boolean has(String name) {
        Environment env = this;
        while (env != null) {
            if (env.values.containsKey(name)) {
                return true;
            }
            env = env.parent;
        }
        return false;
    }

    public Object get(String name) {
        Environment env = this;
        while (env != null) {
            if (env.values.containsKey(name)) {
                return env.values.get(name);
            }
            env = env.parent;
        }
        throw new PainlessRuntimeError("Variable [" + name + "] is not defined.");
    }

    public String typeOf(String name) {
        Environment env = this;
        while (env != null) {
            if (env.values.containsKey(name)) {
                return env.types.get(name);
            }
            env = env.parent;
        }
        return "def";
    }

    public void assign(String name, Object value) {
        Environment env = this;
        while (env != null) {
            if (env.values.containsKey(name)) {
                env.values.put(name, env.coerceForType(env.types.get(name), value));
                return;
            }
            env = env.parent;
        }
        throw new PainlessRuntimeError("Variable [" + name + "] is not defined.");
    }
}
