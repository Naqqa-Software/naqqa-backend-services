package com.naqqa.elasticsearch.codec.fst;

import com.naqqa.elasticsearch.store.BytesDataOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class FSTBuilder {

    private final MutableState root = new MutableState();
    private byte[] lastTerm;
    private boolean built;

    private static final class MutableState {
        final TreeMap<Integer, MutableState> arcs = new TreeMap<>();
        boolean isFinal;
        long finalOutput;
    }

    private static final class Frozen {
        final boolean isFinal;
        final long finalOutput;
        final int[] labels;
        final long[] targetIds;

        Frozen(boolean isFinal, long finalOutput, int[] labels, long[] targetIds) {
            this.isFinal = isFinal;
            this.finalOutput = finalOutput;
            this.labels = labels;
            this.targetIds = targetIds;
        }
    }

    public void add(byte[] term, long output) {
        if (built) {
            throw new IllegalStateException("builder already finished");
        }
        if (lastTerm != null && compare(lastTerm, term) >= 0) {
            throw new IllegalArgumentException("terms must be added in strictly ascending order");
        }
        lastTerm = term.clone();
        MutableState state = root;
        for (byte b : term) {
            int label = b & 0xFF;
            state = state.arcs.computeIfAbsent(label, k -> new MutableState());
        }
        state.isFinal = true;
        state.finalOutput = output;
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

    public FST build() throws IOException {
        built = true;
        List<Frozen> frozenStates = new ArrayList<>();
        Map<String, Long> signatureToId = new HashMap<>();
        long rootId = minimize(root, frozenStates, signatureToId);

        BytesDataOutput out = new BytesDataOutput(Math.max(64, frozenStates.size() * 8));
        long[] offsets = new long[frozenStates.size()];
        for (int id = 0; id < frozenStates.size(); id++) {
            offsets[id] = out.size();
            Frozen frozen = frozenStates.get(id);
            byte flags = (byte) (frozen.isFinal ? 1 : 0);
            out.writeByte(flags);
            if (frozen.isFinal) {
                out.writeVLong(frozen.finalOutput);
            }
            out.writeVInt(frozen.labels.length);
            for (int i = 0; i < frozen.labels.length; i++) {
                out.writeByte((byte) frozen.labels[i]);
                out.writeVLong(offsets[(int) frozen.targetIds[i]]);
            }
        }
        long rootOffset = frozenStates.isEmpty() ? 0 : offsets[(int) rootId];
        return new FST(out.toArrayCopy(), rootOffset);
    }

    private static long minimize(MutableState node, List<Frozen> frozenStates, Map<String, Long> signatureToId) {
        int n = node.arcs.size();
        int[] labels = new int[n];
        long[] targetIds = new long[n];
        int i = 0;
        for (Map.Entry<Integer, MutableState> entry : node.arcs.entrySet()) {
            labels[i] = entry.getKey();
            targetIds[i] = minimize(entry.getValue(), frozenStates, signatureToId);
            i++;
        }
        StringBuilder sig = new StringBuilder();
        sig.append(node.isFinal).append(':').append(node.finalOutput).append(':');
        for (int k = 0; k < n; k++) {
            sig.append(labels[k]).append('=').append(targetIds[k]).append(',');
        }
        String key = sig.toString();
        Long existing = signatureToId.get(key);
        if (existing != null) {
            return existing;
        }
        long id = frozenStates.size();
        frozenStates.add(new Frozen(node.isFinal, node.finalOutput, labels, targetIds));
        signatureToId.put(key, id);
        return id;
    }
}
