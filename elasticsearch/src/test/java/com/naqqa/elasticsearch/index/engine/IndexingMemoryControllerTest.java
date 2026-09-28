package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.common.settings.Settings;
import com.naqqa.elasticsearch.test.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;
import static com.naqqa.elasticsearch.test.Assert.assertTrue;

public final class IndexingMemoryControllerTest {

    private static final class FakeAccountable implements IndexingMemoryController.Accountable {
        final AtomicLong bytes = new AtomicLong();
        final AtomicInteger flushCount = new AtomicInteger();

        @Override
        public long ramBytesUsed() {
            return bytes.get();
        }

        @Override
        public void writeIndexingBufferToSegment() {
            flushCount.incrementAndGet();
            bytes.set(0L);
        }
    }

    @Test
    public void resolvesDefaultBudgetAsTenPercentOfHeapWithFortyEightMbFloor() {
        long resolved = IndexingMemoryController.resolveBudgetBytes(Settings.EMPTY);
        long expected = Math.max((long) (Runtime.getRuntime().maxMemory() * 0.10), IndexingMemoryController.MIN_INDEX_BUFFER_BYTES);
        assertEquals(expected, resolved);
        assertTrue(resolved >= IndexingMemoryController.MIN_INDEX_BUFFER_BYTES, "resolved budget must respect the 48mb floor");
    }

    @Test
    public void resolvesPercentageSetting() {
        Settings settings = Settings.builder().put(IndexingMemoryController.SETTING_INDEX_BUFFER_SIZE, "50%").build();
        long resolved = IndexingMemoryController.resolveBudgetBytes(settings);
        long expected = Math.max((long) (Runtime.getRuntime().maxMemory() * 0.50), IndexingMemoryController.MIN_INDEX_BUFFER_BYTES);
        assertEquals(expected, resolved);
    }

    @Test
    public void resolvesByteSizeSetting() {
        Settings settings = Settings.builder().put(IndexingMemoryController.SETTING_INDEX_BUFFER_SIZE, "128mb").build();
        long resolved = IndexingMemoryController.resolveBudgetBytes(settings);
        assertEquals(128L * 1024 * 1024, resolved);
    }

    @Test
    public void clampsBelowMinimumToFortyEightMb() {
        Settings settings = Settings.builder().put(IndexingMemoryController.SETTING_INDEX_BUFFER_SIZE, "1mb").build();
        long resolved = IndexingMemoryController.resolveBudgetBytes(settings);
        assertEquals(IndexingMemoryController.MIN_INDEX_BUFFER_BYTES, resolved);
    }

    @Test
    public void flushesLargestEnginesUntilUnderBudget() throws IOException {
        IndexingMemoryController controller = new IndexingMemoryController(1_000_000L);
        FakeAccountable small = new FakeAccountable();
        FakeAccountable big = new FakeAccountable();
        controller.register(small);
        controller.register(big);
        small.bytes.set(100_000L);
        big.bytes.set(2_000_000L);

        controller.afterBytesChanged(big);

        assertEquals(0L, big.bytes.get());
        assertTrue(big.flushCount.get() >= 1, "the oversized engine should have been flushed");
        assertTrue(controller.totalBytesUsed() <= controller.budgetBytes(), "total should be under budget after reclaim");
    }

    @Test
    public void singleEngineExceedingItsShareIsFlushedEvenUnderTotalBudget() throws IOException {
        IndexingMemoryController controller = new IndexingMemoryController(10_000_000L);
        FakeAccountable a = new FakeAccountable();
        FakeAccountable b = new FakeAccountable();
        controller.register(a);
        controller.register(b);
        a.bytes.set(6_000_000L);

        controller.afterBytesChanged(a);

        assertEquals(0L, a.bytes.get());
        assertTrue(a.flushCount.get() >= 1, "engine exceeding its fair share should be flushed proactively");
    }
}
