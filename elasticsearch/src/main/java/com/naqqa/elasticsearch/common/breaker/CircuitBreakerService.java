package com.naqqa.elasticsearch.common.breaker;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CircuitBreakerService {

    private final Map<String, CircuitBreaker> breakers = new LinkedHashMap<>();
    private volatile long parentLimit;

    public CircuitBreakerService(long parentLimitBytes) {
        this.parentLimit = parentLimitBytes;
        breakers.put(
            CircuitBreaker.PARENT,
            new ChildMemoryCircuitBreaker(CircuitBreaker.PARENT, parentLimitBytes, 1.0, CircuitBreaker.Durability.PERMANENT)
        );
        registerChild(CircuitBreaker.REQUEST, (long) (parentLimitBytes * 0.6), 1.0, CircuitBreaker.Durability.TRANSIENT);
        registerChild(CircuitBreaker.FIELDDATA, (long) (parentLimitBytes * 0.4), 1.03, CircuitBreaker.Durability.PERMANENT);
        registerChild(CircuitBreaker.IN_FLIGHT_REQUESTS, parentLimitBytes, 1.0, CircuitBreaker.Durability.TRANSIENT);
        registerChild(CircuitBreaker.ACCOUNTING, parentLimitBytes, 1.0, CircuitBreaker.Durability.PERMANENT);
    }

    private void registerChild(String name, long limit, double overhead, CircuitBreaker.Durability durability) {
        breakers.put(
            name,
            new ChildMemoryCircuitBreaker(name, limit, overhead, durability, this::childrenUsed, parentLimit)
        );
    }

    private long childrenUsed() {
        long sum = 0;
        for (Map.Entry<String, CircuitBreaker> e : breakers.entrySet()) {
            if (!e.getKey().equals(CircuitBreaker.PARENT)) {
                sum += e.getValue().getUsed();
            }
        }
        return sum;
    }

    public CircuitBreaker getBreaker(String name) {
        CircuitBreaker breaker = breakers.get(name);
        if (breaker == null) {
            throw new IllegalArgumentException("No such circuit breaker: " + name);
        }
        return breaker;
    }

    public long getParentLimit() {
        return parentLimit;
    }

    public Map<String, CircuitBreaker.Durability> allBreakerNames() {
        Map<String, CircuitBreaker.Durability> result = new LinkedHashMap<>();
        for (Map.Entry<String, CircuitBreaker> e : breakers.entrySet()) {
            result.put(e.getKey(), e.getValue().getDurability());
        }
        return result;
    }
}
