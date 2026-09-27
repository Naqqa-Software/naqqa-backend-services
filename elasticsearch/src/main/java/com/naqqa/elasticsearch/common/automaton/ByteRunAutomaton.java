package com.naqqa.elasticsearch.common.automaton;

public final class ByteRunAutomaton extends RunAutomaton {

    public ByteRunAutomaton(Automaton a) {
        this(a, false, Operations.DEFAULT_MAX_DETERMINIZED_STATES);
    }

    public ByteRunAutomaton(Automaton a, boolean isBinary, int maxDeterminizedStates) {
        super(isBinary ? a : new UTF32ToUTF8().convert(a, maxDeterminizedStates), 256, maxDeterminizedStates);
    }

    public boolean run(byte[] bytes) {
        return run(bytes, 0, bytes.length);
    }

    public boolean run(byte[] bytes, int offset, int length) {
        int p = 0;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            p = step(p, bytes[i] & 0xFF);
            if (p == -1) {
                return false;
            }
        }
        return accept[p];
    }
}
