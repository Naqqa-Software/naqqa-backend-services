package com.naqqa.elasticsearch.script;

import java.util.LinkedHashMap;
import java.util.Map;

public class ScriptCircuitBreakingException extends RuntimeException {

    private final String durability;

    public ScriptCircuitBreakingException(String message, String durability) {
        super(message);
        this.durability = durability;
    }

    public String durability() {
        return durability;
    }

    public int status() {
        return 429;
    }

    public String type() {
        return "circuit_breaking_exception";
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type());
        m.put("reason", getMessage());
        m.put("bytes_wanted", 0);
        m.put("bytes_limit", 0);
        m.put("durability", durability);
        return m;
    }
}
