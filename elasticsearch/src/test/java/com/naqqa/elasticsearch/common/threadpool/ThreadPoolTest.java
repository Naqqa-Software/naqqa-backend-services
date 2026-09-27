package com.naqqa.elasticsearch.common.threadpool;

import com.naqqa.elasticsearch.common.exception.EsRejectedExecutionException;
import com.naqqa.elasticsearch.test.Assert;
import com.naqqa.elasticsearch.test.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadPoolTest {

    @Test
    public void testExecuteOnNamedPool() throws Exception {
        ThreadPool pool = new ThreadPool();
        try {
            AtomicInteger counter = new AtomicInteger();
            CountDownLatch latch = new CountDownLatch(1);
            pool.executor(ThreadPool.GENERIC).execute(() -> {
                counter.incrementAndGet();
                latch.countDown();
            });
            Assert.assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
            Assert.assertEquals(1, counter.get());
        } finally {
            pool.close();
        }
    }

    @Test
    public void testRejectionMapsToEsRejectedExecutionException() {
        ThreadPool pool = new ThreadPool();
        try {
            CountDownLatch block = new CountDownLatch(1);
            for (int i = 0; i < 50; i++) {
                try {
                    pool.executor(ThreadPool.FORCE_MERGE).execute(() -> {
                        try {
                            block.await();
                        } catch (InterruptedException ignored) {
                        }
                    });
                } catch (EsRejectedExecutionException expected) {
                    Assert.assertEquals(429, expected.status().getStatus());
                    block.countDown();
                    return;
                }
            }
            Assert.fail("expected rejection did not occur");
        } finally {
            pool.close();
        }
    }

    @Test
    public void testThreadContextPropagatesAcrossPool() throws Exception {
        ThreadPool pool = new ThreadPool();
        try {
            pool.getThreadContext().putHeader("request-id", "req-42");
            java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            pool.executor(ThreadPool.SEARCH).execute(() -> {
                seen.set(pool.getThreadContext().getHeader("request-id"));
                latch.countDown();
            });
            Assert.assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
            Assert.assertEquals("req-42", seen.get());
        } finally {
            pool.close();
        }
    }

    @Test
    public void testStatsSnapshot() {
        ThreadPool pool = new ThreadPool();
        try {
            ThreadPool.Stats stats = pool.stats();
            Assert.assertNotNull(stats.get(ThreadPool.SEARCH));
            Assert.assertNotNull(stats.get(ThreadPool.WRITE));
        } finally {
            pool.close();
        }
    }
}
