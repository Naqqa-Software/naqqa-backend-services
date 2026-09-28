package com.naqqa.elasticsearch.index.engine;

import com.naqqa.elasticsearch.common.settings.Settings;
import com.naqqa.elasticsearch.common.unit.ByteSizeValue;
import com.naqqa.elasticsearch.common.unit.RatioValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class IndexingMemoryController {

    public static final String SETTING_INDEX_BUFFER_SIZE = "indices.memory.index_buffer_size";
    public static final long MIN_INDEX_BUFFER_BYTES = ByteSizeValue.ofMb(48).getBytes();
    private static final long MAX_THROTTLE_WAIT_MILLIS = 2000L;
    private static final long THROTTLE_STEP_MILLIS = 20L;

    public interface Accountable {
        long ramBytesUsed();

        void writeIndexingBufferToSegment() throws IOException;
    }

    private static volatile IndexingMemoryController instance = new IndexingMemoryController(resolveBudgetBytes(Settings.EMPTY));

    private final long budgetBytes;
    private final Set<Accountable> engines = ConcurrentHashMap.newKeySet();
    private final Object reclaimLock = new Object();

    public IndexingMemoryController(long budgetBytes) {
        this.budgetBytes = budgetBytes;
    }

    public static IndexingMemoryController instance() {
        return instance;
    }

    public static void initialize(Settings settings) {
        instance = new IndexingMemoryController(resolveBudgetBytes(settings));
    }

    public static long resolveBudgetBytes(Settings settings) {
        String raw = settings == null ? null : settings.get(SETTING_INDEX_BUFFER_SIZE);
        long heapMax = Runtime.getRuntime().maxMemory();
        long budget;
        if (raw == null || raw.isBlank()) {
            budget = (long) (heapMax * 0.10);
        } else if (raw.trim().endsWith("%")) {
            RatioValue ratio = RatioValue.parseRatioValue(raw.trim());
            budget = (long) (heapMax * ratio.getAsRatio());
        } else {
            budget = ByteSizeValue.parseBytesSizeValue(raw, SETTING_INDEX_BUFFER_SIZE).getBytes();
        }
        return Math.max(budget, MIN_INDEX_BUFFER_BYTES);
    }

    public long budgetBytes() {
        return budgetBytes;
    }

    public void register(Accountable engine) {
        engines.add(engine);
    }

    public void unregister(Accountable engine) {
        engines.remove(engine);
    }

    public int registeredCount() {
        return engines.size();
    }

    public long totalBytesUsed() {
        long sum = 0L;
        for (Accountable e : engines) {
            sum += e.ramBytesUsed();
        }
        return sum;
    }

    public long shareBytes() {
        int n = Math.max(1, engines.size());
        return budgetBytes / n;
    }

    public void afterBytesChanged(Accountable engine) {
        if (engine.ramBytesUsed() > shareBytes()) {
            flushOne(engine);
        }
        if (totalBytesUsed() <= budgetBytes) {
            return;
        }
        synchronized (reclaimLock) {
            if (totalBytesUsed() <= budgetBytes) {
                return;
            }
            List<Accountable> sorted = engines.stream()
                .sorted(Comparator.comparingLong(Accountable::ramBytesUsed).reversed())
                .toList();
            for (Accountable e : sorted) {
                if (totalBytesUsed() <= budgetBytes) {
                    break;
                }
                flushOne(e);
            }
        }
        throttleIfStillOverBudget();
    }

    private void flushOne(Accountable e) {
        try {
            e.writeIndexingBufferToSegment();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private void throttleIfStillOverBudget() {
        long waited = 0L;
        while (totalBytesUsed() > budgetBytes && waited < MAX_THROTTLE_WAIT_MILLIS) {
            try {
                Thread.sleep(THROTTLE_STEP_MILLIS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            waited += THROTTLE_STEP_MILLIS;
        }
    }
}
