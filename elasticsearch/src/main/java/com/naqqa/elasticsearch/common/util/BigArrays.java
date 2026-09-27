package com.naqqa.elasticsearch.common.util;

import com.naqqa.elasticsearch.common.breaker.CircuitBreaker;

public final class BigArrays {

    private final PageCacheRecycler recycler;
    private final CircuitBreaker breaker;
    private final String label;

    public BigArrays(PageCacheRecycler recycler, CircuitBreaker breaker, String label) {
        this.recycler = recycler;
        this.breaker = breaker;
        this.label = label;
    }

    private void account(long bytes) {
        if (breaker != null) {
            breaker.addEstimateBytesAndMaybeBreak(bytes, label);
        }
    }

    private void release(long bytes) {
        if (breaker != null) {
            breaker.addWithoutBreaking(-bytes);
        }
    }

    public ByteArray newByteArray(long size) {
        account(size);
        int pageSize = PageCacheRecycler.BYTE_PAGE_SIZE;
        int numPages = (int) ((size + pageSize - 1) / pageSize);
        byte[][] pages = new byte[Math.max(numPages, 1)][];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = recycler != null ? recycler.acquireBytePage() : new byte[pageSize];
        }
        return new PagedByteArray(pages, size, this);
    }

    public IntArray newIntArray(long size) {
        account(size * Integer.BYTES);
        int pageSize = PageCacheRecycler.INT_PAGE_SIZE;
        int numPages = (int) ((size + pageSize - 1) / pageSize);
        int[][] pages = new int[Math.max(numPages, 1)][];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = recycler != null ? recycler.acquireIntPage() : new int[pageSize];
        }
        return new PagedIntArray(pages, size, this);
    }

    public LongArray newLongArray(long size) {
        account(size * Long.BYTES);
        int pageSize = PageCacheRecycler.LONG_PAGE_SIZE;
        int numPages = (int) ((size + pageSize - 1) / pageSize);
        long[][] pages = new long[Math.max(numPages, 1)][];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = recycler != null ? recycler.acquireLongPage() : new long[pageSize];
        }
        return new PagedLongArray(pages, size, this);
    }

    public DoubleArray newDoubleArray(long size) {
        account(size * Double.BYTES);
        int pageSize = PageCacheRecycler.DOUBLE_PAGE_SIZE;
        int numPages = (int) ((size + pageSize - 1) / pageSize);
        double[][] pages = new double[Math.max(numPages, 1)][];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = recycler != null ? recycler.acquireDoublePage() : new double[pageSize];
        }
        return new PagedDoubleArray(pages, size, this);
    }

    public <T> ObjectArray<T> newObjectArray(long size) {
        account(size * 8);
        int pageSize = PageCacheRecycler.OBJECT_PAGE_SIZE;
        int numPages = (int) ((size + pageSize - 1) / pageSize);
        Object[][] pages = new Object[Math.max(numPages, 1)][];
        for (int i = 0; i < pages.length; i++) {
            pages[i] = recycler != null ? recycler.acquireObjectPage() : new Object[pageSize];
        }
        return new PagedObjectArray<>(pages, size, this);
    }

    private static final class PagedByteArray implements ByteArray {
        private byte[][] pages;
        private final long size;
        private final BigArrays owner;
        private boolean closed;

        PagedByteArray(byte[][] pages, long size, BigArrays owner) {
            this.pages = pages;
            this.size = size;
            this.owner = owner;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public byte get(long index) {
            int pageSize = PageCacheRecycler.BYTE_PAGE_SIZE;
            return pages[(int) (index / pageSize)][(int) (index % pageSize)];
        }

        @Override
        public byte set(long index, byte value) {
            int pageSize = PageCacheRecycler.BYTE_PAGE_SIZE;
            byte[] page = pages[(int) (index / pageSize)];
            int off = (int) (index % pageSize);
            byte old = page[off];
            page[off] = value;
            return old;
        }

        @Override
        public void fill(long fromIndex, long toIndex, byte value) {
            for (long i = fromIndex; i < toIndex; i++) {
                set(i, value);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            owner.release(size);
            if (owner.recycler != null) {
                for (byte[] p : pages) {
                    owner.recycler.releaseBytePage(p);
                }
            }
            pages = null;
        }
    }

    private static final class PagedIntArray implements IntArray {
        private int[][] pages;
        private final long size;
        private final BigArrays owner;
        private boolean closed;

        PagedIntArray(int[][] pages, long size, BigArrays owner) {
            this.pages = pages;
            this.size = size;
            this.owner = owner;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public int get(long index) {
            int pageSize = PageCacheRecycler.INT_PAGE_SIZE;
            return pages[(int) (index / pageSize)][(int) (index % pageSize)];
        }

        @Override
        public int set(long index, int value) {
            int pageSize = PageCacheRecycler.INT_PAGE_SIZE;
            int[] page = pages[(int) (index / pageSize)];
            int off = (int) (index % pageSize);
            int old = page[off];
            page[off] = value;
            return old;
        }

        @Override
        public void fill(long fromIndex, long toIndex, int value) {
            for (long i = fromIndex; i < toIndex; i++) {
                set(i, value);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            owner.release(size * Integer.BYTES);
            if (owner.recycler != null) {
                for (int[] p : pages) {
                    owner.recycler.releaseIntPage(p);
                }
            }
            pages = null;
        }
    }

    private static final class PagedLongArray implements LongArray {
        private long[][] pages;
        private final long size;
        private final BigArrays owner;
        private boolean closed;

        PagedLongArray(long[][] pages, long size, BigArrays owner) {
            this.pages = pages;
            this.size = size;
            this.owner = owner;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public long get(long index) {
            int pageSize = PageCacheRecycler.LONG_PAGE_SIZE;
            return pages[(int) (index / pageSize)][(int) (index % pageSize)];
        }

        @Override
        public long set(long index, long value) {
            int pageSize = PageCacheRecycler.LONG_PAGE_SIZE;
            long[] page = pages[(int) (index / pageSize)];
            int off = (int) (index % pageSize);
            long old = page[off];
            page[off] = value;
            return old;
        }

        @Override
        public void fill(long fromIndex, long toIndex, long value) {
            for (long i = fromIndex; i < toIndex; i++) {
                set(i, value);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            owner.release(size * Long.BYTES);
            if (owner.recycler != null) {
                for (long[] p : pages) {
                    owner.recycler.releaseLongPage(p);
                }
            }
            pages = null;
        }
    }

    private static final class PagedDoubleArray implements DoubleArray {
        private double[][] pages;
        private final long size;
        private final BigArrays owner;
        private boolean closed;

        PagedDoubleArray(double[][] pages, long size, BigArrays owner) {
            this.pages = pages;
            this.size = size;
            this.owner = owner;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public double get(long index) {
            int pageSize = PageCacheRecycler.DOUBLE_PAGE_SIZE;
            return pages[(int) (index / pageSize)][(int) (index % pageSize)];
        }

        @Override
        public double set(long index, double value) {
            int pageSize = PageCacheRecycler.DOUBLE_PAGE_SIZE;
            double[] page = pages[(int) (index / pageSize)];
            int off = (int) (index % pageSize);
            double old = page[off];
            page[off] = value;
            return old;
        }

        @Override
        public void fill(long fromIndex, long toIndex, double value) {
            for (long i = fromIndex; i < toIndex; i++) {
                set(i, value);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            owner.release(size * Double.BYTES);
            if (owner.recycler != null) {
                for (double[] p : pages) {
                    owner.recycler.releaseDoublePage(p);
                }
            }
            pages = null;
        }
    }

    @SuppressWarnings("unchecked")
    private static final class PagedObjectArray<T> implements ObjectArray<T> {
        private Object[][] pages;
        private final long size;
        private final BigArrays owner;
        private boolean closed;

        PagedObjectArray(Object[][] pages, long size, BigArrays owner) {
            this.pages = pages;
            this.size = size;
            this.owner = owner;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public T get(long index) {
            int pageSize = PageCacheRecycler.OBJECT_PAGE_SIZE;
            return (T) pages[(int) (index / pageSize)][(int) (index % pageSize)];
        }

        @Override
        public T set(long index, T value) {
            int pageSize = PageCacheRecycler.OBJECT_PAGE_SIZE;
            Object[] page = pages[(int) (index / pageSize)];
            int off = (int) (index % pageSize);
            T old = (T) page[off];
            page[off] = value;
            return old;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            owner.release(size * 8);
            if (owner.recycler != null) {
                for (Object[] p : pages) {
                    owner.recycler.releaseObjectPage(p);
                }
            }
            pages = null;
        }
    }
}
