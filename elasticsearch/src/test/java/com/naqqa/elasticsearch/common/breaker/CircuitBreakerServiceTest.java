package com.naqqa.elasticsearch.common.breaker;

import com.naqqa.elasticsearch.common.exception.CircuitBreakingException;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public class CircuitBreakerServiceTest {

    @Test
    public void testNamedBreakersRegistered() {
        CircuitBreakerService service = new CircuitBreakerService(100_000);
        Assert.assertNotNull(service.getBreaker(CircuitBreaker.PARENT));
        Assert.assertNotNull(service.getBreaker(CircuitBreaker.REQUEST));
        Assert.assertNotNull(service.getBreaker(CircuitBreaker.FIELDDATA));
        Assert.assertNotNull(service.getBreaker(CircuitBreaker.IN_FLIGHT_REQUESTS));
        Assert.assertNotNull(service.getBreaker(CircuitBreaker.ACCOUNTING));
        Assert.assertThrows(IllegalArgumentException.class, () -> service.getBreaker("nope"));
    }

    @Test
    public void testRequestBreakerTripsWhenExceedingLimit() {
        CircuitBreakerService service = new CircuitBreakerService(1000);
        CircuitBreaker request = service.getBreaker(CircuitBreaker.REQUEST);
        request.addEstimateBytesAndMaybeBreak(100, "test");
        Assert.assertEquals(100L, request.getUsed());
        Assert.assertThrows(CircuitBreakingException.class, () -> request.addEstimateBytesAndMaybeBreak(100_000, "test"));
        Assert.assertTrue(request.getTrippedCount() >= 1);
    }
}
