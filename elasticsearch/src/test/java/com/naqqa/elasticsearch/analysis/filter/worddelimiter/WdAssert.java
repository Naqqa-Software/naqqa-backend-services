package com.naqqa.elasticsearch.analysis.filter.worddelimiter;

import com.naqqa.elasticsearch.analysis.Token;
import com.naqqa.elasticsearch.analysis.TokenStream;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.naqqa.elasticsearch.test.Assert.assertEquals;

final class WdAssert {

    private WdAssert() {
    }

    static List<Token> collect(TokenStream ts) {
        List<Token> out = new ArrayList<>();
        ts.reset();
        while (ts.incrementToken()) {
            out.add(ts.token().copy());
        }
        ts.end();
        ts.close();
        return out;
    }

    static void assertTokens(TokenStream ts, String[] terms, int[] starts, int[] ends, int[] posIncs, int[] posLens) {
        List<Token> tokens = collect(ts);
        String[] actual = new String[tokens.size()];
        for (int i = 0; i < actual.length; i++) {
            actual[i] = tokens.get(i).term();
        }
        assertEquals(terms, actual, "terms");
        if (starts != null) {
            assertEquals(starts, tokens.stream().mapToInt(Token::startOffset).toArray(), "start offsets of " + tokens);
        }
        if (ends != null) {
            assertEquals(ends, tokens.stream().mapToInt(Token::endOffset).toArray(), "end offsets of " + tokens);
        }
        if (posIncs != null) {
            assertEquals(posIncs, tokens.stream().mapToInt(Token::positionIncrement).toArray(), "position increments of " + tokens);
        }
        if (posLens != null) {
            assertEquals(posLens, tokens.stream().mapToInt(Token::positionLength).toArray(), "position lengths of " + tokens);
        }
    }

    static void assertTerms(TokenStream ts, String... terms) {
        assertTokens(ts, terms, null, null, null, null);
    }

    static Set<String> graphStrings(TokenStream ts) {
        List<Token> tokens = collect(ts);
        List<int[]> arcs = new ArrayList<>();
        int pos = -1;
        for (Token t : tokens) {
            pos += t.positionIncrement();
            arcs.add(new int[] {pos, pos + t.positionLength()});
        }
        Set<String> out = new HashSet<>();
        if (tokens.isEmpty()) {
            return out;
        }
        int startNode = arcs.get(0)[0];
        walk(startNode, tokens, arcs, new StringBuilder(), out);
        return out;
    }

    private static void walk(int node, List<Token> tokens, List<int[]> arcs, StringBuilder path, Set<String> out) {
        boolean any = false;
        for (int i = 0; i < arcs.size(); i++) {
            if (arcs.get(i)[0] == node) {
                any = true;
                int len = path.length();
                if (len > 0) {
                    path.append(' ');
                }
                path.append(tokens.get(i).term());
                walk(arcs.get(i)[1], tokens, arcs, path, out);
                path.setLength(len);
            }
        }
        if (!any) {
            out.add(path.toString());
        }
    }

    static void assertGraphStrings(TokenStream ts, String... expected) {
        assertEquals(new HashSet<>(List.of(expected)), graphStrings(ts));
    }
}
