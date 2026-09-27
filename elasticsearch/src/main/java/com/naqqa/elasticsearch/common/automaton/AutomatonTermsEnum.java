package com.naqqa.elasticsearch.common.automaton;

import java.util.Arrays;

public final class AutomatonTermsEnum {

    private enum AcceptStatus { YES, YES_AND_SEEK, NO, NO_AND_SEEK }

    private final SortedTermSource source;
    private final ByteRunAutomaton runAutomaton;
    private final Automaton automaton;
    private final boolean finite;
    private final byte[] commonSuffix;
    private final int[] visited;
    private int curGen;

    private byte[] seek = new byte[16];
    private int seekLen;
    private int[] savedStates = new int[16];

    private boolean linear;
    private byte[] linearUpperBound = new byte[16];
    private int linearUpperBoundLen;

    private byte[] actualTerm;
    private boolean doSeek = true;
    private boolean exhausted;

    public AutomatonTermsEnum(SortedTermSource source, CompiledAutomaton compiled) {
        if (compiled.getType() != CompiledAutomaton.Type.NORMAL) {
            throw new IllegalArgumentException("please use CompiledAutomaton.intersect for type " + compiled.getType());
        }
        this.source = source;
        this.runAutomaton = compiled.getRunAutomaton();
        this.automaton = runAutomaton.getAutomaton();
        this.finite = compiled.isFinite();
        this.commonSuffix = compiled.getCommonSuffix();
        this.visited = new int[runAutomaton.getSize()];
    }

    public byte[] next() {
        if (exhausted) {
            return null;
        }
        while (true) {
            if (doSeek) {
                doSeek = false;
                byte[] t = nextSeekTerm(actualTerm);
                if (t == null) {
                    exhausted = true;
                    return null;
                }
                actualTerm = source.seekCeil(t);
                if (actualTerm == null) {
                    exhausted = true;
                    return null;
                }
            } else {
                actualTerm = source.next();
                if (actualTerm == null) {
                    exhausted = true;
                    return null;
                }
            }
            switch (accept(actualTerm)) {
                case YES_AND_SEEK:
                    doSeek = true;
                    return actualTerm;
                case YES:
                    return actualTerm;
                case NO_AND_SEEK:
                    doSeek = true;
                    break;
                default:
                    break;
            }
        }
    }

    private AcceptStatus accept(byte[] term) {
        if (commonSuffix.length == 0 || endsWith(term, commonSuffix)) {
            if (runAutomaton.run(term)) {
                return linear ? AcceptStatus.YES : AcceptStatus.YES_AND_SEEK;
            }
        }
        return (linear && Arrays.compareUnsigned(term, 0, term.length, linearUpperBound, 0, linearUpperBoundLen) < 0)
            ? AcceptStatus.NO : AcceptStatus.NO_AND_SEEK;
    }

    private static boolean endsWith(byte[] term, byte[] suffix) {
        if (term.length < suffix.length) {
            return false;
        }
        int off = term.length - suffix.length;
        for (int i = 0; i < suffix.length; i++) {
            if (term[off + i] != suffix[i]) {
                return false;
            }
        }
        return true;
    }

    private byte[] nextSeekTerm(byte[] term) {
        if (term == null) {
            seekLen = 0;
            if (runAutomaton.isAccept(0)) {
                return new byte[0];
            }
        } else {
            ensureSeek(term.length);
            System.arraycopy(term, 0, seek, 0, term.length);
            seekLen = term.length;
        }
        if (nextString()) {
            return Arrays.copyOf(seek, seekLen);
        }
        return null;
    }

    private void ensureSeek(int len) {
        if (seek.length < len) {
            seek = Arrays.copyOf(seek, Math.max(len, seek.length * 2));
        }
    }

    private void setLinear(int position) {
        int state = 0;
        int maxInterval = 0xff;
        for (int i = 0; i < position; i++) {
            state = runAutomaton.step(state, seek[i] & 0xff);
        }
        int b = seek[position] & 0xff;
        int nt = automaton.getNumTransitions(state);
        for (int i = 0; i < nt; i++) {
            if (automaton.getMin(state, i) <= b && b <= automaton.getMax(state, i)) {
                maxInterval = automaton.getMax(state, i);
                break;
            }
        }
        if (maxInterval != 0xff) {
            maxInterval++;
        }
        int length = position + 1;
        if (linearUpperBound.length < length) {
            linearUpperBound = new byte[length];
        }
        System.arraycopy(seek, 0, linearUpperBound, 0, position);
        linearUpperBound[position] = (byte) maxInterval;
        linearUpperBoundLen = length;
        linear = true;
    }

    private boolean nextString() {
        int state;
        int pos = 0;
        if (savedStates.length < seekLen + 1) {
            savedStates = Arrays.copyOf(savedStates, seekLen + 1);
        }
        savedStates[0] = 0;
        while (true) {
            curGen++;
            linear = false;
            for (state = savedStates[pos]; pos < seekLen; pos++) {
                visited[state] = curGen;
                int nextState = runAutomaton.step(state, seek[pos] & 0xff);
                if (nextState == -1) {
                    break;
                }
                savedStates[pos + 1] = nextState;
                if (!finite && !linear && visited[nextState] == curGen) {
                    setLinear(pos);
                }
                state = nextState;
            }
            if (nextString(state, pos)) {
                return true;
            }
            if ((pos = backtrack(pos)) < 0) {
                return false;
            }
            int newState = runAutomaton.step(savedStates[pos], seek[pos] & 0xff);
            if (newState >= 0 && runAutomaton.isAccept(newState)) {
                return true;
            }
            if (!finite) {
                pos = 0;
            }
        }
    }

    private boolean nextString(int state, int position) {
        int c = 0;
        if (position < seekLen) {
            c = seek[position] & 0xff;
            if (c++ == 0xff) {
                return false;
            }
        }
        seekLen = position;
        visited[state] = curGen;
        int nt = automaton.getNumTransitions(state);
        for (int i = 0; i < nt; i++) {
            int max = automaton.getMax(state, i);
            if (max >= c) {
                int nextChar = Math.max(c, automaton.getMin(state, i));
                append(nextChar);
                state = automaton.getDest(state, i);
                while (visited[state] != curGen && !runAutomaton.isAccept(state)) {
                    visited[state] = curGen;
                    int min = automaton.getMin(state, 0);
                    state = automaton.getDest(state, 0);
                    append(min);
                    if (!finite && !linear && visited[state] == curGen) {
                        setLinear(seekLen - 1);
                    }
                }
                return true;
            }
        }
        return false;
    }

    private void append(int b) {
        ensureSeek(seekLen + 1);
        seek[seekLen++] = (byte) b;
        if (savedStates.length < seekLen + 1) {
            savedStates = Arrays.copyOf(savedStates, Math.max(seekLen + 1, savedStates.length * 2));
        }
    }

    private int backtrack(int position) {
        while (position-- > 0) {
            int nextChar = seek[position] & 0xff;
            if (nextChar++ != 0xff) {
                seek[position] = (byte) nextChar;
                seekLen = position + 1;
                return position;
            }
        }
        return -1;
    }
}
