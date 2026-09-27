package com.naqqa.elasticsearch.common.automaton;

public final class CharacterRunAutomaton extends RunAutomaton {

    public CharacterRunAutomaton(Automaton a) {
        this(a, Operations.DEFAULT_MAX_DETERMINIZED_STATES);
    }

    public CharacterRunAutomaton(Automaton a, int maxDeterminizedStates) {
        super(a, Automaton.MAX_CODE_POINT + 1, maxDeterminizedStates);
    }

    public boolean run(String s) {
        int p = 0;
        int len = s.length();
        for (int i = 0; i < len; ) {
            int cp = s.codePointAt(i);
            p = step(p, cp);
            if (p == -1) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return accept[p];
    }

    public boolean run(char[] s, int offset, int length) {
        int p = 0;
        int end = offset + length;
        for (int i = offset; i < end; ) {
            int cp = Character.codePointAt(s, i, end);
            p = step(p, cp);
            if (p == -1) {
                return false;
            }
            i += Character.charCount(cp);
        }
        return accept[p];
    }

    public boolean run(int[] codePoints) {
        int p = 0;
        for (int cp : codePoints) {
            p = step(p, cp);
            if (p == -1) {
                return false;
            }
        }
        return accept[p];
    }
}
