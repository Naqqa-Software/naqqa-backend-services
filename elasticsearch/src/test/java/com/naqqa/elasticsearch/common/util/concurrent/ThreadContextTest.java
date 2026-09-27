package com.naqqa.elasticsearch.common.util.concurrent;

import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

public class ThreadContextTest {

    @Test
    public void testHeadersPropagateAndStash() {
        ThreadContext ctx = new ThreadContext();
        ctx.putHeader("request-id", "abc");
        Assert.assertEquals("abc", ctx.getHeader("request-id"));

        try (ThreadContext.StoredContext stored = ctx.stashContext()) {
            Assert.assertNull(ctx.getHeader("request-id"));
            ctx.putHeader("request-id", "inner");
            Assert.assertEquals("inner", ctx.getHeader("request-id"));
        }
        Assert.assertEquals("abc", ctx.getHeader("request-id"));
    }

    @Test
    public void testResponseHeadersAccumulate() {
        ThreadContext ctx = new ThreadContext();
        ctx.addResponseHeader("Warning", "first");
        ctx.addResponseHeader("Warning", "second");
        Assert.assertEquals(2, ctx.getResponseHeaders("Warning").size());
    }

    @Test
    public void testPreserveContextAcrossThreads() throws Exception {
        ThreadContext ctx = new ThreadContext();
        ctx.putHeader("trace", "xyz");
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable wrapped = ctx.preserveContext(() -> {
            try {
                Assert.assertEquals("xyz", ctx.getHeader("trace"));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        Thread t = new Thread(wrapped);
        t.start();
        t.join();
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
    }
}
