package com.naqqa.elasticsearch.common.util;

import java.util.concurrent.ConcurrentLinkedQueue;

public final class PageCacheRecycler {

    public static final int PAGE_SIZE_IN_BYTES = 16 * 1024;
    public static final int BYTE_PAGE_SIZE = PAGE_SIZE_IN_BYTES;
    public static final int INT_PAGE_SIZE = PAGE_SIZE_IN_BYTES / Integer.BYTES;
    public static final int LONG_PAGE_SIZE = PAGE_SIZE_IN_BYTES / Long.BYTES;
    public static final int DOUBLE_PAGE_SIZE = PAGE_SIZE_IN_BYTES / Double.BYTES;
    public static final int OBJECT_PAGE_SIZE = PAGE_SIZE_IN_BYTES / 8;

    private final int maxPooledPages;
    private final ConcurrentLinkedQueue<byte[]> bytePages = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<int[]> intPages = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<long[]> longPages = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<double[]> doublePages = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Object[]> objectPages = new ConcurrentLinkedQueue<>();

    public PageCacheRecycler() {
        this(10_000);
    }

    public PageCacheRecycler(int maxPooledPages) {
        this.maxPooledPages = maxPooledPages;
    }

    public byte[] acquireBytePage() {
        byte[] page = bytePages.poll();
        return page != null ? page : new byte[BYTE_PAGE_SIZE];
    }

    public void releaseBytePage(byte[] page) {
        if (bytePages.size() < maxPooledPages) {
            java.util.Arrays.fill(page, (byte) 0);
            bytePages.offer(page);
        }
    }

    public int[] acquireIntPage() {
        int[] page = intPages.poll();
        return page != null ? page : new int[INT_PAGE_SIZE];
    }

    public void releaseIntPage(int[] page) {
        if (intPages.size() < maxPooledPages) {
            java.util.Arrays.fill(page, 0);
            intPages.offer(page);
        }
    }

    public long[] acquireLongPage() {
        long[] page = longPages.poll();
        return page != null ? page : new long[LONG_PAGE_SIZE];
    }

    public void releaseLongPage(long[] page) {
        if (longPages.size() < maxPooledPages) {
            java.util.Arrays.fill(page, 0L);
            longPages.offer(page);
        }
    }

    public double[] acquireDoublePage() {
        double[] page = doublePages.poll();
        return page != null ? page : new double[DOUBLE_PAGE_SIZE];
    }

    public void releaseDoublePage(double[] page) {
        if (doublePages.size() < maxPooledPages) {
            java.util.Arrays.fill(page, 0d);
            doublePages.offer(page);
        }
    }

    public Object[] acquireObjectPage() {
        Object[] page = objectPages.poll();
        return page != null ? page : new Object[OBJECT_PAGE_SIZE];
    }

    public void releaseObjectPage(Object[] page) {
        if (objectPages.size() < maxPooledPages) {
            java.util.Arrays.fill(page, null);
            objectPages.offer(page);
        }
    }
}
