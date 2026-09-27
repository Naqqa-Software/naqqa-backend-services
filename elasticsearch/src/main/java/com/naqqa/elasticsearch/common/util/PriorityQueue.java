package com.naqqa.elasticsearch.common.util;

import java.util.Arrays;

public abstract class PriorityQueue<T> {

    private int size = 0;
    private final int maxSize;
    private final Object[] heap;

    protected PriorityQueue(int maxSize) {
        this(maxSize, true);
    }

    protected PriorityQueue(int maxSize, boolean prePopulate) {
        int heapSize;
        if (maxSize == 0) {
            heapSize = 2;
        } else {
            heapSize = maxSize + 1;
        }
        this.maxSize = maxSize;
        this.heap = new Object[heapSize];
        if (prePopulate) {
            for (int i = 1; i < heap.length; i++) {
                heap[i] = getSentinelObject();
            }
            size = maxSize;
        }
    }

    protected T getSentinelObject() {
        return null;
    }

    protected abstract boolean lessThan(T a, T b);

    @SuppressWarnings("unchecked")
    private T elem(int i) {
        return (T) heap[i];
    }

    private void setElem(int i, T v) {
        heap[i] = v;
    }

    public final T add(T element) {
        size++;
        setElem(size, element);
        upHeap(size);
        return elem(1);
    }

    public T insertWithOverflow(T element) {
        if (size < maxSize) {
            add(element);
            return null;
        } else if (size > 0 && !lessThan(element, elem(1))) {
            T ret = elem(1);
            setElem(1, element);
            downHeap(1);
            return ret;
        } else {
            return element;
        }
    }

    public final T top() {
        return size == 0 ? null : elem(1);
    }

    public final T pop() {
        if (size > 0) {
            T result = elem(1);
            setElem(1, elem(size));
            setElem(size, null);
            size--;
            if (size > 0) {
                downHeap(1);
            }
            return result;
        }
        return null;
    }

    public final T updateTop() {
        downHeap(1);
        return elem(1);
    }

    public final T updateTop(T newTop) {
        setElem(1, newTop);
        return updateTop();
    }

    public final int size() {
        return size;
    }

    public final void clear() {
        for (int i = 0; i <= size; i++) {
            setElem(i, null);
        }
        size = 0;
    }

    public final boolean remove(T element) {
        for (int i = 1; i <= size; i++) {
            if (elem(i) == element) {
                setElem(i, elem(size));
                setElem(size, null);
                size--;
                if (i <= size) {
                    if (!upHeap(i)) {
                        downHeap(i);
                    }
                }
                return true;
            }
        }
        return false;
    }

    private boolean upHeap(int origPos) {
        int i = origPos;
        T node = elem(i);
        int j = i >>> 1;
        while (j > 0 && lessThan(node, elem(j))) {
            setElem(i, elem(j));
            i = j;
            j = j >>> 1;
        }
        setElem(i, node);
        return i != origPos;
    }

    private void downHeap(int i) {
        T node = elem(i);
        int j = i << 1;
        int k = j + 1;
        if (k <= size && lessThan(elem(k), elem(j))) {
            j = k;
        }
        while (j <= size && lessThan(elem(j), node)) {
            setElem(i, elem(j));
            i = j;
            j = i << 1;
            k = j + 1;
            if (k <= size && lessThan(elem(k), elem(j))) {
                j = k;
            }
        }
        setElem(i, node);
    }

    @SuppressWarnings("unchecked")
    public T[] drainToArrayHighestFirst(T[] target) {
        int n = size;
        T[] result = target.length >= n ? target : Arrays.copyOf(target, n);
        for (int i = n - 1; i >= 0; i--) {
            result[i] = pop();
        }
        return result;
    }
}
