package com.naqqa.elasticsearch.common.automaton;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class CompiledAutomaton {

    public enum Type { NONE, ALL, SINGLE, NORMAL }

    private final Type type;
    private final byte[] term;
    private final Automaton automaton;
    private final ByteRunAutomaton runAutomaton;
    private final byte[] commonPrefix;
    private final byte[] commonSuffix;
    private final boolean finite;

    public CompiledAutomaton(Automaton automaton) {
        this(automaton, false, Operations.DEFAULT_MAX_DETERMINIZED_STATES);
    }

    public CompiledAutomaton(Automaton automaton, boolean isBinary, int maxDeterminizedStates) {
        int maxLabel = isBinary ? Automaton.MAX_BYTE : Automaton.MAX_CODE_POINT;
        Automaton min = Operations.minimize(automaton, maxDeterminizedStates);
        if (Operations.isEmpty(min)) {
            type = Type.NONE;
            term = null;
            this.automaton = null;
            runAutomaton = null;
            commonPrefix = new byte[0];
            commonSuffix = new byte[0];
            finite = true;
            return;
        }
        if (Operations.isTotal(min, 0, maxLabel)) {
            type = Type.ALL;
            term = null;
            this.automaton = null;
            runAutomaton = null;
            commonPrefix = new byte[0];
            commonSuffix = new byte[0];
            finite = false;
            return;
        }
        int[] singleton = Operations.getSingleton(min);
        if (singleton != null) {
            type = Type.SINGLE;
            term = toBytes(singleton, isBinary);
            this.automaton = null;
            runAutomaton = null;
            commonPrefix = term.clone();
            commonSuffix = new byte[0];
            finite = true;
            return;
        }
        type = Type.NORMAL;
        term = null;
        finite = Operations.isFinite(min);
        Automaton binary = isBinary ? min : new UTF32ToUTF8().convert(min, maxDeterminizedStates);
        binary = Operations.minimize(binary, maxDeterminizedStates);
        this.automaton = binary;
        this.runAutomaton = new ByteRunAutomaton(binary, true, maxDeterminizedStates);
        this.commonPrefix = Operations.getCommonPrefixBytes(binary);
        if (finite) {
            this.commonSuffix = new byte[0];
        } else {
            byte[] suffix = Operations.getCommonPrefixBytes(Operations.reverse(binary));
            byte[] reversed = new byte[suffix.length];
            for (int i = 0; i < suffix.length; i++) {
                reversed[i] = suffix[suffix.length - 1 - i];
            }
            this.commonSuffix = reversed;
        }
    }

    private static byte[] toBytes(int[] labels, boolean isBinary) {
        if (isBinary) {
            byte[] out = new byte[labels.length];
            for (int i = 0; i < labels.length; i++) {
                out[i] = (byte) labels[i];
            }
            return out;
        }
        return new String(labels, 0, labels.length).getBytes(StandardCharsets.UTF_8);
    }

    public Type getType() {
        return type;
    }

    public byte[] getTerm() {
        return term;
    }

    public Automaton getAutomaton() {
        return automaton;
    }

    public ByteRunAutomaton getRunAutomaton() {
        return runAutomaton;
    }

    public byte[] getCommonPrefix() {
        return commonPrefix;
    }

    public byte[] getCommonSuffix() {
        return commonSuffix;
    }

    public boolean isFinite() {
        return finite;
    }

    public boolean run(byte[] bytes) {
        return switch (type) {
            case NONE -> false;
            case ALL -> true;
            case SINGLE -> Arrays.equals(term, bytes);
            case NORMAL -> runAutomaton.run(bytes);
        };
    }

    public TermIterator intersect(SortedTermSource source) {
        switch (type) {
            case NONE:
                return () -> null;
            case ALL: {
                boolean[] started = {false};
                return () -> {
                    if (!started[0]) {
                        started[0] = true;
                        return source.seekCeil(new byte[0]);
                    }
                    return source.next();
                };
            }
            case SINGLE: {
                boolean[] done = {false};
                return () -> {
                    if (done[0]) {
                        return null;
                    }
                    done[0] = true;
                    byte[] t = source.seekCeil(term);
                    return t != null && Arrays.equals(t, term) ? t : null;
                };
            }
            default: {
                AutomatonTermsEnum e = new AutomatonTermsEnum(source, this);
                return e::next;
            }
        }
    }

    @FunctionalInterface
    public interface TermIterator {
        byte[] next();
    }
}
