package com.naqqa.elasticsearch.codec.fst;

import com.naqqa.elasticsearch.store.BytesDataOutput;

import java.io.IOException;
import java.util.Arrays;

public final class FSTBuilder {

    private static final byte[] EMPTY = new byte[0];
    private static final int INITIAL_ARC_CAPACITY = 4;
    private static final int INITIAL_TABLE_SIZE = 1 << 4;
    private static final int INITIAL_STATE_CAPACITY = 1 << 4;
    private static final int INITIAL_ARC_STORE_CAPACITY = 1 << 5;

    private final BytesDataOutput out = new BytesDataOutput(64);

    private boolean[] stateFinal = new boolean[INITIAL_STATE_CAPACITY];
    private long[] stateFinalOutput = new long[INITIAL_STATE_CAPACITY];
    private int[] stateArcStart = new int[INITIAL_STATE_CAPACITY];
    private int[] stateArcCount = new int[INITIAL_STATE_CAPACITY];
    private long[] stateOffset = new long[INITIAL_STATE_CAPACITY];
    private int numStates;

    private int[] arcLabels = new int[INITIAL_ARC_STORE_CAPACITY];
    private long[] arcTargets = new long[INITIAL_ARC_STORE_CAPACITY];
    private int numArcsStored;

    private int[] table = newTable(INITIAL_TABLE_SIZE);
    private int tableCount;

    private MutableState[] path = new MutableState[16];
    private byte[] lastTerm = EMPTY;
    private boolean hasTerms;
    private boolean built;

    public FSTBuilder() {
        path[0] = new MutableState();
    }

    private static final class MutableState {
        int[] labels = new int[INITIAL_ARC_CAPACITY];
        long[] targets = new long[INITIAL_ARC_CAPACITY];
        int count;
        boolean isFinal;
        long finalOutput;
        boolean carriesOutput;

        void reset() {
            count = 0;
            isFinal = false;
            finalOutput = 0L;
            carriesOutput = false;
        }

        boolean shareable() {
            return !carriesOutput && !(isFinal && finalOutput != 0L);
        }

        void addArc(int label, long target) {
            if (count == labels.length) {
                int newCap = labels.length * 2;
                labels = Arrays.copyOf(labels, newCap);
                targets = Arrays.copyOf(targets, newCap);
            }
            labels[count] = label;
            targets[count] = target;
            count++;
        }

        void setLastTarget(long target) {
            targets[count - 1] = target;
        }
    }

    public void add(byte[] term, long output) throws IOException {
        if (built) {
            throw new IllegalStateException("builder already finished");
        }
        if (hasTerms && compare(lastTerm, term) >= 0) {
            throw new IllegalArgumentException("terms must be added in strictly ascending order");
        }
        int cpl = hasTerms ? commonPrefixLength(lastTerm, term) : 0;
        freezeTail(cpl);
        ensurePathCapacity(term.length);
        for (int j = cpl; j < term.length; j++) {
            int label = term[j] & 0xFF;
            path[j].addArc(label, -1L);
            if (path[j + 1] == null) {
                path[j + 1] = new MutableState();
            } else {
                path[j + 1].reset();
            }
        }
        path[term.length].isFinal = true;
        path[term.length].finalOutput = output;
        lastTerm = term.clone();
        hasTerms = true;
    }

    public int frozenStateCount() {
        return tableCount;
    }

    public int outputSizeInBytes() {
        return out.size();
    }

    public FST build() throws IOException {
        if (built) {
            throw new IllegalStateException("builder already finished");
        }
        built = true;
        freezeTail(0);
        long rootOffset = freeze(path[0]);
        return new FST(out.toArrayCopy(), rootOffset);
    }

    private void freezeTail(int cpl) throws IOException {
        for (int i = lastTerm.length; i > cpl; i--) {
            MutableState child = path[i];
            long offset = freeze(child);
            path[i - 1].setLastTarget(offset);
            if (!child.shareable()) {
                path[i - 1].carriesOutput = true;
            }
        }
    }

    private long freeze(MutableState state) throws IOException {
        if (!state.shareable()) {
            return writeState(state);
        }
        int mask = table.length - 1;
        int idx = hashOfMutable(state) & mask;
        while (true) {
            int id = table[idx];
            if (id < 0) {
                int newId = internState(state);
                long offset = writeState(state);
                stateOffset[newId] = offset;
                table[idx] = newId;
                tableCount++;
                if (tableCount * 10L > table.length * 7L) {
                    growTable();
                }
                return offset;
            }
            if (equalsStored(id, state)) {
                return stateOffset[id];
            }
            idx = (idx + 1) & mask;
        }
    }

