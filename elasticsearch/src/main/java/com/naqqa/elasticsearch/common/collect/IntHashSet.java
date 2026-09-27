package com.naqqa.elasticsearch.common.collect;

import java.util.NoSuchElementException;

public final class IntHashSet {

    private int[] keys;
    private boolean[] used;
    private int size;
    private int mask;

    public IntHashSet() {
        this(16);
    }

    public IntHashSet(int expectedElements) {
        int capacity = tableSizeFor(expectedElements);
        keys = new int[capacity];
        used = new boolean[capacity];
        mask = capacity - 1;
    }

    private static int tableSizeFor(int expected) {
        int cap = 8;
        while (cap < expected * 2) {
            cap <<= 1;
        }
        return cap;
    }

    private static int hash(int key) {
        int h = key * 0x9E3779B1;
        return h ^ (h >>> 16);
    }

    public boolean add(int key) {
        int idx = indexOf(key);
        if (idx >= 0) {
            return false;
        }
        if ((size + 1) > keys.length * 0.7) {
            resize(keys.length * 2);
        }
        int slot = hash(key) & mask;
        while (used[slot]) {
            slot = (slot + 1) & mask;
        }
        keys[slot] = key;
        used[slot] = true;
        size++;
        return true;
    }

    public boolean contains(int key) {
        return indexOf(key) >= 0;
    }

    public boolean remove(int key) {
        int slot = hash(key) & mask;
        while (used[slot]) {
            if (keys[slot] == key) {
                used[slot] = false;
                size--;
                slot = (slot + 1) & mask;
                while (used[slot]) {
                    int relocKey = keys[slot];
                    used[slot] = false;
                    size--;
                    int insertSlot = hash(relocKey) & mask;
                    while (used[insertSlot]) {
                        insertSlot = (insertSlot + 1) & mask;
                    }
                    keys[insertSlot] = relocKey;
                    used[insertSlot] = true;
                    size++;
                    slot = (slot + 1) & mask;
                }
                return true;
            }
            slot = (slot + 1) & mask;
        }
        return false;
    }

    private int indexOf(int key) {
        int slot = hash(key) & mask;
        while (used[slot]) {
            if (keys[slot] == key) {
                return slot;
            }
            slot = (slot + 1) & mask;
        }
        return -1;
    }

    private void resize(int newCapacity) {
        int[] oldKeys = keys;
        boolean[] oldUsed = used;
        keys = new int[newCapacity];
        used = new boolean[newCapacity];
        mask = newCapacity - 1;
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldUsed[i]) {
                add(oldKeys[i]);
            }
        }
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int[] toArray() {
        int[] result = new int[size];
        int i = 0;
        for (int slot = 0; slot < keys.length; slot++) {
            if (used[slot]) {
                result[i++] = keys[slot];
            }
        }
        return result;
    }
}
