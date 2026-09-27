package com.naqqa.elasticsearch.codec.fst;

import java.util.ArrayDeque;
import java.util.Deque;

public final class FSTEnum {

    private final FST fst;
    private final Deque<Frame> stack = new ArrayDeque<>();
    private final ByteList path = new ByteList();
    private byte[] currentTerm;
    private long currentOutput;
    private boolean started;

    private static final class Frame {
        final FST.State state;
        int arcPos;
        boolean finalPending;

        Frame(FST.State state, int arcPos, boolean finalPending) {
            this.state = state;
            this.arcPos = arcPos;
            this.finalPending = finalPending;
        }
    }

    private static final class ByteList {
        byte[] bytes = new byte[16];
        int size;

        void add(int b) {
            if (size == bytes.length) {
                byte[] grown = new byte[bytes.length * 2];
                System.arraycopy(bytes, 0, grown, 0, size);
                bytes = grown;
            }
            bytes[size++] = (byte) b;
        }

        void removeLast() {
            size--;
        }

        void clear() {
            size = 0;
        }

        boolean isEmpty() {
            return size == 0;
        }

        byte[] toArray() {
            byte[] result = new byte[size];
            System.arraycopy(bytes, 0, result, 0, size);
            return result;
        }
    }

    FSTEnum(FST fst) {
        this.fst = fst;
    }

    public byte[] term() {
        return currentTerm;
    }

    public long output() {
        return currentOutput;
    }

    public boolean next() {
        if (!started) {
            started = true;
            if (fst.isEmpty()) {
                return false;
            }
            FST.State root = fst.root();
            stack.push(new Frame(root, 0, root.isFinal));
        }
        return advance();
    }

    public boolean seekCeil(byte[] target) {
        started = true;
        stack.clear();
        path.clear();
        if (fst.isEmpty()) {
            return false;
        }
        FST.State state = fst.root();
        for (int pos = 0; pos < target.length; pos++) {
            int label = target[pos] & 0xFF;
            int idx = state.findArc(label);
            if (idx >= 0) {
                stack.push(new Frame(state, idx + 1, false));
                path.add(label);
                state = fst.readState(state.arcs[idx].target);
            } else {
                int insertion = -(idx + 1);
                if (insertion < state.arcs.length) {
                    stack.push(new Frame(state, insertion + 1, false));
                    FST.Arc arc = state.arcs[insertion];
                    path.add(arc.label);
                    FST.State child = fst.readState(arc.target);
                    stack.push(new Frame(child, 0, child.isFinal));
                    return advance();
                }
                stack.push(new Frame(state, state.arcs.length, false));
                return retreatThenAdvance();
            }
        }
        stack.push(new Frame(state, 0, state.isFinal));
        return advance();
    }

    public boolean seekExact(byte[] target) {
        started = true;
        stack.clear();
        path.clear();
        if (fst.isEmpty()) {
            return false;
        }
        FST.State state = fst.root();
        for (int pos = 0; pos < target.length; pos++) {
            int label = target[pos] & 0xFF;
            int idx = state.findArc(label);
            if (idx < 0) {
                return false;
            }
            stack.push(new Frame(state, idx + 1, false));
            path.add(label);
            state = fst.readState(state.arcs[idx].target);
        }
        if (!state.isFinal) {
            return false;
        }
        currentTerm = path.toArray();
        currentOutput = state.finalOutput;
        stack.push(new Frame(state, 0, false));
        return true;
    }

    private boolean retreatThenAdvance() {
        while (!stack.isEmpty()) {
            Frame top = stack.peek();
            if (top.arcPos < top.state.arcs.length) {
                return advance();
            }
            stack.pop();
            if (!path.isEmpty()) {
                path.removeLast();
            }
        }
        return false;
    }

    private boolean advance() {
        while (!stack.isEmpty()) {
            Frame top = stack.peek();
            if (top.finalPending) {
                top.finalPending = false;
                currentTerm = path.toArray();
                currentOutput = top.state.finalOutput;
                return true;
            }
            if (top.arcPos < top.state.arcs.length) {
                FST.Arc arc = top.state.arcs[top.arcPos++];
                FST.State child = fst.readState(arc.target);
                path.add(arc.label);
                stack.push(new Frame(child, 0, child.isFinal));
                continue;
            }
            stack.pop();
            if (!path.isEmpty()) {
                path.removeLast();
            }
        }
        currentTerm = null;
        return false;
    }
}
