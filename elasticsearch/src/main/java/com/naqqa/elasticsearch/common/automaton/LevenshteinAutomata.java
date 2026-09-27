package com.naqqa.elasticsearch.common.automaton;

public final class LevenshteinAutomata {

    private final int[] word;
    private final int maxEdits;
    private final boolean transpositions;

    public LevenshteinAutomata(String s, int maxEdits, boolean transpositions) {
        this(s.codePoints().toArray(), maxEdits, transpositions);
    }

    public LevenshteinAutomata(int[] word, int maxEdits, boolean transpositions) {
        if (maxEdits < 0 || maxEdits > 2) {
            throw new IllegalArgumentException("maxEdits must be between 0 and 2, got " + maxEdits);
        }
        this.word = word;
        this.maxEdits = maxEdits;
        this.transpositions = transpositions;
    }

    public Automaton toAutomaton() {
        return toAutomaton(0);
    }

    public Automaton toAutomaton(int prefixLength) {
        int n = word.length;
        prefixLength = Math.max(0, Math.min(prefixLength, n));
        Automaton a = new Automaton();
        int[][] normal = new int[n + 1][maxEdits + 1];
        int[][] trans = transpositions && n > 1 ? new int[n - 1][Math.max(maxEdits, 1)] : null;
        for (int i = 0; i <= n; i++) {
            for (int e = 0; e <= maxEdits; e++) {
                normal[i][e] = a.createState();
            }
        }
        if (transpositions && n > 1) {
            for (int i = 0; i < n - 1; i++) {
                for (int e = 0; e < maxEdits; e++) {
                    trans[i][e] = a.createState();
                }
            }
        }
        for (int e = 0; e <= maxEdits; e++) {
            a.setAccept(normal[n][e], true);
        }
        for (int i = 0; i <= n; i++) {
            boolean editsAllowedHere = i >= prefixLength;
            for (int e = 0; e <= maxEdits; e++) {
                int src = normal[i][e];
                if (i < n) {
                    int c = word[i];
                    a.addTransition(src, normal[i + 1][e], c, c);
                    if (editsAllowedHere && e < maxEdits) {
                        addAllExcept(a, src, normal[i + 1][e + 1], c);
                        a.addEpsilon(src, normal[i + 1][e + 1]);
                        if (transpositions && i + 1 < n) {
                            int c2 = word[i + 1];
                            if (c2 != c) {
                                a.addTransition(src, trans[i][e], c2, c2);
                            }
                        }
                    }
                }
                if (editsAllowedHere && e < maxEdits) {
                    a.addTransition(src, normal[i][e + 1], 0, Automaton.MAX_CODE_POINT);
                }
            }
        }
        if (transpositions && n > 1) {
            for (int i = 0; i < n - 1; i++) {
                for (int e = 0; e < maxEdits; e++) {
                    int c = word[i];
                    a.addTransition(trans[i][e], normal[i + 2][e + 1], c, c);
                }
            }
        }
        a.finish();
        Automaton noEps = Operations.removeEpsilons(a);
        Automaton dfa = Operations.determinize(noEps, Operations.DEFAULT_MAX_DETERMINIZED_STATES);
        return Operations.minimize(dfa, Operations.DEFAULT_MAX_DETERMINIZED_STATES);
    }

    private static void addAllExcept(Automaton a, int src, int dest, int exclude) {
        if (exclude > 0) {
            a.addTransition(src, dest, 0, exclude - 1);
        }
        if (exclude < Automaton.MAX_CODE_POINT) {
            a.addTransition(src, dest, exclude + 1, Automaton.MAX_CODE_POINT);
        }
    }

    public static Automaton toAutomaton(String s, int maxEdits, boolean transpositions, int prefixLength) {
        return new LevenshteinAutomata(s, maxEdits, transpositions).toAutomaton(prefixLength);
    }
}
