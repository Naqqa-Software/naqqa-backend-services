package com.naqqa.elasticsearch.codec.fst;

import com.naqqa.elasticsearch.store.ByteArrayDataInput;
import com.naqqa.elasticsearch.store.DataInput;
import com.naqqa.elasticsearch.store.DataOutput;

import java.io.IOException;

public final class FST {

    final byte[] data;
    final long rootOffset;

    FST(byte[] data, long rootOffset) {
        this.data = data;
        this.rootOffset = rootOffset;
    }

    public static final class Arc {
        public final int label;
        public final long target;

        Arc(int label, long target) {
            this.label = label;
            this.target = target;
        }
    }

    public static final class State {
        public final boolean isFinal;
        public final long finalOutput;
        public final Arc[] arcs;

        State(boolean isFinal, long finalOutput, Arc[] arcs) {
            this.isFinal = isFinal;
            this.finalOutput = finalOutput;
            this.arcs = arcs;
        }

        public int findArc(int label) {
            int lo = 0;
            int hi = arcs.length - 1;
            while (lo <= hi) {
                int mid = (lo + hi) >>> 1;
                int cmp = Integer.compare(arcs[mid].label, label);
                if (cmp == 0) {
                    return mid;
                } else if (cmp < 0) {
                    lo = mid + 1;
                } else {
                    hi = mid - 1;
                }
            }
            return -(lo + 1);
        }
    }

    public State readState(long offset) {
        ByteArrayDataInput in = new ByteArrayDataInput(data, (int) offset, data.length - (int) offset);
        try {
            byte flags = in.readByte();
            boolean isFinal = (flags & 1) != 0;
            long finalOutput = isFinal ? in.readVLong() : 0L;
            int numArcs = in.readVInt();
            Arc[] arcs = new Arc[numArcs];
            for (int i = 0; i < numArcs; i++) {
                int label = in.readByte() & 0xFF;
                long target = in.readVLong();
                arcs[i] = new Arc(label, target);
            }
            return new State(isFinal, finalOutput, arcs);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public State root() {
        return readState(rootOffset);
    }

    public Long get(byte[] term) {
        State state = root();
        for (byte b : term) {
            int idx = state.findArc(b & 0xFF);
            if (idx < 0) {
                return null;
            }
            state = readState(state.arcs[idx].target);
        }
        return state.isFinal ? state.finalOutput : null;
    }

    public boolean isEmpty() {
        return data.length == 0;
    }

    public FSTEnum iterator() {
        return new FSTEnum(this);
    }

    public void save(DataOutput out) throws IOException {
        out.writeVInt(data.length);
        out.writeBytes(data, 0, data.length);
        out.writeVLong(rootOffset);
    }

    public static FST load(DataInput in) throws IOException {
        int length = in.readVInt();
        byte[] data = new byte[length];
        in.readBytes(data, 0, length);
        long rootOffset = in.readVLong();
        return new FST(data, rootOffset);
    }

    public int sizeInBytes() {
        return data.length;
    }

    @Override
    public String toString() {
        return "FST(bytes=" + data.length + ",root=" + rootOffset + ")";
    }
}