    private int internState(MutableState state) {
        if (numStates == stateFinal.length) {
            int newCap = stateFinal.length * 2;
            stateFinal = Arrays.copyOf(stateFinal, newCap);
            stateFinalOutput = Arrays.copyOf(stateFinalOutput, newCap);
            stateArcStart = Arrays.copyOf(stateArcStart, newCap);
            stateArcCount = Arrays.copyOf(stateArcCount, newCap);
            stateOffset = Arrays.copyOf(stateOffset, newCap);
        }
        int id = numStates++;
        stateFinal[id] = state.isFinal;
        stateFinalOutput[id] = state.finalOutput;
        int start = numArcsStored;
        ensureArcStoreCapacity(start + state.count);
        System.arraycopy(state.labels, 0, arcLabels, start, state.count);
        System.arraycopy(state.targets, 0, arcTargets, start, state.count);
        numArcsStored += state.count;
        stateArcStart[id] = start;
        stateArcCount[id] = state.count;
        return id;
    }

    private void ensureArcStoreCapacity(int minSize) {
        if (minSize > arcLabels.length) {
            int newCap = Math.max(minSize, arcLabels.length * 2);
            arcLabels = Arrays.copyOf(arcLabels, newCap);
            arcTargets = Arrays.copyOf(arcTargets, newCap);
        }
    }

    private long writeState(MutableState state) throws IOException {
        long offset = out.size();
        byte flags = (byte) (state.isFinal ? 1 : 0);
        out.writeByte(flags);
        if (state.isFinal) {
            out.writeVLong(state.finalOutput);
        }
        out.writeVInt(state.count);
        for (int i = 0; i < state.count; i++) {
            out.writeByte((byte) state.labels[i]);
            out.writeVLong(state.targets[i]);
        }
        return offset;
    }

    private boolean equalsStored(int id, MutableState state) {
        if (stateFinal[id] != state.isFinal) {
            return false;
        }
        if (state.isFinal && stateFinalOutput[id] != state.finalOutput) {
            return false;
        }
        int count = stateArcCount[id];
        if (count != state.count) {
            return false;
        }
        int start = stateArcStart[id];
        for (int i = 0; i < count; i++) {
            if (arcLabels[start + i] != state.labels[i] || arcTargets[start + i] != state.targets[i]) {
                return false;
            }
        }
        return true;
    }

    private int hashOfStored(int id) {
        int h = stateFinal[id] ? 1 : 0;
        long fo = stateFinalOutput[id];
        h = h * 31 + (int) (fo ^ (fo >>> 32));
        int start = stateArcStart[id];
        int count = stateArcCount[id];
        for (int i = 0; i < count; i++) {
            h = h * 31 + arcLabels[start + i];
            long t = arcTargets[start + i];
            h = h * 31 + (int) (t ^ (t >>> 32));
        }
        return mix(h);
    }

    private static int hashOfMutable(MutableState s) {
        int h = s.isFinal ? 1 : 0;
        h = h * 31 + (int) (s.finalOutput ^ (s.finalOutput >>> 32));
        for (int i = 0; i < s.count; i++) {
            h = h * 31 + s.labels[i];
            h = h * 31 + (int) (s.targets[i] ^ (s.targets[i] >>> 32));
        }
        return mix(h);
    }

    private static int mix(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h & 0x7FFFFFFF;
    }

    private void growTable() {
        int[] oldTable = table;
        table = newTable(oldTable.length * 2);
        int mask = table.length - 1;
        for (int id : oldTable) {
            if (id < 0) {
                continue;
            }
            int idx = hashOfStored(id) & mask;
            while (table[idx] != -1) {
                idx = (idx + 1) & mask;
            }
            table[idx] = id;
        }
    }

    private static int[] newTable(int size) {
        int[] t = new int[size];
        Arrays.fill(t, -1);
        return t;
    }

    private void ensurePathCapacity(int minLen) {
        if (minLen >= path.length) {
            int newLen = Math.max(minLen + 1, path.length * 2);
            path = Arrays.copyOf(path, newLen);
        }
    }

    private static int compare(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            int ai = a[i] & 0xFF;
            int bi = b[i] & 0xFF;
            if (ai != bi) {
                return ai - bi;
            }
        }
        return a.length - b.length;
    }

    private static int commonPrefixLength(byte[] a, byte[] b) {
        int n = Math.min(a.length, b.length);
        int i = 0;
        while (i < n && a[i] == b[i]) {
            i++;
        }
        return i;
    }
}
